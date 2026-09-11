package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ADR-030/031 explicit backfill: {@link SupplierProductLink#getFingerprint()} is a PERSISTED value
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
 * <p>ADR-031 (Section 6): the actual per-link recompute+persist work lives in the SEPARATE {@link
 * SupplierLinkFingerprintRecomputer} bean, called here (a genuine cross-bean call, so Spring's
 * {@code @Transactional} proxy is actually engaged - see that class' javadoc for the
 * self-invocation bug this fixes). One link's recompute failure is caught HERE, AFTER its own
 * transaction has already completed (committed nothing, since the failure happened before any
 * write, or rolled back whatever partial state existed) - it is isolated and logged, never
 * aborting the whole batch, mirroring every other automatic pipeline job in this codebase ({@code
 * ImportRetentionJob}, the pipeline stage jobs). A link that fails on one run remains eligible
 * (still below the current version) for the next scheduled run - there is no permanent "stuck"
 * state introduced by one bad record.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupplierLinkFingerprintMigrationService {

    private final SupplierProductLinkRepository supplierProductLinkRepository;
    private final SupplierLinkFingerprintRecomputer recomputer;
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
        int skipped = 0;
        for (SupplierProductLink link : stale) {
            try {
                // Cross-bean call - goes through the real Spring proxy, so @Transactional on
                // recomputeOne genuinely opens/commits/rolls back its OWN transaction per link.
                if (recomputer.recomputeOne(link.getId())) {
                    migrated++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                // Reached AFTER that link's own transaction has already completed (rolled back) -
                // isolated here so one bad link never aborts the rest of this batch or blocks any
                // other link, on this run or the next.
                log.warn("Fingerprint backfill: failed to recompute SupplierProductLink {}", link.getId(), e);
                failed++;
            }
        }
        log.info("Fingerprint backfill finished: {} migrated, {} skipped (no product), {} failed", migrated, skipped, failed);
    }
}
