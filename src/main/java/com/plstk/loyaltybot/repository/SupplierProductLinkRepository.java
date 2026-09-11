package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierProductLinkRepository extends JpaRepository<SupplierProductLink, Long> {

    Optional<SupplierProductLink> findByShopIdAndSupplierIdAndExternalSku(
            String shopId, Long supplierId, String externalSku);

    Optional<SupplierProductLink> findByShopIdAndSupplierIdAndBarcode(
            String shopId, Long supplierId, String barcode);

    /**
     * Fingerprint-based lookup is intentionally NOT scoped by supplier: a full-name fingerprint
     * confirmed for one supplier's row must still short-circuit AI matching when a different
     * supplier later sends a row that normalizes to the same fingerprint (docs/ARCHITECTURE.md §9.2
     * step 3, "ранее подтверждённый full-name fingerprint"). Returned as a list (not Optional) since
     * there is no unique constraint on fingerprint alone - the caller must treat more than one
     * distinct linked product as ambiguous rather than picking one.
     */
    List<SupplierProductLink> findByShopIdAndFingerprintAndProductIsNotNull(String shopId, String fingerprint);

    /**
     * True if {@code productId} is already linked to some OTHER supplier than {@code supplierId}.
     * Used to reject a coincidental cross-supplier {@code supplierArticle} collision in
     * {@code DeterministicMatchResolver#resolveViaExactSupplierArticle} - a product already
     * "owned" (for identifier-matching purposes) by a different supplier must never be silently
     * re-matched onto a different supplier's row just because an internal SKU string happens to
     * be equal (docs/DECISIONS.md ADR-023).
     */
    boolean existsByShopIdAndProductIdAndSupplierIdNot(String shopId, Long productId, Long supplierId);

    /**
     * ADR-030 backfill source: every link still carrying a fingerprint computed by an OLDER
     * normalization algorithm version, oldest id first (stable, resumable paging across scheduled
     * runs on a large table) - see {@code SupplierLinkFingerprintMigrationService}. Only links that
     * actually HAVE a fingerprint/product need recomputation; a link with neither is unaffected by
     * any fingerprint-algorithm change.
     */
    List<SupplierProductLink> findByNormalizationVersionLessThanAndFingerprintIsNotNullAndProductIsNotNullOrderByIdAsc(
            int normalizationVersion, Pageable pageable);

    long countByNormalizationVersionLessThanAndFingerprintIsNotNullAndProductIsNotNull(int normalizationVersion);
}
