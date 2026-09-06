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
 * Automatically moves every {@code MATCHING} batch through {@link ImportBatchMatchingService}
 * (Prompt 05) without any manual step, exactly the same claim/lease/DB-guard pattern as
 * {@link ImportBatchNormalizingJob}: each batch is claimed individually via
 * {@link ImportJobClaimService} (job type {@code import-batch-match}, job key = batch id) before
 * processing, and {@link ImportBatchMatchingService} itself performs an additional atomic
 * MATCHING -&gt; VALIDATING transition as a second, DB-level idempotency guard.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportBatchMatchingJob {

    private static final String JOB_TYPE = "import-batch-match";

    private final ImportBatchRepository importBatchRepository;
    private final ImportJobClaimService importJobClaimService;
    private final ImportBatchMatchingService importBatchMatchingService;
    private final SupplierImportProperties properties;

    @Scheduled(
            fixedDelayString = "${supplier-import.job.matching-interval-ms:30000}",
            initialDelayString = "${supplier-import.job.matching-initial-delay-ms:35000}")
    public void matchNormalizedBatches() {
        List<ImportBatch> matchingBatches = importBatchRepository.findByStatusOrderByIdAsc(ImportBatchStatus.MATCHING);
        if (matchingBatches.isEmpty()) {
            return;
        }
        Duration lease = Duration.ofSeconds(properties.getJob().getMatchingLeaseSeconds());
        for (ImportBatch batch : matchingBatches) {
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
            importBatchMatchingService.matchBatch(batchId);
        } catch (Exception e) {
            log.error("Unhandled error while matching batch {}", batchId, e);
        } finally {
            importJobClaimService.release(claim.get());
        }
    }
}
