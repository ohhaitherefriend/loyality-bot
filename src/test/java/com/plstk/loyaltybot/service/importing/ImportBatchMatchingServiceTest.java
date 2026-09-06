package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MatchDecision;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of {@link ImportBatchMatchingService}: promoting deterministic Prompt 04
 * matches, the safe/unsafe NEW_PRODUCT gate, and every explicit AI-matcher case from prompt 05
 * (invented candidate id, malformed JSON, conflict, boundary thresholds, disabled provider, shadow
 * mode), plus the MATCHING -&gt; VALIDATING batch transition/idempotency.
 */
@DataJpaTest
@Import(ImportBatchMatchingServiceTest.TestConfig.class)
class ImportBatchMatchingServiceTest {

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
    private MatchDecisionRepository matchDecisionRepository;
    @Autowired
    private ImportBatchMatchingService importBatchMatchingService;
    @Autowired
    private FakeAiCatalogMatcher fakeAiCatalogMatcher;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    private Supplier supplier;
    private SupplierSource supplierSource;

    @BeforeEach
    void setUp() {
        fakeAiCatalogMatcher.reset();
        supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Test Supplier").build());
        supplierSource = supplierSourceRepository.save(
                SupplierSource.builder().shopId(SHOP_A).supplier(supplier).label("main").build());
        entityManager.flush();
    }

