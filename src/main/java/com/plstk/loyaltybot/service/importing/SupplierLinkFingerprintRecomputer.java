package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-031 (Section 6): the per-link recompute step, deliberately split into its OWN Spring bean.
 *
 * <p>The original design had {@code SupplierLinkFingerprintMigrationService#backfill()} call
 * {@code this.recomputeOne(linkId)} - a same-class ("self-invocation") call. Spring's
 * {@code @Transactional} is implemented via a proxy wrapping the bean; a call from ONE method of a
 * bean to ANOTHER method of the SAME bean instance goes directly through the raw {@code this}
 * reference and never passes through that proxy, so {@code @Transactional} on the callee is
 * silently a no-op - no transaction actually opens. {@code SupplierProductLink.product} is a
 * {@code LAZY} association, so accessing {@code link.getProduct()} without a REAL active
 * transaction/session either throws a {@code LazyInitializationException} or - worse, depending
 * on whatever incidental session context happens to still be open from the read - silently
 * appears to work in tests while remaining broken in production, where no such incidental context
 * exists. Calling this class's {@link #recomputeOne} from a DIFFERENT bean goes through the real
 * Spring proxy, so {@code @Transactional} genuinely opens a transaction for each call.
 */
@Component
@RequiredArgsConstructor
public class SupplierLinkFingerprintRecomputer {

    private final SupplierProductLinkRepository supplierProductLinkRepository;
    private final RowAttributeNormalizer normalizer;

    /**
     * One independent transaction per link: a failure recomputing one link rolls back ONLY that
     * link's own transaction (nothing else was written in it anyway) and propagates the exception
     * back to the caller, which handles it AFTER this transaction has already completed (committed
     * or rolled back) - see {@code SupplierLinkFingerprintMigrationService#backfill}. This is what
     * makes one failing link never able to leave any other link (or itself, on the next scheduled
     * run) permanently stuck.
     *
     * @return true if recomputed, false if the link (or its product) no longer exists - not an
     *     error, just nothing to do.
     */
    @Transactional
    public boolean recomputeOne(Long linkId) {
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
    }
}
