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
 * Automatically moves every {@code VALIDATING} batch through {@link ImportBatchValidationService}
 * (Prompt 06) without any manual step, same claim/lease/DB-guard pattern as
 * {@link ImportBatchMatchingJob}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportBatchValidationJob {

    private static final String JOB_TYPE = "import-batch-validate";

    private final ImportBatchRepository importBatchRepository;
    private final ImportJobClaimService importJobClaimService;
    private final ImportBatchValidationService importBatchValidationService;
    private final SupplierImportProperties properties;

    @Scheduled(
            fixedDelayString = "${supplier-import.job.validation-interval-ms:30000}",
            initialDelayString = "${supplier-import.job.validation-initial-delay-ms:40000}")
    public void validateGatedBatches() {
        List<ImportBatch> validatingBatches = importBatchRepository.findByStatusOrderByIdAsc(ImportBatchStatus.VALIDATING);
        if (validatingBatches.isEmpty()) {
            return;
        }
        Duration lease = Duration.ofSeconds(properties.getJob().getValidationLeaseSeconds());
        for (ImportBatch batch : validatingBatches) {
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
            importBatchValidationService.validateBatch(batchId);
        } catch (Exception e) {
            log.error("Unhandled error while validating batch {}", batchId, e);
        } finally {
            importJobClaimService.release(claim.get());
        }
    }
}
