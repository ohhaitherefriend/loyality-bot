package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Transactional half of {@link ImportBatchValidationService}, same self-invocation-safe split as
 * every previous stage's {@code *Writer}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchValidationWriter {

    private final ImportBatchRepository importBatchRepository;

    /** @return true if this call won the VALIDATING -&gt; {@code newStatus} transition. */
    @Transactional
    public boolean transition(Long batchId, ImportBatchStatus newStatus, String reason) {
        return importBatchRepository.transitionFromValidating(batchId, newStatus, reason, LocalDateTime.now()) == 1;
    }

    /**
     * Conditional on the batch still being {@code VALIDATING} (see {@code
     * ImportBatchRepository#finalizeFailedFrom}) - a losing side of a concurrent validation race must
     * never overwrite a batch that another caller already transitioned out of {@code VALIDATING}.
     */
    @Transactional
    public void finalizeFailed(Long batchId, String reason) {
        int updated = importBatchRepository.finalizeFailedFrom(
                batchId, ImportBatchStatus.VALIDATING, reason, LocalDateTime.now());
        if (updated == 1) {
            log.error("Batch {} failed: {}", batchId, reason);
        } else {
            log.warn("Batch {} was no longer VALIDATING when validation failed ({}); leaving its actual status untouched",
                    batchId, reason);
        }
    }
}
