package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Stage 2 of the production-hardening pass: the dedicated service behind
 * {@code POST /api/shops/{shopId}/operations/batches/{batchId}/approve}, which is the only
 * supported way to move a batch {@code NEEDS_ATTENTION -&gt; APPROVED} (the controller never talks
 * to {@link ImportBatchRepository#approveNeedsAttention} directly).
 *
 * <p>Approval is more than a status flip: it re-validates that nothing changed underneath the
 * operator since the batch was decided (no row still needs a human {@code NEEDS_REVIEW}/{@code
 * INVALID} decision, and the Prompt 06 batch-level guards still pass right now, not just when the
 * batch first reached {@code VALIDATING}), and records who approved it and when. Approval itself
 * is a single conditional {@code UPDATE ... WHERE status = NEEDS_ATTENTION}, so two concurrent
 * approve calls (double-click, or an operator racing the read-only re-check above with another
 * browser tab) can never both succeed - only one wins the atomic transition.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchApprovalService {

    private static final List<ImportRowStatus> APPLIABLE_STATUSES =
            List.of(ImportRowStatus.AUTO_APPROVED, ImportRowStatus.APPROVED);

    private static final List<ImportRowStatus> UNRESOLVED_STATUSES =
            List.of(ImportRowStatus.NEEDS_REVIEW, ImportRowStatus.INVALID);

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final BatchApplyGuardEvaluator guardEvaluator;

    /**
     * @return the approved batch, or empty if it does not exist / does not belong to this shop.
     * @throws RowReviewException if the batch is not in {@code NEEDS_ATTENTION}, still has
     *     unresolved rows, or fails re-evaluated guards (in which case it is quarantined instead of
     *     silently staying in {@code NEEDS_ATTENTION}, so the operator sees an explicit new state
     *     rather than a repeatedly-failing approve button).
     */
    @Transactional
    public Optional<ImportBatch> approve(String shopId, Long batchId, AdminUser approver) {
        Optional<ImportBatch> batchOpt = importBatchRepository.findByIdWithSupplierSourceAndSupplier(batchId)
                .filter(b -> b.getShopId().equals(shopId));
        if (batchOpt.isEmpty()) {
            return Optional.empty();
        }
        ImportBatch batch = batchOpt.get();

        if (batch.getStatus() != ImportBatchStatus.NEEDS_ATTENTION) {
            throw new RowReviewException(
                    "Batch " + batchId + " is not in NEEDS_ATTENTION (currently " + batch.getStatus() + ")");
        }

        long unresolved = importRowRepository.findByImportBatchIdAndStatusIn(batchId, UNRESOLVED_STATUSES).size();
        if (unresolved > 0) {
            throw new RowReviewException(
                    "Batch " + batchId + " has " + unresolved
                            + " row(s) still in NEEDS_REVIEW/INVALID that require a decision before approval");
        }

        List<ImportRow> appliableRows = importRowRepository.findByImportBatchIdAndStatusIn(batchId, APPLIABLE_STATUSES);
        GuardResult guardResult = guardEvaluator.evaluate(batch, appliableRows);
        if (!guardResult.passed()) {
            String reason = "Re-evaluated guard(s) failed at approval time: " + String.join("; ", guardResult.reasons());
            int quarantined = importBatchRepository.finalizeQuarantineFrom(
                    batchId, ImportBatchStatus.NEEDS_ATTENTION, reason, LocalDateTime.now());
            if (quarantined == 1) {
                log.warn("Batch {} failed re-evaluated guards at approval time -> QUARANTINED: {}", batchId, reason);
            }
            throw new RowReviewException(reason);
        }

        Long approverId = approver != null ? approver.getId() : null;
        String approverEmail = approver != null ? approver.getEmail() : null;
        int updated = importBatchRepository.approveNeedsAttention(batchId, approverId, approverEmail, LocalDateTime.now());
        if (updated == 0) {
            // Lost a concurrent race (another approve call, or the batch moved on some other way).
            throw new RowVersionConflictException(null);
        }
        log.info("Batch {} approved by userId={} ({}) -> APPROVED", batchId, approverId, approverEmail);
        return importBatchRepository.findByShopIdAndId(shopId, batchId);
    }
}
