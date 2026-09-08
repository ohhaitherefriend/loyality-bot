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

    /** Scope-aware offer identity lookup (Stage 3) - see {@link SupplierOffer} unique constraint. */
    Optional<SupplierOffer> findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(
            String shopId, Long supplierId, String snapshotScope, Long productId);

    List<SupplierOffer> findByShopIdAndProductIdAndActiveTrue(String shopId, Long productId);

    /**
     * Active offers of one supplier, within one FULL-snapshot {@code snapshotScope}, not seen in
     * {@code currentBatchId} - the Prompt 06 reconciliation deactivation candidates
     * (docs/ARCHITECTURE.md D-004: "деактивируй offers в том же supplier + snapshotScope").
     * Deliberately scoped by {@code supplier + snapshotScope} rather than just
     * {@code supplierSourceId}: several {@code SupplierSource}s of the same supplier can share one
     * {@code snapshotScope} (e.g. re-sent duplicate-category files), and a partial-scope file from
     * one source must never deactivate offers that belong to a different source/scope.
     *
     * <p>Stage 3: filters on the offer's own persisted {@code snapshotScope} column, not a live join
     * to {@code supplierSource.snapshotScope} - the latter tracks whichever source last touched the
     * row and would stop matching (or wrongly start matching) if that source's scope is edited later
     * or if a same-scope offer is later updated by a different {@code SupplierSource}.
     */
    @Query("SELECT o FROM SupplierOffer o WHERE o.shopId = :shopId AND o.supplier.id = :supplierId "
            + "AND o.snapshotScope = :snapshotScope AND o.active = true "
            + "AND (o.lastSeenBatch IS NULL OR o.lastSeenBatch.id <> :currentBatchId)")
    List<SupplierOffer> findStaleActiveOffersInScope(
            @Param("shopId") String shopId,
            @Param("supplierId") Long supplierId,
            @Param("snapshotScope") String snapshotScope,
            @Param("currentBatchId") Long currentBatchId);
}
