package com.plstk.loyaltybot.service.importing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thin orchestrator for the Prompt 06 Apply stage: claims the {@code AUTO_APPROVED}/{@code
 * APPROVED -&gt; APPLYING} transition (or, for a batch already found {@code APPLYING} at job start -
 * i.e. a crash-recovery resume - skips the claim, since it is already held) and delegates the
 * actual work to {@link ImportBatchApplyWriter#applyBatch}, one whole batch per DB transaction. Any
 * unexpected exception moves the batch to {@code FAILED} with a reason, mirroring every earlier
 * stage's error handling - it never leaves a batch silently stuck.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchApplyService {

    private final ImportBatchApplyWriter writer;
    private final SupplierImportMetrics metrics;

    /** For batches currently {@code AUTO_APPROVED} or {@code APPROVED}. */
    public void applyNewly(Long batchId) {
        if (!writer.claimForApplying(batchId)) {
            log.debug("Batch {} is not AUTO_APPROVED/APPROVED (already claimed/processed) - skipping", batchId);
            return;
        }
        runApply(batchId);
    }

    /**
     * For a batch already {@code APPLYING} when the job started - either another concurrent
     * apply is genuinely still in progress (in which case {@link ImportBatchApplyWriter#applyBatch}
     * is a no-op because the {@code AUTO_APPROVED}/{@code APPROVED} claim already happened) or the
     * previous attempt crashed before committing, and this call is the "automatic resume/recovery
     * after restart" retry.
     */
    public void resumeApplying(Long batchId) {
        runApply(batchId);
    }

    private void runApply(Long batchId) {
        try {
            writer.applyBatch(batchId);
            metrics.batchApply("applied");
        } catch (Exception e) {
            log.error("Batch {} failed with unexpected error during apply", batchId, e);
            metrics.batchApply("failed");
            writer.finalizeFailed(batchId, "Unexpected apply error: " + safeMessage(e));
        }
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }
}
