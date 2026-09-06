package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.ImportRuleVersion;
import com.plstk.loyaltybot.entity.importing.RuleVersionSource;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.ImportRuleVersionRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import com.plstk.loyaltybot.service.importing.fixtures.SupplierWorkbookFixtures;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Workbook;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of {@link ImportBatchParsingService}: known-layout reuse without AI, new
 * valid layout publishing a rule version, and every quarantine path (invented column, malformed
 * JSON, empty JSON, AI transport failure, low-confidence preview, collapsed final valid-row ratio).
 */
@DataJpaTest
@Import(ImportBatchParsingServiceTest.TestConfig.class)
class ImportBatchParsingServiceTest {

    private static final String SHOP_ID = "shop-a";

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private ImportFileRepository importFileRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private ImportRuleVersionRepository importRuleVersionRepository;
    @Autowired
    private ImportRowRepository importRowRepository;
    @Autowired
    private ImportFileStorage importFileStorage;
    @Autowired
    private SpreadsheetParser spreadsheetParser;
    @Autowired
    private LayoutRuleValidator layoutRuleValidator;
    @Autowired
    private FakeAiSpreadsheetLayoutDetector fakeAi;
    @Autowired
    private ImportBatchParsingService importBatchParsingService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideStorageBasePath(DynamicPropertyRegistry registry) {
        registry.add("supplier-import.storage.base-path", tempDir::toString);
    }

    private SupplierSource supplierSource;

    @BeforeEach
    void setUp() {
        fakeAi.reset();
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Test Supplier").build());
        supplierSource = supplierSourceRepository.save(
                SupplierSource.builder().shopId(SHOP_ID).supplier(supplier).label("main").build());
        entityManager.flush();
    }

    @Test
    void knownLayout_reusesPublishedRule_withoutCallingAi() throws Exception {
        LayoutRuleDefinition rule = parsedStandardRule();
        Workbook standardWorkbook = SupplierWorkbookFixtures.standardLayoutWorkbook();
        rule.setExpectedHeaderSignature(spreadsheetParser.computeHeaderSignature(standardWorkbook, rule));
        ImportRuleVersion existingRule = saveRuleVersion(rule, RuleVersionStatus.ACTIVE, RuleVersionSource.MANUAL, 1);

        Long batchId = createStoredBatch(standardWorkbook);

        importBatchParsingService.parseBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(0, fakeAi.callCount(), "known layout must not call AI at all");
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        assertEquals(ImportBatchStatus.NORMALIZING, batch.getStatus());
        assertEquals(existingRule.getId(), batch.getRuleVersion().getId());
        assertEquals(1, importRuleVersionRepository.findByShopIdAndSupplierSourceId(SHOP_ID, supplierSource.getId()).size(),
                "reuse must not create a second rule version");

        List<ImportRow> rows = importRowRepository.findByImportBatchId(batchId);
        assertEquals(6, rows.size());
        assertEquals(1, rows.stream().filter(r -> r.getStatus() == ImportRowStatus.INVALID).count());
        assertEquals(5, rows.stream().filter(r -> r.getStatus() == ImportRowStatus.PENDING).count());
    }

    @Test
    void unknownLayout_aiSucceeds_previewPasses_publishesNewActiveRuleVersion() {
        fakeAi.enqueue(LayoutDetectionResponse.success(
                SupplierWorkbookFixtures.standardLayoutRuleJson(), "deepseek", "deepseek-chat", 120L));

        Long batchId = createStoredBatch(SupplierWorkbookFixtures.standardLayoutWorkbook());

        importBatchParsingService.parseBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(1, fakeAi.callCount());
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        assertEquals(ImportBatchStatus.NORMALIZING, batch.getStatus());

        List<ImportRuleVersion> versions =
                importRuleVersionRepository.findByShopIdAndSupplierSourceId(SHOP_ID, supplierSource.getId());
        assertEquals(1, versions.size());
        assertEquals(RuleVersionStatus.ACTIVE, versions.get(0).getStatus());
        assertEquals(RuleVersionSource.AI_GENERATED, versions.get(0).getSource());
        assertEquals(batch.getRuleVersion().getId(), versions.get(0).getId());
    }

