package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Prompt 07 "resume batch" operator action (docs/ARCHITECTURE.md §12): resets a {@code QUARANTINED}/
 * {@code FAILED} batch back to whichever pipeline stage it actually stalled in, so the existing
 * scheduled jobs ({@link ImportBatchParsingJob}/{@link ImportBatchNormalizingJob}/
 * {@link ImportBatchMatchingJob}/{@link ImportBatchValidationJob}/{@link ImportBatchApplyJob}) simply
 * pick it back up on their next tick - no separate "replay" code path to keep in sync with the real
 * pipeline.
 *
 * <p>There is no persisted "which stage failed" field, so the target stage is inferred:
 * <ol>
 *   <li>no {@code ruleVersion} yet -&gt; parsing itself never completed -&gt; back to {@code STORED};</li>
 *   <li>otherwise the {@code errorMessage} prefix set by that stage's {@code *Writer.finalizeFailed}
 *       (or the Prompt 06 {@code BatchApplyGuardEvaluator} quarantine reason) names its own stage
 *       verbatim - matched literally rather than re-derived, since every one of those messages is
 *       already unique to exactly one writer in this codebase;</li>
 *   <li>if {@code errorMessage} is null or unrecognized (future stage, manual DB edit, ...), fall
 *       back to each row's own progress: no row has {@code normalizedData} yet -&gt; {@code NORMALIZING};
 *       some rows normalized but none past matching -&gt; {@code MATCHING}; some rows
 *       {@code AUTO_APPROVED}/{@code APPROVED} but the batch never reached {@code APPLIED} -&gt;
 *       {@code VALIDATING} (safest re-entry: guards re-run, no partial apply work to protect).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchResumeService {

    private static final Set<ImportBatchStatus> RESUMABLE_STATUSES =
            Set.of(ImportBatchStatus.QUARANTINED, ImportBatchStatus.FAILED);

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;

    /** @return empty if the batch does not exist for this shop. */
    @Transactional
    public Optional<ImportBatch> resume(String shopId, Long batchId) {
        Optional<ImportBatch> found = importBatchRepository.findDetailByShopIdAndId(shopId, batchId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ImportBatch batch = found.get();
        if (!RESUMABLE_STATUSES.contains(batch.getStatus())) {
            throw new RowReviewException(
                    "Batch " + batchId + " is not resumable in its current status " + batch.getStatus());
        }

        ImportBatchStatus previousStatus = batch.getStatus();
        ImportBatchStatus target = determineResumeTarget(batch);
        int updated = importBatchRepository.resetForResume(batchId, target);
        if (updated != 1) {
            // Lost a race with another resume/automatic transition between the read above and now.
            throw new RowVersionConflictException(null);
        }
        // resetForResume() is a bulk @Modifying query, which bypasses the persistence context - the
        // managed `batch` instance above is already stale, same reasoning as every stage writer's
        // finalizeSuccess() (e.g. ImportBatchNormalizeWriter) - keep it in sync explicitly rather than
        // re-querying, which would just return this same stale managed instance from the session cache.
        batch.setStatus(target);
        batch.setErrorMessage(null);
        batch.setFinishedAt(null);
        batch.setAttemptNumber(batch.getAttemptNumber() + 1);
        log.info("Batch {} (shop {}) resumed: {} -> {}", batchId, shopId, previousStatus, target);
        return Optional.of(batch);
    }

    private ImportBatchStatus determineResumeTarget(ImportBatch batch) {
        if (batch.getRuleVersion() == null) {
            return ImportBatchStatus.STORED;
        }

        String message = batch.getErrorMessage();
        if (message != null) {
            if (message.startsWith("Unexpected normalizing error")) {
                return ImportBatchStatus.NORMALIZING;
            }
            if (message.startsWith("Unexpected matching error")) {
                return ImportBatchStatus.MATCHING;
            }
            if (message.startsWith("Unexpected validation error") || message.startsWith("Apply guard(s) failed")) {
                return ImportBatchStatus.VALIDATING;
            }
            if (message.startsWith("Unexpected apply error")) {
                return ImportBatchStatus.APPLYING;
            }
        }
        return determineResumeTargetFromRowProgress(batch.getId());
    }

    private ImportBatchStatus determineResumeTargetFromRowProgress(Long batchId) {
        // PENDING/INVALID are exactly the two statuses a row can have straight out of parsing, before
        // the normalizer ever looks at it - anything else means normalizing produced at least one
        // outcome for this batch already.
        boolean anyNormalized = !importRowRepository
                .findByImportBatchIdAndStatusIn(batchId, allStatusesExcept(ImportRowStatus.PENDING, ImportRowStatus.INVALID))
                .isEmpty();
        if (!anyNormalized) {
            return ImportBatchStatus.NORMALIZING;
        }
        boolean anyGated = !importRowRepository
                .findByImportBatchIdAndStatusIn(batchId, List.of(
                        ImportRowStatus.AUTO_APPROVED, ImportRowStatus.NEEDS_REVIEW,
                        ImportRowStatus.APPROVED, ImportRowStatus.APPLIED))
                .isEmpty();
        return anyGated ? ImportBatchStatus.VALIDATING : ImportBatchStatus.MATCHING;
    }

    private List<ImportRowStatus> allStatusesExcept(ImportRowStatus... excluded) {
        Set<ImportRowStatus> exclude = Set.of(excluded);
        return Arrays.stream(ImportRowStatus.values())
                .filter(status -> !exclude.contains(status))
                .toList();
    }
}
