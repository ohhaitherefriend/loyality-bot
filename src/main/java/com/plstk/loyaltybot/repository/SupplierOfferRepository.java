package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierOfferRepository extends JpaRepository<SupplierOffer, Long> {

    Optional<SupplierOffer> findByShopIdAndSupplierIdAndProductId(String shopId, Long supplierId, Long productId);

    List<SupplierOffer> findByShopIdAndProductIdAndActiveTrue(String shopId, Long productId);

    /**
     * Active offers of one supplier, within one FULL-snapshot {@code snapshotScope}, not seen in
     * {@code currentBatchId} - the Prompt 06 reconciliation deactivation candidates
     * (docs/ARCHITECTURE.md D-004: "деактивируй offers в том же supplier + snapshotScope").
     * Deliberately scoped by {@code supplier + snapshotScope} rather than just
     * {@code supplierSourceId}: several {@code SupplierSource}s of the same supplier can share one
     * {@code snapshotScope} (e.g. re-sent duplicate-category files), and a partial-scope file from
     * one source must never deactivate offers that belong to a different source/scope.
     */
    @Query("SELECT o FROM SupplierOffer o WHERE o.shopId = :shopId AND o.supplier.id = :supplierId "
            + "AND o.supplierSource.snapshotScope = :snapshotScope AND o.active = true "
            + "AND (o.lastSeenBatch IS NULL OR o.lastSeenBatch.id <> :currentBatchId)")
    List<SupplierOffer> findStaleActiveOffersInScope(
            @Param("shopId") String shopId,
            @Param("supplierId") Long supplierId,
            @Param("snapshotScope") String snapshotScope,
            @Param("currentBatchId") Long currentBatchId);
}
