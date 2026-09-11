package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ADR-030 explicit backfill: {@link SupplierProductLink#getFingerprint()} is a PERSISTED value
 * computed at link-confirmation time by {@link RowAttributeNormalizer}. Bumping {@link
 * RowAttributeNormalizer#NORMALIZATION_VERSION} (e.g. making the fingerprint's brand component
 * alias-canonical instead of raw text) changes what string the SAME product now produces - a link
 * still carrying an OLDER version's fingerprint must never be silently left to fail every future
 * {@code findByShopIdAndFingerprintAndProductIsNotNull} lookup just because nobody recomputed it.
 *
 * <p>Recomputation is idempotent and self-healing by construction: it always derives the new
 * fingerprint from the link's live {@code Product} + the shop's CURRENT {@link BrandAliasResolver}
 * data, the exact same way a fresh row would be normalized today. Running this repeatedly (this
 * shop's alias configuration can keep changing after go-live) is always safe - a link already at
 * the current version is simply excluded from the next page by the repository query, so a
 * fully-migrated shop costs one cheap empty-result query per scheduled run.
 *
 * <p>One link's recompute failure (e.g. its {@code Product} was deleted between the query and the
 * update) is isolated and logged - it must never abort the whole batch, mirroring every other
 * automatic pipeline job in this codebase ({@code ImportRetentionJob}, the pipeline stage jobs).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupplierLinkFingerprintMigrationService {

    private final SupplierProductLinkRepository supplierProductLinkRepository;
    private final RowAttributeNormalizer normalizer;
    private final SupplierImportProperties properties;

    @Scheduled(
            fixedDelayString = "${supplier-import.matching.fingerprint-backfill.interval-ms:3600000}",
            initialDelayString = "${supplier-import.matching.fingerprint-backfill.initial-delay-ms:90000}")
    public void backfill() {
        SupplierImportProperties.FingerprintBackfill config = properties.getMatching().getFingerprintBackfill();
        if (!config.isEnabled()) {
            return;
        }

        List<SupplierProductLink> stale = supplierProductLinkRepository
                .findByNormalizationVersionLessThanAndFingerprintIsNotNullAndProductIsNotNullOrderByIdAsc(
                        RowAttributeNormalizer.NORMALIZATION_VERSION, PageRequest.of(0, config.getBatchSize()));
        if (stale.isEmpty()) {
            return;
        }

        log.info("Fingerprint backfill: recomputing {} SupplierProductLink(s) still on an older "
                + "normalization version (current={})", stale.size(), RowAttributeNormalizer.NORMALIZATION_VERSION);
        int migrated = 0;
        int failed = 0;
        for (SupplierProductLink link : stale) {
            if (recomputeOne(link.getId())) {
                migrated++;
            } else {
                failed++;
            }
        }
        log.info("Fingerprint backfill finished: {} migrated, {} failed/skipped", migrated, failed);
    }

    @Transactional
    boolean recomputeOne(Long linkId) {
        try {
            SupplierProductLink link = supplierProductLinkRepository.findById(linkId).orElse(null);
            if (link == null || link.getProduct() == null) {
                return false;
            }
            Product product = link.getProduct();
            NormalizedRowData normalized = normalizer.normalizeProduct(link.getShopId(), product);
            link.setFingerprint(normalized.fingerprint());
            link.setNormalizationVersion(RowAttributeNormalizer.NORMALIZATION_VERSION);
            supplierProductLinkRepository.save(link);
            return true;
        } catch (RuntimeException e) {
            log.warn("Fingerprint backfill: failed to recompute SupplierProductLink {}", linkId, e);
            return false;
        }
    }
}
