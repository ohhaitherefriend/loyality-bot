package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Evaluates the Prompt 06 apply guards for one {@code VALIDATING} {@link ImportBatch} and decides
 * its next status: {@code AUTO_APPROVED} (safe, source graduated to automatic apply),
 * {@code NEEDS_ATTENTION} (safe but the source is in shadow mode or has not graduated to
 * {@code autoApply} yet), or {@code QUARANTINED} (a batch-level guard failed -
 * {@link BatchApplyGuardEvaluator}). This stage never touches a single {@code SupplierOffer} or
 * {@code Product} itself - that is {@link ImportBatchApplyService}'s job once a batch reaches
 * {@code AUTO_APPROVED}/{@code APPROVED}.
 *
 * <p>Unlike the claim-then-process pattern in earlier stages (e.g. {@code ImportBatchMatchingService}),
 * this stage has no external I/O to protect against duplicate work for - guard evaluation is pure DB
 * reads, so it is safe (if slightly wasteful) for two concurrent calls to both compute a result; only
 * one of them will win the final atomic {@code VALIDATING -&gt;} transition
 * ({@code ImportBatchRepository#transitionFromValidating}), which is the actual idempotency guard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchValidationService {

    private static final List<ImportRowStatus> APPLIABLE_STATUSES =
            List.of(ImportRowStatus.AUTO_APPROVED, ImportRowStatus.APPROVED);

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final BatchApplyGuardEvaluator guardEvaluator;
    private final ImportBatchValidationWriter writer;
    private final SupplierImportMetrics metrics;

    public void validateBatch(Long batchId) {
        ImportBatch batch = importBatchRepository.findByIdWithSupplierSourceAndSupplier(batchId).orElse(null);
        if (batch == null || batch.getStatus() != ImportBatchStatus.VALIDATING) {
            log.debug("Batch {} is not in VALIDATING status (already processed) - skipping", batchId);
            return;
        }
        SupplierSource source = batch.getSupplierSource();

        try {
            List<ImportRow> appliableRows = importRowRepository.findByImportBatchIdAndStatusIn(batchId, APPLIABLE_STATUSES);
            GuardResult guardResult = guardEvaluator.evaluate(batch, appliableRows);

            ImportBatchStatus decision;
            String reason;
            if (!guardResult.passed()) {
                decision = ImportBatchStatus.QUARANTINED;
                reason = "Apply guard(s) failed: " + String.join("; ", guardResult.reasons());
            } else if (Boolean.TRUE.equals(source.getShadowMode())) {
                decision = ImportBatchStatus.NEEDS_ATTENTION;
                reason = "Source is in shadow mode: decisions computed, apply withheld pending graduation";
            } else if (!Boolean.TRUE.equals(source.getAutoApply())) {
                decision = ImportBatchStatus.NEEDS_ATTENTION;
                reason = "Source autoApply is disabled: batch requires manual approval before apply";
            } else {
                decision = ImportBatchStatus.AUTO_APPROVED;
                reason = null;
            }

            boolean won = writer.transition(batchId, decision, reason);
            if (won) {
                metrics.batchValidationDecision(decision.name());
                log.info("Batch {} validated: {} appliable row(s) -> {}", batchId, appliableRows.size(), decision);
            } else {
                log.debug("Batch {} was already transitioned out of VALIDATING by another caller", batchId);
            }
        } catch (Exception e) {
            log.error("Batch {} failed with unexpected error during validation", batchId, e);
            metrics.batchValidationDecision(ImportBatchStatus.FAILED.name());
            writer.finalizeFailed(batchId, "Unexpected validation error: " + safeMessage(e));
        }
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }
}
