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
 * Automatically moves every {@code NORMALIZING} batch through
 * {@link ImportBatchNormalizingService} (Prompt 04) without any manual step, exactly the same
 * claim/lease/DB-guard pattern as {@link ImportBatchParsingJob}: each batch is claimed individually
 * via {@link ImportJobClaimService} (job type {@code import-batch-normalize}, job key = batch id)
 * before processing, and {@link ImportBatchNormalizingService} itself performs an additional atomic
 * NORMALIZING -&gt; MATCHING transition as a second, DB-level idempotency guard.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportBatchNormalizingJob {

    private static final String JOB_TYPE = "import-batch-normalize";

    private final ImportBatchRepository importBatchRepository;
    private final ImportJobClaimService importJobClaimService;
    private final ImportBatchNormalizingService importBatchNormalizingService;
    private final SupplierImportProperties properties;

    @Scheduled(
            fixedDelayString = "${supplier-import.job.normalizing-interval-ms:30000}",
            initialDelayString = "${supplier-import.job.normalizing-initial-delay-ms:25000}")
    public void normalizeStoredBatches() {
        List<ImportBatch> normalizingBatches = importBatchRepository.findByStatusOrderByIdAsc(ImportBatchStatus.NORMALIZING);
        if (normalizingBatches.isEmpty()) {
            return;
        }
        Duration lease = Duration.ofSeconds(properties.getJob().getNormalizingLeaseSeconds());
        for (ImportBatch batch : normalizingBatches) {
            processOneBatch(batch.getId(), lease);
        }
    }

    private void processOneBatch(Long batchId, Duration lease) {
        Optional<ImportJobClaimService.ClaimHandle> claim =
                importJobClaimService.tryClaim(JOB_TYPE, batchId.toString(), lease);
        if (claim.isEmpty()) {
            log.debug("Batch {} is currently claimed by another replica - skipping this cycle", batchId);
            return;
        }
        try {
            importBatchNormalizingService.normalizeBatch(batchId);
        } catch (Exception e) {
            log.error("Unhandled error while normalizing batch {}", batchId, e);
        } finally {
            importJobClaimService.release(claim.get());
        }
    }
}
