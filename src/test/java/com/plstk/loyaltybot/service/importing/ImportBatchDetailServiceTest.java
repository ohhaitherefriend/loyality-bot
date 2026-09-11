package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.DecidedBy;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.ImportRuleVersion;
import com.plstk.loyaltybot.entity.importing.MatchDecision;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.ImportRuleVersionRepository;
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
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 07 batch/row detail: batch identity + row-status breakdown + apply "diff" counters, row
 * listing with an optional status filter, and full row detail with raw/normalized/candidates/
 * AI-and-reviewer audit trail (newest decision first).
 */
@DataJpaTest
@Import(ImportBatchDetailServiceTest.TestConfig.class)
class ImportBatchDetailServiceTest {

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
    private MatchDecisionRepository matchDecisionRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ImportBatchDetailService importBatchDetailService;
    @Autowired
    private ObjectMapper objectMapper;
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
    void getBatchDetail_includesIdentityRuleVersionAndRowStatusCounts() {
        ImportRuleVersion ruleVersion = importRuleVersionRepository.save(ImportRuleVersion.builder()
                .shopId(SHOP_A).supplierSource(source).version(3).status(RuleVersionStatus.ACTIVE).ruleDefinition("{}").build());
        Long batchId = createBatch(ImportBatchStatus.APPLIED, ruleVersion);
        addRowWithNumber(batchId, 1, ImportRowStatus.APPLIED);
        addRowWithNumber(batchId, 2, ImportRowStatus.APPLIED);
        addRowWithNumber(batchId, 3, ImportRowStatus.NEEDS_REVIEW);
        flushClear();

        Optional<BatchDetailResponse> result = importBatchDetailService.getBatchDetail(SHOP_A, batchId);

        assertTrue(result.isPresent());
        BatchDetailResponse detail = result.get();
        assertEquals(batchId, detail.batchId());
        assertEquals(ImportBatchStatus.APPLIED, detail.status());
        assertEquals(3, detail.ruleVersionNumber());
        assertEquals(2L, detail.rowStatusCounts().get(ImportRowStatus.APPLIED.name()));
        assertEquals(1L, detail.rowStatusCounts().get(ImportRowStatus.NEEDS_REVIEW.name()));
    }

    @Test
    void getBatchDetail_unknownBatch_returnsEmpty() {
        assertTrue(importBatchDetailService.getBatchDetail(SHOP_A, 999_999L).isEmpty());
    }

    @Test
    void getBatchDetail_crossShop_returnsEmpty() {
        Long batchId = createBatch(ImportBatchStatus.APPLIED, null);
        flushClear();

        assertTrue(importBatchDetailService.getBatchDetail("shop-b", batchId).isEmpty());
    }

    @Test
    void listBatchRows_withoutFilter_returnsAllRowsOrderedBySourceRowNumber() {
        Long batchId = createBatch(ImportBatchStatus.APPLIED, null);
        addRowWithNumber(batchId, 2, ImportRowStatus.APPLIED);
        addRowWithNumber(batchId, 1, ImportRowStatus.NEEDS_REVIEW);
        flushClear();

        Optional<Page<RowListItem>> result = importBatchDetailService.listBatchRows(SHOP_A, batchId, null, 0, 20);

        assertTrue(result.isPresent());
        assertEquals(2, result.get().getTotalElements());
        assertEquals(1, result.get().getContent().get(0).sourceRowNumber());
        assertEquals(2, result.get().getContent().get(1).sourceRowNumber());
    }

