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
 * Automatically moves every {@code STORED} batch through {@link ImportBatchParsingService} without
 * any manual "configure this file" step (per Prompt 03). Safe to run on every replica: each batch is
 * claimed individually via {@link ImportJobClaimService} (job type {@code import-batch-parse}, job
 * key = batch id) before processing, and {@link ImportBatchParsingService} itself performs an
 * additional atomic STORED -&gt; PARSING transition as a second, DB-level idempotency guard.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportBatchParsingJob {

    private static final String JOB_TYPE = "import-batch-parse";

    private final ImportBatchRepository importBatchRepository;
    private final ImportJobClaimService importJobClaimService;
    private final ImportBatchParsingService importBatchParsingService;
    private final SupplierImportProperties properties;

    @Scheduled(
            fixedDelayString = "${supplier-import.job.parsing-interval-ms:30000}",
            initialDelayString = "${supplier-import.job.parsing-initial-delay-ms:20000}")
    public void parseStoredBatches() {
        List<ImportBatch> storedBatches = importBatchRepository.findByStatusOrderByIdAsc(ImportBatchStatus.STORED);
        if (storedBatches.isEmpty()) {
            return;
        }
        Duration lease = Duration.ofSeconds(properties.getJob().getParsingLeaseSeconds());
        for (ImportBatch batch : storedBatches) {
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
            importBatchParsingService.parseBatch(batchId);
        } catch (Exception e) {
            log.error("Unhandled error while parsing batch {}", batchId, e);
        } finally {
            importJobClaimService.release(claim.get());
        }
    }
}