    @Test
    void aiRespondsWithInventedColumn_quarantinesBatch_noRuleCreated() {
        String invented = """
                {
                  "sheetSelectors": ["Косметика и уход"],
                  "headerRow": 6,
                  "firstDataRow": 7,
                  "columns": {
                    "rawName": {"headerAliases": ["Номенклатура"], "type": "STRING"},
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL"},
                    "externalSku": {"headerAliases": ["Артикул"], "type": "STRING"},
                    "supplierSecretMargin": {"headerAliases": ["Маржа"], "type": "DECIMAL"}
                  }
                }
                """;
        fakeAi.enqueue(LayoutDetectionResponse.success(invented, "deepseek", "deepseek-chat", 100L));

        Long batchId = createStoredBatch(SupplierWorkbookFixtures.standardLayoutWorkbook());
        importBatchParsingService.parseBatch(batchId);

        assertQuarantinedWithReason(batchId, "validation");
        assertTrue(importRuleVersionRepository.findByShopIdAndSupplierSourceId(SHOP_ID, supplierSource.getId()).isEmpty());
    }

    @Test
    void aiRespondsWithMalformedJson_quarantinesBatch() {
        fakeAi.enqueue(LayoutDetectionResponse.success("not { valid ] json :::", "deepseek", "deepseek-chat", 90L));

        Long batchId = createStoredBatch(SupplierWorkbookFixtures.standardLayoutWorkbook());
        importBatchParsingService.parseBatch(batchId);

        assertQuarantinedWithReason(batchId, "validation");
    }

    @Test
    void aiRespondsWithEmptyContent_quarantinesBatch() {
        fakeAi.enqueue(LayoutDetectionResponse.success("", "deepseek", "deepseek-chat", 50L));

        Long batchId = createStoredBatch(SupplierWorkbookFixtures.standardLayoutWorkbook());
        importBatchParsingService.parseBatch(batchId);

        assertQuarantinedWithReason(batchId, "validation");
    }

    @Test
    void aiTransportFailsAfterRetries_quarantinesBatch() {
        fakeAi.enqueue(LayoutDetectionResponse.failure(
                "DeepSeek call failed after 3 attempt(s): Read timed out", true, "deepseek"));

        Long batchId = createStoredBatch(SupplierWorkbookFixtures.standardLayoutWorkbook());
        importBatchParsingService.parseBatch(batchId);

        assertQuarantinedWithReason(batchId, "AI layout detection failed");
    }

    @Test
    void aiRule_withUnresolvableHeaders_failsPreview_quarantinesBatch_noRuleCreated() {
        String unresolvable = """
                {
                  "sheetSelectors": ["Косметика и уход"],
                  "headerRow": 6,
                  "firstDataRow": 7,
                  "columns": {
                    "rawName": {"headerAliases": ["ЭтогоЗаголовкаНеСуществует"], "type": "STRING"},
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL"},
                    "externalSku": {"headerAliases": ["Артикул"], "type": "STRING"}
                  }
                }
                """;
        fakeAi.enqueue(LayoutDetectionResponse.success(unresolvable, "deepseek", "deepseek-chat", 100L));

        Long batchId = createStoredBatch(SupplierWorkbookFixtures.standardLayoutWorkbook());
        importBatchParsingService.parseBatch(batchId);

        assertQuarantinedWithReason(batchId, "preview");
        assertTrue(importRuleVersionRepository.findByShopIdAndSupplierSourceId(SHOP_ID, supplierSource.getId()).isEmpty());
    }

    @Test
    void priceColumnMissing_quarantinesBatch_evenWhenReusingKnownRule() throws Exception {
        LayoutRuleDefinition rule = parsedStandardRule();
        Workbook standardWorkbook = SupplierWorkbookFixtures.standardLayoutWorkbook();
        rule.setExpectedHeaderSignature(spreadsheetParser.computeHeaderSignature(standardWorkbook, rule));
        saveRuleVersion(rule, RuleVersionStatus.ACTIVE, RuleVersionSource.MANUAL, 1);

        // Same headers (so the known-layout signature still matches), but the price cell values
        // are missing in the actual file - this must be caught by the *final* row-ratio guard,
        // independent of the AI/preview path.
        Long batchId = createStoredBatch(SupplierWorkbookFixtures.standardLayoutWithMissingPricesWorkbook());
        importBatchParsingService.parseBatch(batchId);

        assertEquals(0, fakeAi.callCount());
        assertQuarantinedWithReason(batchId, "Valid row ratio");
    }

    @Test
    void reparsingAnAlreadyProcessedBatch_isANoOp() {
        fakeAi.enqueue(LayoutDetectionResponse.success(
                SupplierWorkbookFixtures.standardLayoutRuleJson(), "deepseek", "deepseek-chat", 100L));
        Long batchId = createStoredBatch(SupplierWorkbookFixtures.standardLayoutWorkbook());

        importBatchParsingService.parseBatch(batchId);
        entityManager.flush();
        int rowsAfterFirstParse = importRowRepository.findByImportBatchId(batchId).size();

        importBatchParsingService.parseBatch(batchId);
        entityManager.flush();

        assertEquals(1, fakeAi.callCount(), "second call must not re-trigger AI detection");
        assertEquals(rowsAfterFirstParse, importRowRepository.findByImportBatchId(batchId).size(),
                "re-running parse on an already-parsed batch must not duplicate rows");
    }