    @Test
    void listBatchRows_withStatusFilter_onlyReturnsMatchingRows() {
        Long batchId = createBatch(ImportBatchStatus.NEEDS_ATTENTION, null);
        addRowWithNumber(batchId, 1, ImportRowStatus.NEEDS_REVIEW);
        addRowWithNumber(batchId, 2, ImportRowStatus.APPLIED);
        flushClear();

        Optional<Page<RowListItem>> result = importBatchDetailService.listBatchRows(
                SHOP_A, batchId, List.of(ImportRowStatus.NEEDS_REVIEW), 0, 20);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getTotalElements());
        assertEquals(ImportRowStatus.NEEDS_REVIEW, result.get().getContent().get(0).status());
    }

    @Test
    void listBatchRows_unknownBatch_returnsEmpty() {
        assertTrue(importBatchDetailService.listBatchRows(SHOP_A, 999_999L, null, 0, 20).isEmpty());
    }

    @Test
    void getRowDetail_includesRawNormalizedCandidatesAndDecisionsNewestFirst() throws Exception {
        Product product = productRepository.save(Product.builder()
                .shopId(SHOP_A).name("Matched Product").currency("RUB").build());
        Long batchId = createBatch(ImportBatchStatus.NEEDS_ATTENTION, null);
        NormalizedRowData normalized = normalized("SKU-1", "Brand", "150.00");
        List<ScoredCandidate> candidates = List.of(new ScoredCandidate(
                product.getId(), "Matched Product", new BigDecimal("0.90"),
                Map.of("nameSimilarity", new BigDecimal("0.80")), List.of("brand"), List.of(), normalized));
        ImportRow row = ImportRow.builder()
                .shopId(SHOP_A).importBatch(importBatchRepository.findById(batchId).orElseThrow())
                .sourceRowNumber(1).rawData(toJson(Map.of("rawName", "Chanel No 5")))
                .normalizedData(toJson(normalized))
                .candidateSearchResult(toJson(candidates))
                .status(ImportRowStatus.NEEDS_REVIEW).matchedProduct(product).build();
        row = importRowRepository.save(row);
        flushClear();

        matchDecisionRepository.save(MatchDecision.builder()
                .shopId(SHOP_A).importRow(reload(row.getId())).decisionType(MatchDecisionType.AI_NO_MATCH)
                .decidedBy(DecidedBy.SYSTEM).modelProvider("deepseek").modelName("deepseek-chat")
                .promptVersion("v1").confidenceScore(new BigDecimal("0.55"))
                .decidedAt(LocalDateTime.now().minusMinutes(5)).build());
        matchDecisionRepository.save(MatchDecision.builder()
                .shopId(SHOP_A).importRow(reload(row.getId())).decisionType(MatchDecisionType.MANUAL)
                .decidedBy(DecidedBy.HUMAN).chosenProduct(product).reviewerUserId(7L).reviewerEmail("op@shop.test")
                .decidedAt(LocalDateTime.now()).build());
        flushClear();

        Optional<RowDetailResponse> result = importBatchDetailService.getRowDetail(SHOP_A, row.getId());

        assertTrue(result.isPresent());
        RowDetailResponse detail = result.get();
        assertEquals("Chanel No 5", detail.rawData().get("rawName"));
        assertEquals("Brand", detail.normalizedData().brand());
        assertEquals(1, detail.candidates().size());
        assertEquals(product.getId(), detail.candidates().get(0).productId());
        assertEquals(product.getId(), detail.matchedProductId());
        assertEquals(2, detail.decisions().size());
        assertEquals(DecidedBy.HUMAN, detail.decisions().get(0).decidedBy(), "newest decision (human review) must come first");
        assertEquals("op@shop.test", detail.decisions().get(0).reviewerEmail());
        assertEquals(DecidedBy.SYSTEM, detail.decisions().get(1).decidedBy());
        assertEquals("deepseek", detail.decisions().get(1).modelProvider());
    }

    @Test
    void getRowDetail_unknownRow_returnsEmpty() {
        assertTrue(importBatchDetailService.getRowDetail(SHOP_A, 999_999L).isEmpty());
    }

    // ===== helpers =====

    private Long createBatch(ImportBatchStatus status, ImportRuleVersion ruleVersion) {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_A).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(1L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_A).supplierSource(source).importFile(importFile).ruleVersion(ruleVersion)
                .status(status).attemptNumber(1).build());
        entityManager.flush();
        return batch.getId();
    }

    private void addRowWithNumber(Long batchId, int sourceRowNumber, ImportRowStatus status) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(sourceRowNumber).rawData("{}").status(status).build());
    }

    private NormalizedRowData normalized(String externalSku, String brand, String price) {
        return new NormalizedRowData(
                brand, "line", null, new BigDecimal("100"), "ml", null, null, false, false,
                externalSku, null, new BigDecimal(price), 5, brand.toLowerCase() + " line", "fp-" + externalSku,
                RowAttributeNormalizer.NORMALIZATION_VERSION);
    }

    private ImportRow reload(Long rowId) {
        return importRowRepository.findById(rowId).orElseThrow();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ImportBatchDetailService importBatchDetailService(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                MatchDecisionRepository matchDecisionRepository,
                ObjectMapper objectMapper) {
            return new ImportBatchDetailService(importBatchRepository, importRowRepository, matchDecisionRepository, objectMapper);
        }
    }
}
