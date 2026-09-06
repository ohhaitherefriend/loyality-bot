package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    Optional<ImportBatch> findByImportFileId(Long importFileId);

    Optional<ImportBatch> findByShopIdAndId(String shopId, Long id);

    Page<ImportBatch> findByShopId(String shopId, Pageable pageable);

    List<ImportBatch> findByStatusOrderByIdAsc(ImportBatchStatus status);

    List<ImportBatch> findByStatusInOrderByIdAsc(List<ImportBatchStatus> statuses);

    /**
     * Previous successfully applied batch for the same supplier source, used by the Prompt 06
     * row-count-collapse guard. Excludes the batch currently being evaluated (relevant for
     * idempotent re-runs/tests that reuse the same supplier source).
     */
    Optional<ImportBatch> findTopByShopIdAndSupplierSourceIdAndStatusAndIdNotOrderByFinishedAtDesc(
            String shopId, Long supplierSourceId, ImportBatchStatus status, Long excludedId);

    @Query("SELECT b FROM ImportBatch b JOIN FETCH b.supplierSource s JOIN FETCH b.importFile f WHERE b.id = :id")
    Optional<ImportBatch> findByIdWithSupplierSourceAndFile(@Param("id") Long id);

    /**
     * Prompt 04 needs the supplier id (for {@code SupplierProductLink} lookups) but not the
     * {@code ImportFile}/workbook - fetch-joins {@code supplier} too so
     * {@code ImportBatchNormalizingService} (which runs outside any single open transaction) never
     * risks a {@code LazyInitializationException} navigating {@code supplierSource.supplier}.
     */
    @Query("SELECT b FROM ImportBatch b JOIN FETCH b.supplierSource s JOIN FETCH s.supplier WHERE b.id = :id")
    Optional<ImportBatch> findByIdWithSupplierSourceAndSupplier(@Param("id") Long id);

    /**
     * Atomic STORED -&gt; PARSING transition: repeat-safe by construction, so two concurrent
     * callers (e.g. a job racing with itself across replicas despite the claim/lease) can never
     * both start parsing, or double-insert {@code ImportRow}s for, the same batch.
     */
    @Modifying
    @Query("UPDATE ImportBatch b SET b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.PARSING, "
            + "b.startedAt = :startedAt WHERE b.id = :id AND b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.STORED")
    int claimForParsing(@Param("id") Long id, @Param("startedAt") LocalDateTime startedAt);

    /**
     * Atomic NORMALIZING -&gt; MATCHING transition (Prompt 04), same repeat-safe pattern as
     * {@link #claimForParsing}: two concurrent callers can never both run normalization/candidate
     * search for, or double-write {@code ImportRow}s of, the same batch.
     */
    @Modifying
    @Query("UPDATE ImportBatch b SET b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.MATCHING "
            + "WHERE b.id = :id AND b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.NORMALIZING")
    int claimForNormalizing(@Param("id") Long id);

    /**
     * Atomic MATCHING -&gt; VALIDATING transition (Prompt 05), same repeat-safe pattern as
     * {@link #claimForNormalizing}: two concurrent callers can never both run the AI matcher/
     * automation gate for, or double-write {@code MatchDecision}s of, the same batch. VALIDATING is
     * where the future batch-level apply guards (Prompt 06) take over - Prompt 05's scope ends once
     * every row has a gated terminal status.
     */
    @Modifying
    @Query("UPDATE ImportBatch b SET b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.VALIDATING "
            + "WHERE b.id = :id AND b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.MATCHING")
    int claimForMatching(@Param("id") Long id);

    /**
     * Atomic transition out of {@code VALIDATING} into whichever terminal-ish decision the Prompt
     * 06 guard evaluation produced ({@code AUTO_APPROVED}/{@code NEEDS_ATTENTION}/
     * {@code QUARANTINED}). The condition on the current status being {@code VALIDATING} is the
     * idempotency guard: a second call on an already-decided batch updates zero rows.
     */
    @Modifying
    @Query("UPDATE ImportBatch b SET b.status = :newStatus, b.errorMessage = :reason, b.finishedAt = :finishedAt "
            + "WHERE b.id = :id AND b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.VALIDATING")
    int transitionFromValidating(
            @Param("id") Long id,
            @Param("newStatus") ImportBatchStatus newStatus,
            @Param("reason") String reason,
            @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * Atomic claim of the Prompt 06 Apply stage: {@code AUTO_APPROVED} or {@code APPROVED} ->
     * {@code APPLYING}. Two concurrent callers (job racing with itself, or a manual
     * trigger racing the scheduled job) can never both start applying the same batch. If the
     * process crashes while a batch sits in {@code APPLYING}, the batch is picked up again as-is on
     * the next job tick/restart (see {@code ImportBatchApplyJob}) without needing this claim again -
     * that is the "automatic resume/recovery after restart" requirement.
     */
    @Modifying
    @Query("UPDATE ImportBatch b SET b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.APPLYING, "
            + "b.startedAt = :startedAt WHERE b.id = :id "
            + "AND (b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.AUTO_APPROVED "
            + "OR b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.APPROVED)")
    int claimForApplying(@Param("id") Long id, @Param("startedAt") LocalDateTime startedAt);

    // ========== Prompt 07 operations UI ==========

    long countByShopId(String shopId);

    long countByShopIdAndStatus(String shopId, ImportBatchStatus status);

    long countByShopIdAndStatusIn(String shopId, List<ImportBatchStatus> statuses);

    Page<ImportBatch> findByShopIdAndStatusInOrderByCreatedAtDesc(
            String shopId, List<ImportBatchStatus> statuses, Pageable pageable);

    @Query("SELECT b FROM ImportBatch b JOIN FETCH b.supplierSource s JOIN FETCH s.supplier "
            + "LEFT JOIN FETCH b.ruleVersion LEFT JOIN FETCH b.importFile WHERE b.shopId = :shopId AND b.id = :id")
    Optional<ImportBatch> findDetailByShopIdAndId(@Param("shopId") String shopId, @Param("id") Long id);

    /** Apply-audit counters summed dashboard-side (docs/ARCHITECTURE.md §15) within an optional window. */
    @Query("SELECT b FROM ImportBatch b WHERE b.shopId = :shopId "
            + "AND b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.APPLIED "
            + "AND (:since IS NULL OR b.appliedAt >= :since)")
    List<ImportBatch> findAppliedSince(@Param("shopId") String shopId, @Param("since") LocalDateTime since);

    /**
     * Best-effort operator "resume" (Prompt 07): resets a stuck {@code QUARANTINED}/{@code FAILED}
     * batch back to the earliest not-yet-completed stage so the corresponding pipeline service can
     * retry it, clearing the stale error/finished timestamps and bumping {@code attemptNumber} so
     * this is a new, explicit attempt rather than a silent overwrite of the previous audit
     * (docs/ARCHITECTURE.md §7 "Повторная обработка должна быть явной операцией").
     */
    @Modifying
    @Query("UPDATE ImportBatch b SET b.status = :targetStatus, b.errorMessage = NULL, b.finishedAt = NULL, "
            + "b.attemptNumber = b.attemptNumber + 1 WHERE b.id = :id "
            + "AND (b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.QUARANTINED "
            + "OR b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.FAILED)")
    int resetForResume(@Param("id") Long id, @Param("targetStatus") ImportBatchStatus targetStatus);

    /**
     * Atomic transition into {@code FAILED} from whichever in-flight status the caller expects the
     * batch to still be in. Every earlier writer's {@code finalizeFailed} used to do an unconditional
     * {@code findById -&gt; setStatus(FAILED) -&gt; save}, with no guard - a losing side of a genuine
     * concurrent-processing race (e.g. an expired lease letting two replicas both process the same
     * batch, see {@code ImportJobClaimService}) could overwrite an already-{@code APPLIED}/other
     * terminal batch back to {@code FAILED}, corrupting the audit trail. Mirrors the same
     * conditional-{@code WHERE status = ...} pattern as every {@code claimForX}/{@code
     * transitionFromValidating} above: zero rows updated means this call lost the race and must not
     * touch the batch at all. {@code clearAutomatically = true} because, unlike the old unconditional
     * {@code findById -&gt; setStatus -&gt; save} it replaced, this bulk update never touches the
     * managed entity in the current session - without clearing, a caller that already loaded this
     * same {@code ImportBatch} earlier in the same transaction (common in tests, and possible in
     * production under a shared persistence context) would keep seeing its pre-update in-memory
     * status instead of the row this call just wrote.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ImportBatch b SET b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.FAILED, "
            + "b.errorMessage = :reason, b.finishedAt = :finishedAt WHERE b.id = :id AND b.status = :expectedStatus")
    int finalizeFailedFrom(
            @Param("id") Long id,
            @Param("expectedStatus") ImportBatchStatus expectedStatus,
            @Param("reason") String reason,
            @Param("finishedAt") LocalDateTime finishedAt);

    /** Same conditional-transition reasoning as {@link #finalizeFailedFrom}, but into {@code QUARANTINED}. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ImportBatch b SET b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.QUARANTINED, "
            + "b.errorMessage = :reason, b.finishedAt = :finishedAt WHERE b.id = :id AND b.status = :expectedStatus")
    int finalizeQuarantineFrom(
            @Param("id") Long id,
            @Param("expectedStatus") ImportBatchStatus expectedStatus,
            @Param("reason") String reason,
            @Param("finishedAt") LocalDateTime finishedAt);

    /** NEEDS_ATTENTION batches are already fully decided; an operator approve = manual graduation to apply. */
    @Modifying
    @Query("UPDATE ImportBatch b SET b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.APPROVED, "
            + "b.errorMessage = NULL WHERE b.id = :id "
            + "AND b.status = com.plstk.loyaltybot.entity.importing.ImportBatchStatus.NEEDS_ATTENTION")
    int approveNeedsAttention(@Param("id") Long id);
}
