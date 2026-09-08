package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierOfferRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Prompt 06 {@code VALIDATING -&gt;} decision ({@link ImportBatchValidationService}):
 * every batch-level apply guard from {@link BatchApplyGuardEvaluator}, the shadowMode/autoApply
 * gating policy, and the VALIDATING transition's idempotency.
 */
@DataJpaTest
@Import(ImportBatchValidationServiceTest.TestConfig.class)
class ImportBatchValidationServiceTest {

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
    private ProductRepository productRepository;
    @Autowired
    private SupplierOfferRepository supplierOfferRepository;
    @Autowired
    private ImportBatchValidationService importBatchValidationService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    private Supplier supplier;

    @BeforeEach
    void setUp() {
        supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Test Supplier").build());
    }

    @Test
    void safeFullBatch_autoApplySource_becomesAutoApproved() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", true, false, null);
        Long batchId = createBatch(source, 1, 1);
        addAutoApprovedRow(batchId, normalized("SKU-1", null, "100.00"));
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();

        assertEquals(ImportBatchStatus.AUTO_APPROVED, reloadBatch(batchId).getStatus());
    }

    @Test
    void shadowModeSource_safeBatch_becomesNeedsAttention_notApplied() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", true, true, null);
        Long batchId = createBatch(source, 1, 1);
        addAutoApprovedRow(batchId, normalized("SKU-1", null, "100.00"));
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.NEEDS_ATTENTION, batch.getStatus());
        assertTrue(batch.getErrorMessage().contains("shadow mode"));
    }

    @Test
    void autoApplyDisabled_safeBatch_becomesNeedsAttention() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", false, false, null);
        Long batchId = createBatch(source, 1, 1);
        addAutoApprovedRow(batchId, normalized("SKU-1", null, "100.00"));
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();

        assertEquals(ImportBatchStatus.NEEDS_ATTENTION, reloadBatch(batchId).getStatus());
    }

    @Test
    void fullSnapshot_zeroAppliableRows_isQuarantined_neverAllowedToWipeScope() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", true, false, null);
        Long batchId = createBatch(source, 1, 0);
        // Row exists but stayed NEEDS_REVIEW - nothing appliable.
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1).rawData("{}")
                .normalizedData(toJson(normalized("SKU-1", null, "100.00")))
                .status(ImportRowStatus.NEEDS_REVIEW).build());
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();

        ImportBatch reloaded = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.QUARANTINED, reloaded.getStatus());
        assertTrue(reloaded.getErrorMessage().contains("zero appliable rows"));
    }

    @Test
    void deltaSnapshot_zeroAppliableRows_isNotQuarantined_deltaNeverWipesAnything() {
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", true, false, null);
        Long batchId = createBatch(source, 1, 0);
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();

        assertEquals(ImportBatchStatus.AUTO_APPROVED, reloadBatch(batchId).getStatus());
    }

    @Test
    void rowCountCollapse_vsPreviousAppliedBatch_isQuarantined() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", true, false, null);
        // Previous successfully applied batch had 100 valid rows, all of which were actually applied.
        Long previousBatchId = createBatch(source, 100, 100);
        markApplied(previousBatchId, 100);

        // New batch has only 10 appliable rows - an 90% collapse, well below the 50% default floor.
        Long batchId = createBatch(source, 10, 10);
        for (int i = 0; i < 10; i++) {
            addAutoApprovedRow(batchId, normalized("SKU-" + i, null, "100.00"));
        }
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();

        ImportBatch reloaded = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.QUARANTINED, reloaded.getStatus());
        assertTrue(reloaded.getErrorMessage().contains("Row-count collapse"));
    }

    @Test
    void duplicateIdentifierExplosion_isQuarantined() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", true, false, null);
        Long batchId = createBatch(source, 10, 10);
        // 8 out of 10 rows share the same externalSku - way above the 20% default duplicate ratio.
        for (int i = 0; i < 8; i++) {
            addAutoApprovedRow(batchId, normalized("DUPLICATED-SKU", null, "100.00"));
        }
        addAutoApprovedRow(batchId, normalized("UNIQUE-1", null, "100.00"));
        addAutoApprovedRow(batchId, normalized("UNIQUE-2", null, "100.00"));
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();

        ImportBatch reloaded = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.QUARANTINED, reloaded.getStatus());
        assertTrue(reloaded.getErrorMessage().contains("Duplicate identifier"));
    }

    @Test
    void anomalousPriceDelta_isQuarantined() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", true, false, null);
        Product product = productRepository.save(Product.builder().shopId(SHOP_A).name("Existing").currency("RUB").build());
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).snapshotScope(source.getSnapshotScope()).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).active(true).build());
        flushClear();

        Long batchId = createBatch(source, 1, 1);
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        ImportRow row = importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1).rawData("{}")
                // Price jumped from 100.00 to 1000.00 - a 900% delta, way above the 50% warn ratio.
                .normalizedData(toJson(normalized("SKU-1", null, "1000.00")))
                .status(ImportRowStatus.AUTO_APPROVED).build());
        row.setMatchedProduct(product);
        importRowRepository.save(row);
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();

        ImportBatch reloaded = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.QUARANTINED, reloaded.getStatus());
        assertTrue(reloaded.getErrorMessage().contains("Anomalous price delta"));
    }

    @Test
    void validation_isIdempotent_secondCallIsNoOp() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", true, false, null);
        Long batchId = createBatch(source, 1, 1);
        addAutoApprovedRow(batchId, normalized("SKU-1", null, "100.00"));
        flushClear();

        importBatchValidationService.validateBatch(batchId);
        flushClear();
        assertEquals(ImportBatchStatus.AUTO_APPROVED, reloadBatch(batchId).getStatus());

        // Re-running on an already-decided batch must be a complete no-op.
        importBatchValidationService.validateBatch(batchId);
        flushClear();
        assertEquals(ImportBatchStatus.AUTO_APPROVED, reloadBatch(batchId).getStatus());
    }

    // ===== helpers =====

    private SupplierSource saveSource(
            SnapshotMode mode, String scope, boolean autoApply, boolean shadowMode, BigDecimal commissionOverride) {
        return supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(supplier).label("main-" + System.nanoTime())
                .snapshotMode(mode).snapshotScope(scope)
                .autoApply(autoApply).shadowMode(shadowMode)
                .commissionPercentOverride(commissionOverride)
                .build());
    }

    private Long createBatch(SupplierSource source, int totalRows, int validRows) {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_A).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(10L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_A).supplierSource(source).importFile(importFile)
                .status(ImportBatchStatus.VALIDATING).attemptNumber(1)
                .totalRows(totalRows).validRows(validRows).build());
        entityManager.flush();
        return batch.getId();
    }

    /**
     * @param appliableCount the previous batch's own appliable-row count (offersAdded +
     *                        offersUpdated), which is what the row-count-collapse guard now compares
     *                        against instead of the parse-stage validRows - see
     *                        BatchApplyGuardEvaluator#checkRowCountCollapse.
     */
    private void markApplied(Long batchId, int appliableCount) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        batch.setStatus(ImportBatchStatus.APPLIED);
        batch.setFinishedAt(LocalDateTime.now());
        batch.setOffersAddedCount(appliableCount);
        batch.setOffersUpdatedCount(0);
        importBatchRepository.save(batch);
        entityManager.flush();
    }

    private void addAutoApprovedRow(Long batchId, NormalizedRowData normalized) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1).rawData("{}")
                .normalizedData(toJson(normalized)).status(ImportRowStatus.AUTO_APPROVED).build());
    }

    private NormalizedRowData normalized(String externalSku, String barcode, String price) {
        return new NormalizedRowData(
                "Brand", "line", null, new BigDecimal("100"), "ml", null, null, false, false,
                externalSku, barcode, new BigDecimal(price), null, "brand line", "fp-" + externalSku);
    }

    private ImportBatch reloadBatch(Long batchId) {
        return importBatchRepository.findById(batchId).orElseThrow();
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        BatchApplyGuardEvaluator batchApplyGuardEvaluator(
                ImportBatchRepository importBatchRepository,
                SupplierOfferRepository supplierOfferRepository,
                SupplierImportProperties properties,
                ObjectMapper objectMapper) {
            return new BatchApplyGuardEvaluator(importBatchRepository, supplierOfferRepository, properties, objectMapper);
        }

        @Bean
        ImportBatchValidationWriter importBatchValidationWriter(ImportBatchRepository importBatchRepository) {
            return new ImportBatchValidationWriter(importBatchRepository);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SupplierImportMetrics supplierImportMetrics(MeterRegistry meterRegistry) {
            return new SupplierImportMetrics(meterRegistry);
        }

        @Bean
        ImportBatchValidationService importBatchValidationService(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                BatchApplyGuardEvaluator guardEvaluator,
                ImportBatchValidationWriter writer,
                SupplierImportMetrics metrics) {
            return new ImportBatchValidationService(importBatchRepository, importRowRepository, guardEvaluator, writer, metrics);
        }
    }
}