    private void assertQuarantinedWithReason(Long batchId, String expectedSubstring) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        assertEquals(ImportBatchStatus.QUARANTINED, batch.getStatus());
        assertTrue(batch.getErrorMessage() != null
                        && batch.getErrorMessage().toLowerCase().contains(expectedSubstring.toLowerCase()),
                () -> "expected error message to contain '" + expectedSubstring + "', got: " + batch.getErrorMessage());
    }

    private LayoutRuleDefinition parsedStandardRule() {
        LayoutRuleValidationResult result = layoutRuleValidator.validate(SupplierWorkbookFixtures.standardLayoutRuleJson());
        assertTrue(result.valid(), () -> "fixture rule must be valid: " + result.errors());
        return result.rule();
    }

    private ImportRuleVersion saveRuleVersion(
            LayoutRuleDefinition rule, RuleVersionStatus status, RuleVersionSource source, int version) throws Exception {
        ImportRuleVersion entity = ImportRuleVersion.builder()
                .shopId(SHOP_ID)
                .supplierSource(supplierSource)
                .version(version)
                .status(status)
                .source(source)
                .ruleDefinition(objectMapper.writeValueAsString(rule))
                .build();
        ImportRuleVersion saved = importRuleVersionRepository.save(entity);
        entityManager.flush();
        return saved;
    }

    private Long createStoredBatch(Workbook workbook) {
        try {
            byte[] bytes = SupplierWorkbookFixtures.toBytes(workbook);
            Path tempFile = Files.createTempFile("fixture-workbook-", ".xlsx");
            Files.write(tempFile, bytes);
            String sha256 = sha256Hex(bytes);
            String storageKey = importFileStorage.store(SHOP_ID, sha256, "price.xlsx", tempFile);
            Files.deleteIfExists(tempFile);

            ImportFile importFile = importFileRepository.save(ImportFile.builder()
                    .shopId(SHOP_ID)
                    .supplierSource(supplierSource)
                    .sha256(sha256)
                    .sizeBytes((long) bytes.length)
                    .mediaType("application/octet-stream")
                    .originalFilename("price.xlsx")
                    .storageKey(storageKey)
                    .receivedAt(LocalDateTime.now())
                    .build());

            ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                    .shopId(SHOP_ID)
                    .supplierSource(supplierSource)
                    .importFile(importFile)
                    .status(ImportBatchStatus.STORED)
                    .attemptNumber(1)
                    .build());
            entityManager.flush();
            return batch.getId();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
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
        ImportFileStorage importFileStorage(SupplierImportProperties properties) {
            return new LocalImportFileStorage(properties);
        }

        @Bean
        SpreadsheetParser spreadsheetParser(SupplierImportProperties properties) {
            return new SpreadsheetParser(properties);
        }

        @Bean
        LayoutRuleValidator layoutRuleValidator(ObjectMapper objectMapper) {
            return new LayoutRuleValidator(objectMapper);
        }

        @Bean
        FakeAiSpreadsheetLayoutDetector fakeAiSpreadsheetLayoutDetector() {
            return new FakeAiSpreadsheetLayoutDetector();
        }

        @Bean
        ImportBatchParseWriter importBatchParseWriter(
                ImportBatchRepository importBatchRepository,
                ImportRuleVersionRepository importRuleVersionRepository,
                ImportRowRepository importRowRepository,
                ObjectMapper objectMapper) {
            return new ImportBatchParseWriter(
                    importBatchRepository, importRuleVersionRepository, importRowRepository, objectMapper);
        }

        @Bean
        ImportBatchParsingService importBatchParsingService(
                ImportBatchRepository importBatchRepository,
                ImportRuleVersionRepository importRuleVersionRepository,
                ImportFileStorage importFileStorage,
                SpreadsheetParser spreadsheetParser,
                FakeAiSpreadsheetLayoutDetector fakeAiSpreadsheetLayoutDetector,
                LayoutRuleValidator layoutRuleValidator,
                SupplierImportProperties properties,
                ImportBatchParseWriter importBatchParseWriter) {
            return new ImportBatchParsingService(
                    importBatchRepository, importRuleVersionRepository, importFileStorage, spreadsheetParser,
                    fakeAiSpreadsheetLayoutDetector, layoutRuleValidator, properties, importBatchParseWriter);
        }
    }
}
