package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Stage 10 retention sweep (docs/DECISIONS.md ADR-015): deletes the {@code ImportFileStorage} blob
 * for terminal batches (APPLIED/QUARANTINED/FAILED) once they are older than
 * {@code supplier-import.storage.retention.file-retention-days}. Disabled by default - an operator
 * must explicitly opt in ({@code supplier-import.storage.retention.enabled=true}), since deleting
 * historical supplier price files is an irreversible, business-impacting decision this codebase
 * must never make unilaterally.
 *
 * <p>The {@code ImportFile} metadata row (and every {@code ImportBatch}/{@code ImportRow}/
 * {@code MatchDecision} audit trail derived from it) is never deleted - only the underlying blob -
 * so "who imported what, when, and what happened to it" remains answerable forever even after the
 * raw spreadsheet bytes are gone. This mirrors the {@code storage_deleted_at} marker rather than a
 * hard delete precisely so a repeat sweep (this replica or another) is a safe no-op for a file
 * already processed.</p>
 *
 * <p>Safe under multiple replicas without a claim/lease (unlike the pipeline stage jobs): deleting
 * an already-deleted {@link ImportFileStorage} key is a no-op by contract, and the
 * {@code storage_deleted_at IS NULL} predicate in {@code findEligibleForFileRetention} means a
 * second replica racing the same row either finds it already marked (skips it) or marks it again
 * with an equivalent timestamp - never double-charges or corrupts anything.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportRetentionJob {

    private static final List<ImportBatchStatus> TERMINAL_STATUSES =
            List.of(ImportBatchStatus.APPLIED, ImportBatchStatus.QUARANTINED, ImportBatchStatus.FAILED);

    private final ImportBatchRepository importBatchRepository;
    private final ImportFileRepository importFileRepository;
    private final ImportFileStorage importFileStorage;
    private final SupplierImportProperties properties;
    private final SupplierImportMetrics metrics;

    @Scheduled(
            fixedDelayString = "${supplier-import.storage.retention.sweep-interval-ms:86400000}",
            initialDelayString = "${supplier-import.storage.retention.sweep-initial-delay-ms:300000}")
    public void sweep() {
        SupplierImportProperties.Retention retention = properties.getStorage().getRetention();
        if (!retention.isEnabled()) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retention.getFileRetentionDays());
        List<ImportBatch> eligible = importBatchRepository.findEligibleForFileRetention(
                TERMINAL_STATUSES, cutoff, PageRequest.of(0, retention.getMaxDeletionsPerSweep()));

        if (eligible.isEmpty()) {
            log.debug("Retention sweep: no ImportFile blobs older than {} days to delete", retention.getFileRetentionDays());
            return;
        }

        log.info("Retention sweep: deleting {} ImportFile blob(s) older than {} days (cutoff={})",
                eligible.size(), retention.getFileRetentionDays(), cutoff);

        int deleted = 0;
        int failed = 0;
        for (ImportBatch batch : eligible) {
            if (deleteBlob(batch.getImportFile())) {
                deleted++;
            } else {
                failed++;
            }
        }
        log.info("Retention sweep finished: {} deleted, {} failed", deleted, failed);
    }

    @Transactional
    boolean deleteBlob(ImportFile file) {
        try {
            importFileStorage.delete(file.getStorageKey());
            file.setStorageDeletedAt(LocalDateTime.now());
            importFileRepository.save(file);
            metrics.retentionDeletion("deleted");
            return true;
        } catch (IOException | RuntimeException e) {
            log.error("Retention sweep: failed to delete ImportFile {} (storageKey={})",
                    file.getId(), file.getStorageKey(), e);
            metrics.retentionDeletion("failed");
            return false;
        }
    }
}
