package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ImportRowRepository extends JpaRepository<ImportRow, Long> {

    List<ImportRow> findByImportBatchId(Long importBatchId);

    List<ImportRow> findByImportBatchIdAndStatus(Long importBatchId, ImportRowStatus status);

    /** Prompt 05 gates every row still awaiting an automation decision: PENDING (needs AI/new-product
     * evaluation) plus the already-safe-but-ungated EXACT_MATCH/LEARNED_MATCH rows from Prompt 04. */
    List<ImportRow> findByImportBatchIdAndStatusIn(Long importBatchId, List<ImportRowStatus> statuses);

    // ========== Prompt 07 operations UI ==========

    Optional<ImportRow> findByShopIdAndId(String shopId, Long id);

    @Query("SELECT r FROM ImportRow r JOIN FETCH r.importBatch b LEFT JOIN FETCH r.matchedProduct "
            + "WHERE r.shopId = :shopId AND r.id = :id")
    Optional<ImportRow> findDetailByShopIdAndId(@Param("shopId") String shopId, @Param("id") Long id);

    Page<ImportRow> findByImportBatchIdOrderBySourceRowNumberAsc(Long importBatchId, Pageable pageable);

    Page<ImportRow> findByImportBatchIdAndStatusInOrderBySourceRowNumberAsc(
            Long importBatchId, List<ImportRowStatus> statuses, Pageable pageable);

    List<ImportRow> findByIdInAndShopId(List<Long> ids, String shopId);

    /** Row-status breakdown for one batch's detail page (docs/ARCHITECTURE.md §12). */
    @Query("SELECT r.status, COUNT(r) FROM ImportRow r WHERE r.importBatch.id = :batchId GROUP BY r.status")
    List<Object[]> countByStatusForBatch(@Param("batchId") Long batchId);

    long countByShopIdAndStatusIn(String shopId, List<ImportRowStatus> statuses);

    long countByShopId(String shopId);

    long countByShopIdAndCreatedAtAfter(String shopId, LocalDateTime since);

    /**
     * Cross-batch exception queue (Prompt 07): every row of this shop currently sitting in one of
     * the given statuses, optionally narrowed to one supplier. Deliberately a JOIN through
     * {@code importBatch.supplierSource.supplier} rather than a denormalized column on
     * {@code ImportRow} - supplier attribution always flows through the batch it belongs to.
     */
    @Query("SELECT r FROM ImportRow r JOIN r.importBatch b JOIN b.supplierSource s "
            + "WHERE r.shopId = :shopId AND r.status IN :statuses "
            + "AND (:supplierId IS NULL OR s.supplier.id = :supplierId) "
            + "ORDER BY r.createdAt DESC")
    Page<ImportRow> findExceptionRows(
            @Param("shopId") String shopId,
            @Param("statuses") List<ImportRowStatus> statuses,
            @Param("supplierId") Long supplierId,
            Pageable pageable);

    /**
     * Per-supplier exception-rate breakdown for the automation dashboard (docs/ARCHITECTURE.md
     * §15 "exception rate по каждому поставщику"). Returns raw tuples
     * {@code (supplierId, supplierName, totalRows, exceptionRows)} - mapped to a proper DTO in
     * {@code ImportDashboardService} to keep this query portable across H2 and PostgreSQL.
     */
    @Query("SELECT s.supplier.id, s.supplier.name, COUNT(r), "
            + "SUM(CASE WHEN r.status = com.plstk.loyaltybot.entity.importing.ImportRowStatus.NEEDS_REVIEW "
            + "OR r.status = com.plstk.loyaltybot.entity.importing.ImportRowStatus.INVALID THEN 1 ELSE 0 END) "
            + "FROM ImportRow r JOIN r.importBatch b JOIN b.supplierSource s "
            + "WHERE r.shopId = :shopId GROUP BY s.supplier.id, s.supplier.name")
    List<Object[]> countExceptionsBySupplier(@Param("shopId") String shopId);
}
