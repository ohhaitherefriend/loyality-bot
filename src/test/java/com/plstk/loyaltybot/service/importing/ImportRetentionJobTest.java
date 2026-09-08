package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 10 (docs/DECISIONS.md ADR-015): {@link ImportRetentionJob} must only ever touch terminal
 * batches past the retention window, must be a safe no-op when disabled/nothing eligible, and must
 * mark {@code storage_deleted_at} so a repeat sweep never tries to delete an already-gone blob.
 */
@DataJpaTest
@Import(ImportRetentionJobTest.TestServicesConfig.class)
class ImportRetentionJobTest {

    private static final String SHOP_ID = "shop-retention";

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private ImportFileRepository importFileRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private ImportFileStorage importFileStorage;
    @Autowired
    private SupplierImportProperties properties;
    @Autowired
    private EntityManager entityManager;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideStorageBasePath(DynamicPropertyRegistry registry) {
        registry.add("supplier-import.storage.base-path", tempDir::toString);
    }

    private SupplierSource source;

    @BeforeEach
    void setUp() {
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Retention Supplier").build());
        source = supplierSourceRepository.save(SupplierSource.builder().shopId(SHOP_ID).supplier(supplier).label("main").build());
        properties.getStorage().getRetention().setEnabled(true);
        properties.getStorage().getRetention().setFileRetentionDays(30);
        properties.getStorage().getRetention().setMaxDeletionsPerSweep(500);
    }

    private ImportRetentionJob newJob() {
        return new ImportRetentionJob(importBatchRepository, importFileRepository, importFileStorage, properties,
                new SupplierImportMetrics(new SimpleMeterRegistry()));
    }

    private ImportFile storeFile(String content) throws IOException {
        Path tempFile = Files.createTempFile("retention", ".xlsx");
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
        String sha256 = java.util.UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
        String key = importFileStorage.store(SHOP_ID, sha256, "price.xlsx", tempFile);
        Files.deleteIfExists(tempFile);
        return importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_ID)
                .supplierSource(source)
                .sha256(sha256)
                .sizeBytes((long) content.length())
                .mediaType("application/octet-stream")
                .originalFilename("price.xlsx")
                .storageKey(key)
                .receivedAt(LocalDateTime.now())
                .build());
    }

    private ImportBatch saveBatch(ImportFile file, ImportBatchStatus status, LocalDateTime finishedAt) {
        return importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_ID)
                .supplierSource(source)
                .importFile(file)
                .status(status)
                .attemptNumber(1)
                .finishedAt(finishedAt)
                .build());
    }

    @Test
    void sweep_disabled_deletesNothing() throws IOException {
        ImportFile file = storeFile("old applied batch");
        saveBatch(file, ImportBatchStatus.APPLIED, LocalDateTime.now().minusDays(400));
        entityManager.flush();
        properties.getStorage().getRetention().setEnabled(false);

        newJob().sweep();

        ImportFile reloaded = importFileRepository.findById(file.getId()).orElseThrow();
        assertNull(reloaded.getStorageDeletedAt());
        assertTrue(importFileStorage.exists(file.getStorageKey()));
    }

    @Test
    void sweep_oldTerminalBatch_deletesBlobAndMarksFile() throws IOException {
        ImportFile file = storeFile("old applied batch");
        saveBatch(file, ImportBatchStatus.APPLIED, LocalDateTime.now().minusDays(400));
        entityManager.flush();

        newJob().sweep();

        ImportFile reloaded = importFileRepository.findById(file.getId()).orElseThrow();
        assertNotNull(reloaded.getStorageDeletedAt());
        assertFalse(importFileStorage.exists(file.getStorageKey()));
    }

    @Test
    void sweep_recentTerminalBatch_isNotDeleted() throws IOException {
        ImportFile file = storeFile("recent applied batch");
        saveBatch(file, ImportBatchStatus.APPLIED, LocalDateTime.now().minusDays(1));
        entityManager.flush();

        newJob().sweep();

        ImportFile reloaded = importFileRepository.findById(file.getId()).orElseThrow();
        assertNull(reloaded.getStorageDeletedAt());
        assertTrue(importFileStorage.exists(file.getStorageKey()));
    }

    @Test
    void sweep_inFlightBatch_isNeverTouchedRegardlessOfAge() throws IOException {
        ImportFile file = storeFile("still processing");
        // In-flight batches have no finishedAt yet - simulate an old startedAt-only batch.
        saveBatch(file, ImportBatchStatus.MATCHING, null);
        entityManager.flush();

        newJob().sweep();

        ImportFile reloaded = importFileRepository.findById(file.getId()).orElseThrow();
        assertNull(reloaded.getStorageDeletedAt());
        assertTrue(importFileStorage.exists(file.getStorageKey()));
    }

    @Test
    void sweep_alreadyDeletedFile_isSkippedOnRepeatRun() throws IOException {
        ImportFile file = storeFile("already purged");
        saveBatch(file, ImportBatchStatus.QUARANTINED, LocalDateTime.now().minusDays(400));
        entityManager.flush();

        ImportRetentionJob job = newJob();
        job.sweep();
        // Second run must not attempt to delete an already-deleted key (would be a no-op anyway,
        // but the storage_deleted_at IS NULL predicate should exclude it from the query entirely).
        job.sweep();

        ImportFile reloaded = importFileRepository.findById(file.getId()).orElseThrow();
        assertNotNull(reloaded.getStorageDeletedAt());
    }

    @TestConfiguration
    static class TestServicesConfig {
        @Bean
        ImportFileStorage importFileStorage(SupplierImportProperties properties) {
            return new LocalImportFileStorage(properties);
        }
    }
}
