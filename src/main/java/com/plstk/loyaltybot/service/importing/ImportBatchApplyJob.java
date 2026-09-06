package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Automatically applies every {@code AUTO_APPROVED}/{@code APPROVED} batch (Prompt 06) without any
 * manual step, and additionally re-drives any batch found stuck in {@code APPLYING} - this second
 * query is what makes apply resumable after a crash/restart: {@code APPLYING} is a durable DB state,
 * so whichever replica's job tick finds it there next simply retries
 * {@link ImportBatchApplyWriter#applyBatch}, which is safe to redo because the whole thing is one
 * transaction (either the previous attempt fully committed, in which case the batch is no longer
 * {@code APPLYING} and this call is a no-op, or it never committed anything at all).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportBatchApplyJob {

    private static final String JOB_TYPE = "import-batch-apply";

    private final ImportBatchRepository importBatchRepository;
    private final ImportJobClaimService importJobClaimService;
    private final ImportBatchApplyService importBatchApplyService;
    private final SupplierImportProperties properties;

    @Scheduled(
            fixedDelayString = "${supplier-import.job.apply-interval-ms:30000}",
            initialDelayString = "${supplier-import.job.apply-initial-delay-ms:45000}")
    public void applyPendingBatches() {
        Duration lease = Duration.ofSeconds(properties.getJob().getApplyLeaseSeconds());

        List<ImportBatch> newlyDecided = importBatchRepository.findByStatusInOrderByIdAsc(
                List.of(ImportBatchStatus.AUTO_APPROVED, ImportBatchStatus.APPROVED));
        for (ImportBatch batch : newlyDecided) {
            processOneBatch(batch.getId(), lease, true);
        }

        List<ImportBatch> stuckApplying = importBatchRepository.findByStatusOrderByIdAsc(ImportBatchStatus.APPLYING);
        for (ImportBatch batch : stuckApplying) {
            processOneBatch(batch.getId(), lease, false);
        }
    }

    private void processOneBatch(Long batchId, Duration lease, boolean isNewlyDecided) {
        Optional<ImportJobClaimService.ClaimHandle> claim =
                importJobClaimService.tryClaim(JOB_TYPE, batchId.toString(), lease);
        if (claim.isEmpty()) {
            log.debug("Batch {} is currently claimed by another replica - skipping this cycle", batchId);
            return;
        }
        try {
            if (isNewlyDecided) {
                importBatchApplyService.applyNewly(batchId);
            } else {
                importBatchApplyService.resumeApplying(batchId);
            }
        } catch (Exception e) {
            log.error("Unhandled error while applying batch {}", batchId, e);
        } finally {
            importJobClaimService.release(claim.get());
        }
    }
}
