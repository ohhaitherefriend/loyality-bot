package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.ImportRuleVersion;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.ImportRuleVersionRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 07 "resume batch" stage-inference heuristic: {@code ruleVersion == null} -&gt;
 * {@code STORED}, known {@code errorMessage} prefixes -&gt; their own stage, and the row-progress
 * fallback when the message is unrecognized.
 */
@DataJpaTest
@Import(ImportBatchResumeServiceTest.TestConfig.class)
class ImportBatchResumeServiceTest {

    private static final String SHOP_A = "shop-a";

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private ImportFileRepository importFileRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private ImportRowRepository importRowRepository;
    @Autowired
    private ImportRuleVersionRepository importRuleVersionRepository;
    @Autowired
    private ImportBatchResumeService importBatchResumeService;
    @Autowired
    private EntityManager entityManager;

    private SupplierSource source;

    @BeforeEach
    void setUp() {
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier").build());
        source = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(supplier).label("main").build());
        flushClear();
    }

    @Test
    void noRuleVersion_resumesToStored() {
        Long batchId = createBatch(ImportBatchStatus.QUARANTINED, null, "AI layout detection failed: timeout");
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.STORED, result.get().getStatus());
        assertEquals(2, result.get().getAttemptNumber());
    }

    @Test
    void normalizingErrorMessage_resumesToNormalizing() {
        ImportRuleVersion ruleVersion = saveRuleVersion();
        Long batchId = createBatch(ImportBatchStatus.FAILED, ruleVersion, "Unexpected normalizing error: boom");
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.NORMALIZING, result.get().getStatus());
    }

    @Test
    void matchingErrorMessage_resumesToMatching() {
        ImportRuleVersion ruleVersion = saveRuleVersion();
        Long batchId = createBatch(ImportBatchStatus.FAILED, ruleVersion, "Unexpected matching error: boom");
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.MATCHING, result.get().getStatus());
    }

    @Test
    void validationErrorMessage_resumesToValidating() {
        ImportRuleVersion ruleVersion = saveRuleVersion();
        Long batchId = createBatch(ImportBatchStatus.FAILED, ruleVersion, "Unexpected validation error: boom");
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.VALIDATING, result.get().getStatus());
    }

    @Test
    void applyGuardFailure_resumesToValidating() {
        ImportRuleVersion ruleVersion = saveRuleVersion();
        Long batchId = createBatch(
                ImportBatchStatus.QUARANTINED, ruleVersion, "Apply guard(s) failed: row-count collapse");
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.VALIDATING, result.get().getStatus());
    }

    @Test
    void applyErrorMessage_resumesToApplying() {
        ImportRuleVersion ruleVersion = saveRuleVersion();
        Long batchId = createBatch(ImportBatchStatus.FAILED, ruleVersion, "Unexpected apply error: boom");
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.APPLYING, result.get().getStatus());
    }

    @Test
    void unrecognizedErrorMessage_fallsBackToRowProgress_allPending_resumesToNormalizing() {
        ImportRuleVersion ruleVersion = saveRuleVersion();
        Long batchId = createBatch(ImportBatchStatus.FAILED, ruleVersion, "Some future stage exploded");
        addRow(batchId, ImportRowStatus.PENDING);
        addRow(batchId, ImportRowStatus.INVALID);
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.NORMALIZING, result.get().getStatus());
    }

    @Test
    void unrecognizedErrorMessage_fallsBackToRowProgress_someUngatedRows_resumesToMatching() {
        ImportRuleVersion ruleVersion = saveRuleVersion();
        Long batchId = createBatch(ImportBatchStatus.FAILED, ruleVersion, "Some future stage exploded");
        addRow(batchId, ImportRowStatus.EXACT_MATCH);
        addRow(batchId, ImportRowStatus.PENDING);
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.MATCHING, result.get().getStatus());
    }

    @Test
    void unrecognizedErrorMessage_fallsBackToRowProgress_gatedRows_resumesToValidating() {
        ImportRuleVersion ruleVersion = saveRuleVersion();
        Long batchId = createBatch(ImportBatchStatus.FAILED, ruleVersion, "Some future stage exploded");
        addRow(batchId, ImportRowStatus.AUTO_APPROVED);
        addRow(batchId, ImportRowStatus.NEEDS_REVIEW);
        flushClear();

        Optional<ImportBatch> result = importBatchResumeService.resume(SHOP_A, batchId);

        assertTrue(result.isPresent());
        assertEquals(ImportBatchStatus.VALIDATING, result.get().getStatus());
    }

    @Test
    void notInResumableStatus_throwsRowReviewException() {
        Long batchId = createBatch(ImportBatchStatus.APPLIED, null, null);
        flushClear();

        assertThrows(RowReviewException.class, () -> importBatchResumeService.resume(SHOP_A, batchId));
    }

    @Test
    void unknownBatchId_returnsEmpty() {
        assertTrue(importBatchResumeService.resume(SHOP_A, 999_999L).isEmpty());
    }

    // ===== helpers =====

    private ImportRuleVersion saveRuleVersion() {
        return importRuleVersionRepository.save(ImportRuleVersion.builder()
                .shopId(SHOP_A).supplierSource(source).version(1)
                .status(RuleVersionStatus.ACTIVE).ruleDefinition("{}").build());
    }

    private Long createBatch(ImportBatchStatus status, ImportRuleVersion ruleVersion, String errorMessage) {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_A).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(1L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_A).supplierSource(source).importFile(importFile).ruleVersion(ruleVersion)
                .status(status).attemptNumber(1).errorMessage(errorMessage).build());
        entityManager.flush();
        return batch.getId();
    }

    private void addRow(Long batchId, ImportRowStatus status) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1).rawData("{}").status(status).build());
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ImportBatchResumeService importBatchResumeService(
                ImportBatchRepository importBatchRepository, ImportRowRepository importRowRepository) {
            return new ImportBatchResumeService(importBatchRepository, importRowRepository);
        }
    }
}