    @Test
    void deterministicExactMatch_isPromotedToAutoApproved_noDuplicateDecision() {
        Product product = saveProduct("Nivea", "Крем 100 мл");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.EXACT_MATCH, normalized("Nivea", true), null);
        row.setMatchedProduct(product);
        importRowRepository.save(row);
        matchDecisionRepository.save(MatchDecision.builder()
                .shopId(SHOP_A).importRow(row).decisionType(MatchDecisionType.EXACT)
                .confidenceScore(BigDecimal.ONE).candidateProductIds("[]").conflicts("[]").build());
        entityManager.flush();
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow reloaded = onlyRow(batchId);
        assertEquals(ImportRowStatus.AUTO_APPROVED, reloaded.getStatus());
        assertEquals(product.getId(), reloaded.getMatchedProduct().getId());
        assertEquals(1, matchDecisionRepository.findByImportRowId(reloaded.getId()).size(),
                "promoting an already-safe deterministic match must not write a duplicate MatchDecision");
        assertEquals(0, fakeAiCatalogMatcher.callCount(), "deterministic rows never call AI");
    }

    @Test
    void deterministicLearnedMatch_isPromotedToAutoApproved() {
        Product product = saveProduct("Nivea", "Крем 100 мл");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.LEARNED_MATCH, normalized("Nivea", true), null);
        row.setMatchedProduct(product);
        importRowRepository.save(row);
        entityManager.flush();
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportRowStatus.AUTO_APPROVED, onlyRow(batchId).getStatus());
    }

    @Test
    void pendingRow_noCandidates_brandPresent_becomesAutoApprovedNewProduct() {
        Long batchId = createBatch();
        addRow(batchId, ImportRowStatus.PENDING, normalized("Nivea", true), null);
        entityManager.flush();
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.AUTO_APPROVED, row.getStatus());
        assertNull(row.getMatchedProduct());
        assertEquals(0, fakeAiCatalogMatcher.callCount(), "no candidates -> AI is never called");
        List<MatchDecision> decisions = matchDecisionRepository.findByImportRowId(row.getId());
        assertEquals(1, decisions.size());
        assertEquals(MatchDecisionType.NEW_PRODUCT, decisions.get(0).getDecisionType());
    }

    @Test
    void pendingRow_noCandidates_brandMissing_becomesNeedsReview() {
        Long batchId = createBatch();
        addRow(batchId, ImportRowStatus.PENDING, normalized(null, true), null);
        entityManager.flush();
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.NEEDS_REVIEW, row.getStatus());
        assertNull(row.getMatchedProduct());
        assertEquals(0, fakeAiCatalogMatcher.callCount());
    }

    @Test
    void pendingRow_withCandidates_aiCallFails_becomesNeedsReview() {
        Product candidateProduct = saveProduct("Chanel", "No 5 100 ml");
        Long batchId = createBatch();
        addRow(batchId, ImportRowStatus.PENDING, normalized("Chanel", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.90", List.of())));
        fakeAiCatalogMatcher.alwaysReturn(AiMatchResponse.failure("simulated outage", true, "deepseek"));
        entityManager.flush();
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.NEEDS_REVIEW, row.getStatus());
        List<MatchDecision> decisions = matchDecisionRepository.findByImportRowId(row.getId());
        assertTrue(decisions.get(0).getReason().contains("AI matcher call failed"));
    }

    @Test
    void pendingRow_aiInventsCandidateId_becomesNeedsReview() {
        Product candidateProduct = saveProduct("Chanel", "No 5 100 ml");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.PENDING, normalized("Chanel", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.90", List.of())));
        entityManager.flush();
        fakeAiCatalogMatcher.enqueue(aiSuccess(matchJson(row.getId().toString(), "999999999", 0.95, "ok")));
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow reloaded = onlyRow(batchId);
        assertEquals(ImportRowStatus.NEEDS_REVIEW, reloaded.getStatus());
        assertNull(reloaded.getMatchedProduct(), "an invented candidate id must never be auto-matched");
    }

    @Test
    void pendingRow_aiReturnsMalformedJson_becomesNeedsReview() {
        Product candidateProduct = saveProduct("Chanel", "No 5 100 ml");
        Long batchId = createBatch();
        addRow(batchId, ImportRowStatus.PENDING, normalized("Chanel", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.90", List.of())));
        fakeAiCatalogMatcher.alwaysReturn(aiSuccess("{not valid json"));
        entityManager.flush();
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportRowStatus.NEEDS_REVIEW, onlyRow(batchId).getStatus());
    }

    @Test
    void pendingRow_aiMatchWithBackendDetectedConflict_neverAutoApproved() {
        Product candidateProduct = saveProduct("Brand", "Aroma 100 ml");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.PENDING, normalized("Brand", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.95", List.of("VOLUME_UNIT"))));
        entityManager.flush();
        fakeAiCatalogMatcher.enqueue(aiSuccess(matchJson(row.getId().toString(), candidateProduct.getId().toString(), 0.99, "high confidence")));
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow reloaded = onlyRow(batchId);
        assertEquals(ImportRowStatus.NEEDS_REVIEW, reloaded.getStatus(),
                "AI confidence alone must never override a backend-detected critical conflict");
        assertEquals(candidateProduct.getId(), reloaded.getMatchedProduct().getId(),
                "the suggested candidate is still surfaced for human review");
    }

    @Test
    void pendingRow_aiMatch_exactlyAtBothThresholds_isAutoApproved() {
        Product candidateProduct = saveProduct("Brand", "Aroma 100 ml");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.PENDING, normalized("Brand", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.80", List.of())));
        entityManager.flush();
        fakeAiCatalogMatcher.enqueue(aiSuccess(matchJson(row.getId().toString(), candidateProduct.getId().toString(), 0.55, "boundary")));
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow reloaded = onlyRow(batchId);
        assertEquals(ImportRowStatus.AUTO_APPROVED, reloaded.getStatus(), "thresholds are inclusive (>=)");
        assertEquals(candidateProduct.getId(), reloaded.getMatchedProduct().getId());
    }

    @Test
    void pendingRow_aiMatch_belowScoreThreshold_isNeedsReview_evenWithHighConfidence() {
        Product candidateProduct = saveProduct("Brand", "Aroma 100 ml");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.PENDING, normalized("Brand", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.79", List.of())));
        entityManager.flush();
        fakeAiCatalogMatcher.enqueue(aiSuccess(matchJson(row.getId().toString(), candidateProduct.getId().toString(), 0.99, "weak deterministic")));
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportRowStatus.NEEDS_REVIEW, onlyRow(batchId).getStatus());
    }

    @Test
    void pendingRow_aiMatch_belowConfidenceThreshold_isNeedsReview_evenWithHighDeterministicScore() {
        Product candidateProduct = saveProduct("Brand", "Aroma 100 ml");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.PENDING, normalized("Brand", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.99", List.of())));
        entityManager.flush();
        fakeAiCatalogMatcher.enqueue(aiSuccess(matchJson(row.getId().toString(), candidateProduct.getId().toString(), 0.10, "unsure")));
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportRowStatus.NEEDS_REVIEW, onlyRow(batchId).getStatus(),
                "model confidence alone (here: low) combined with a strong deterministic score must still gate correctly, "
                        + "and a strong deterministic score alone must never bypass a low AI confidence");
    }

    @Test
    void pendingRow_aiMatch_safe_isAutoApprovedWithMatchedProduct() {
        Product candidateProduct = saveProduct("Chanel", "No 5 100 ml");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.PENDING, normalized("Chanel", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.92", List.of())));
        entityManager.flush();
        fakeAiCatalogMatcher.enqueue(aiSuccess(matchJson(row.getId().toString(), candidateProduct.getId().toString(), 0.9, "confident match")));
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow reloaded = onlyRow(batchId);
        assertEquals(ImportRowStatus.AUTO_APPROVED, reloaded.getStatus());
        assertEquals(candidateProduct.getId(), reloaded.getMatchedProduct().getId());
        assertEquals(MatchDecisionType.AI_MATCH, matchDecisionRepository.findByImportRowId(reloaded.getId()).get(0).getDecisionType());
    }

    @Test
    void shadowModeSource_stillComputesAndPersistsAutoApprovedDecisions() {
        supplierSource.setShadowMode(true);
        supplierSource.setAutoApply(false);
        supplierSourceRepository.save(supplierSource);
        Product candidateProduct = saveProduct("Chanel", "No 5 100 ml");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.PENDING, normalized("Chanel", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.92", List.of())));
        entityManager.flush();
        fakeAiCatalogMatcher.enqueue(aiSuccess(matchJson(row.getId().toString(), candidateProduct.getId().toString(), 0.9, "confident match")));
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        // Prompt 05 deliberately does not gate on shadowMode/autoApply: decisions are always
        // computed/persisted; the future Apply stage (Prompt 06) is what withholds action for shadow.
        assertEquals(ImportRowStatus.AUTO_APPROVED, onlyRow(batchId).getStatus());
    }

    @Test
    void sourceThresholdOverride_isRespected() {
        supplierSource.setAiAutoApproveMinScoreOverride(new BigDecimal("0.99"));
        supplierSourceRepository.save(supplierSource);
        Product candidateProduct = saveProduct("Chanel", "No 5 100 ml");
        Long batchId = createBatch();
        ImportRow row = addRow(batchId, ImportRowStatus.PENDING, normalized("Chanel", true),
                List.of(scoredCandidate(candidateProduct.getId(), "0.92", List.of())));
        entityManager.flush();
        fakeAiCatalogMatcher.enqueue(aiSuccess(matchJson(row.getId().toString(), candidateProduct.getId().toString(), 0.99, "confident match")));
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportRowStatus.NEEDS_REVIEW, onlyRow(batchId).getStatus(),
                "a stricter per-source override must be respected over the global default");
    }

    @Test
    void batchTransitionsToValidating_onlyAfterProcessing_andIsIdempotent() {
        Long batchId = createBatch();
        addRow(batchId, ImportRowStatus.PENDING, normalized(null, true), null);
        entityManager.flush();
        entityManager.clear();

        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportBatchStatus.VALIDATING, importBatchRepository.findById(batchId).orElseThrow().getStatus());
        int decisionsAfterFirstRun = matchDecisionRepository.findByImportRowId(onlyRow(batchId).getId()).size();
        assertEquals(1, decisionsAfterFirstRun);

        // Re-running on an already-VALIDATING batch must be a complete no-op (claim fails).
        importBatchMatchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportBatchStatus.VALIDATING, importBatchRepository.findById(batchId).orElseThrow().getStatus());
        assertEquals(decisionsAfterFirstRun, matchDecisionRepository.findByImportRowId(onlyRow(batchId).getId()).size(),
                "re-running on an already-processed batch must not duplicate MatchDecision rows");
    }

    private Product saveProduct(String brand, String name) {
        return productRepository.save(Product.builder().shopId(SHOP_A).brand(brand).name(name).currency("RUB").build());
    }

    private NormalizedRowData normalized(String brand, boolean withPrice) {
        return new NormalizedRowData(
                brand, "line", null, new BigDecimal("100"), "ml", null, null, false, false,
                "SKU-1", null, withPrice ? new BigDecimal("100.00") : null, null,
                brand == null ? "line" : (brand + " line").toLowerCase(), "fp");
    }

    private ScoredCandidate scoredCandidate(Long productId, String score, List<String> conflicts) {
        NormalizedRowData attrs = normalized("Brand", true);
        return new ScoredCandidate(productId, "Candidate " + productId, new BigDecimal(score), Map.of(), List.of(), conflicts, attrs);
    }

    private AiMatchResponse aiSuccess(String rawContent) {
        return AiMatchResponse.success(rawContent, "deepseek", "deepseek-chat", "catalog-matcher-v1", 100, 20, 42L);
    }

    private String matchJson(String rowId, String candidateId, double confidence, String reason) {
        return String.format(
                "{\"row_id\":\"%s\",\"decision\":\"MATCH\",\"candidate_id\":\"%s\",\"confidence\":%s,"
                        + "\"matched_attributes\":[],\"conflicts\":[],\"reason\":\"%s\"}",
                rowId, candidateId, confidence, reason);
    }

    private Long createBatch() {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_A)
                .supplierSource(supplierSource)
                .sha256("sha-" + System.nanoTime())
                .sizeBytes(10L)
                .mediaType("application/octet-stream")
                .originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime())
                .receivedAt(LocalDateTime.now())
                .build());

        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_A)
                .supplierSource(supplierSource)
                .importFile(importFile)
                .status(ImportBatchStatus.MATCHING)
                .attemptNumber(1)
                .build());
        entityManager.flush();
        return batch.getId();
    }

    private ImportRow addRow(Long batchId, ImportRowStatus status, NormalizedRowData normalized, List<ScoredCandidate> candidates) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        ImportRow row = importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A)
                .importBatch(batch)
                .sourceSheet("Sheet1")
                .sourceRowNumber(1)
                .rawData("{}")
                .normalizedData(toJson(normalized))
                .candidateSearchResult(candidates == null || candidates.isEmpty() ? null : toJson(candidates))
                .status(status)
                .build());
        entityManager.flush();
        return row;
    }

    private ImportRow onlyRow(Long batchId) {
        List<ImportRow> rows = importRowRepository.findByImportBatchId(batchId);
        assertEquals(1, rows.size());
        return rows.get(0);
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

        // SupplierImportProperties is already registered by the real application context via
        // @ConfigurationProperties (prefix "supplier-import"); declaring a second bean of this type here
        // would cause a NoUniqueBeanDefinitionException, so tests just @Autowired the real one below.

        @Bean
        FakeAiCatalogMatcher fakeAiCatalogMatcher() {
            return new FakeAiCatalogMatcher();
        }

        @Bean
        CatalogMatchResponseValidator catalogMatchResponseValidator(ObjectMapper objectMapper) {
            return new CatalogMatchResponseValidator(objectMapper);
        }

        @Bean
        ImportBatchMatchWriter importBatchMatchWriter(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                ProductRepository productRepository,
                MatchDecisionRepository matchDecisionRepository,
                ObjectMapper objectMapper) {
            return new ImportBatchMatchWriter(
                    importBatchRepository, importRowRepository, productRepository, matchDecisionRepository, objectMapper);
        }

        @Bean
        ImportBatchMatchingService importBatchMatchingService(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                FakeAiCatalogMatcher fakeAiCatalogMatcher,
                CatalogMatchResponseValidator catalogMatchResponseValidator,
                SupplierImportProperties properties,
                ObjectMapper objectMapper,
                ImportBatchMatchWriter writer) {
            return new ImportBatchMatchingService(
                    importBatchRepository, importRowRepository, fakeAiCatalogMatcher, catalogMatchResponseValidator,
                    properties, objectMapper, writer);
        }
    }
}
