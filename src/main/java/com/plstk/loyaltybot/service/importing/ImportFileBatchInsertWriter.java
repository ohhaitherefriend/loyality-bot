package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The insert-a-brand-new-file-and-batch half of {@link ImportFileBatchWriter#getOrCreate}, kept as
 * a separate Spring bean/transaction so a lost unique-constraint race here rolls back atomically
 * and in isolation (both the file and batch insert undone together), instead of poisoning a shared
 * transaction the caller would then try to keep issuing recovery statements against on PostgreSQL
 * (see {@link ImportFileBatchWriter} class javadoc).
 */
@Service
@RequiredArgsConstructor
class ImportFileBatchInsertWriter {

    private final ImportFileRepository importFileRepository;
    private final ImportBatchRepository importBatchRepository;

    @Transactional
    public IngestionResult insertNew(
            String shopId,
            SupplierSource supplierSource,
            AttachmentMetadata metadata,
            HashedTempFile hashed,
            String storageKey) {

        ImportFile importFile = ImportFile.builder()
                .shopId(shopId)
                .supplierSource(supplierSource)
                .sha256(hashed.sha256Hex())
                .sizeBytes(hashed.sizeBytes())
                .mediaType(metadata.mediaType())
                .originalFilename(metadata.originalFilename())
                .storageKey(storageKey)
                .sourceIdentity(metadata.sourceIdentity())
                .receivedAt(LocalDateTime.now())
                .build();
        importFile = importFileRepository.save(importFile);
        importFileRepository.flush();

        ImportBatch importBatch = ImportBatch.builder()
                .shopId(shopId)
                .supplierSource(supplierSource)
                .importFile(importFile)
                .status(ImportBatchStatus.STORED)
                .attemptNumber(1)
                .build();
        importBatch = importBatchRepository.save(importBatch);
        importBatchRepository.flush();

        return new IngestionResult(importFile, importBatch, false);
    }
}
