package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotently persists exactly one {@link ImportFile} + {@link ImportBatch} pair per
 * {@code (shopId, supplierSourceId, sha256)}.
 *
 * <p>Deliberately NOT {@code @Transactional} itself: the initial lookup, the insert attempt
 * ({@link ImportFileBatchInsertWriter}), and the race-recovery lookup below each need to be their
 * own independent transaction. On PostgreSQL, once a statement inside a transaction errors, the
 * whole backend transaction is aborted and every later statement fails with "current transaction is
 * aborted" until rollback - so the original implementation's "catch the unique-constraint race,
 * then re-query in the same transaction" idiom threw a second, unhandled exception in production
 * despite passing every H2-backed test (H2 is far more lenient here). Splitting the insert into its
 * own bean/transaction means a lost race rolls back atomically and in isolation, and every recovery
 * lookup below always runs in a fresh transaction instead of a poisoned one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportFileBatchWriter {

    private final ImportFileRepository importFileRepository;
    private final ImportBatchRepository importBatchRepository;
    private final ImportFileBatchInsertWriter insertWriter;

    public IngestionResult getOrCreate(
            String shopId,
            SupplierSource supplierSource,
            AttachmentMetadata metadata,
            HashedTempFile hashed,
            String storageKey) {

        var existing = importFileRepository.findByShopIdAndSupplierSourceIdAndSha256(
                shopId, supplierSource.getId(), hashed.sha256Hex());
        if (existing.isPresent()) {
            ImportBatch existingBatch = importBatchRepository.findByImportFileId(existing.get().getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ImportFile " + existing.get().getId() + " has no ImportBatch"));
            log.info("Duplicate attachment for shop {} source {} sha256 {} - returning existing batch {}",
                    shopId, supplierSource.getId(), hashed.sha256Hex(), existingBatch.getId());
            return new IngestionResult(existing.get(), existingBatch, true);
        }

        try {
            IngestionResult result = insertWriter.insertNew(shopId, supplierSource, metadata, hashed, storageKey);
            log.info("Stored new import file {} and batch {} for shop {} source {}",
                    result.importFile().getId(), result.importBatch().getId(), shopId, supplierSource.getId());
            return result;
        } catch (DataIntegrityViolationException e) {
            // insertWriter.insertNew() ran in its own transaction, which has already rolled back
            // atomically and cleanly by the time this catch runs - this lookup starts a brand new
            // transaction, never reusing an aborted one (see class javadoc).
            return reloadAfterRaceOrThrow(shopId, supplierSource.getId(), hashed.sha256Hex(), e);
        }
    }

    private IngestionResult reloadAfterRaceOrThrow(
            String shopId, Long supplierSourceId, String sha256, DataIntegrityViolationException original) {
        ImportFile winningFile = importFileRepository
                .findByShopIdAndSupplierSourceIdAndSha256(shopId, supplierSourceId, sha256)
                .orElseThrow(() -> original);
        ImportBatch winningBatch = importBatchRepository.findByImportFileId(winningFile.getId())
                .orElseThrow(() -> original);
        return new IngestionResult(winningFile, winningBatch, true);
    }
}
