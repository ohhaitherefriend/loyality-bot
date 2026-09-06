package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
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
}
