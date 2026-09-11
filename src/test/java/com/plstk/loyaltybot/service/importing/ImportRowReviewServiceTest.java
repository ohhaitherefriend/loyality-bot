package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.DecidedBy;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MatchDecision;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 07 human row-review actions: MATCH/NO_MATCH/CREATE_PRODUCT/IGNORE, optimistic-lock
 * conflicts, status-gate rejection, reviewer audit, and bulk review with per-row failures.
 */
@DataJpaTest
@Import(ImportRowReviewServiceTest.TestConfig.class)
class ImportRowReviewServiceTest {

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
    private ImportRowReviewService importRowReviewService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    private SupplierSource source;
    private final AdminUser reviewer = AdminUser.builder().id(42L).email("reviewer@shop.test").build();

    @BeforeEach
    void setUp() {
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier").build());
        source = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(supplier).label("main").build());
        flushClear();
    }

    @Test
    void match_movesRowToApproved_setsMatchedProduct_andWritesHumanDecision() {
        Product product = productRepository.save(Product.builder()
                .shopId(SHOP_A).name("Existing Product").currency("RUB").build());
        Long rowId = addRow(ImportRowStatus.NEEDS_REVIEW, normalizedJson("100.00"), null).getId();
        flushClear();

        Optional<ImportRow> result = importRowReviewService.reviewRow(
                SHOP_A, rowId, RowReviewAction.MATCH, 0L, product.getId(), "looks right", reviewer);

        assertTrue(result.isPresent());
        assertEquals(ImportRowStatus.APPROVED, result.get().getStatus());
        assertEquals(product.getId(), result.get().getMatchedProduct().getId());

        List<MatchDecision> decisions = matchDecisionRepository.findByImportRowIdOrderByDecidedAtDesc(rowId);
        assertEquals(1, decisions.size());
        MatchDecision decision = decisions.get(0);
        assertEquals(DecidedBy.HUMAN, decision.getDecidedBy());
        assertEquals(reviewer.getId(), decision.getReviewerUserId());
        assertEquals(reviewer.getEmail(), decision.getReviewerEmail());
        assertEquals(product.getId(), decision.getChosenProduct().getId());
    }

    @Test
    void match_withoutProductId_throwsRowReviewException() {
        Long rowId = addRow(ImportRowStatus.NEEDS_REVIEW, normalizedJson("100.00"), null).getId();
        flushClear();

        assertThrows(RowReviewException.class, () ->
                importRowReviewService.reviewRow(SHOP_A, rowId, RowReviewAction.MATCH, 0L, null, null, reviewer));
    }

    @Test
    void createProduct_withoutSupplierPrice_isRejected() {
        // INVALID rows can reach the parser with no usable normalizedData at all.
        Long rowId = addRow(ImportRowStatus.INVALID, null, null).getId();
        flushClear();

        assertThrows(RowReviewException.class, () ->
                importRowReviewService.reviewRow(SHOP_A, rowId, RowReviewAction.CREATE_PRODUCT, 0L, null, null, reviewer));
    }

    @Test
    void createProduct_withValidNormalizedData_approvesRowWithNoMatchedProduct() {
        Long rowId = addRow(ImportRowStatus.INVALID, normalizedJson("50.00"), null).getId();
        flushClear();

        Optional<ImportRow> result = importRowReviewService.reviewRow(
                SHOP_A, rowId, RowReviewAction.CREATE_PRODUCT, 0L, null, "safe new product", reviewer);

        assertTrue(result.isPresent());
        assertEquals(ImportRowStatus.APPROVED, result.get().getStatus());
        assertEquals(null, result.get().getMatchedProduct());
    }

    @Test
    void noMatch_movesRowToIgnored() {
        Long rowId = addRow(ImportRowStatus.NEEDS_REVIEW, normalizedJson("100.00"), null).getId();
        flushClear();

        Optional<ImportRow> result = importRowReviewService.reviewRow(
                SHOP_A, rowId, RowReviewAction.NO_MATCH, 0L, null, null, reviewer);

        assertTrue(result.isPresent());
        assertEquals(ImportRowStatus.IGNORED, result.get().getStatus());
    }

    @Test
    void ignore_movesRowToIgnored() {
        Long rowId = addRow(ImportRowStatus.NEEDS_REVIEW, normalizedJson("100.00"), null).getId();
        flushClear();

        Optional<ImportRow> result = importRowReviewService.reviewRow(
                SHOP_A, rowId, RowReviewAction.IGNORE, 0L, null, null, reviewer);

        assertTrue(result.isPresent());
        assertEquals(ImportRowStatus.IGNORED, result.get().getStatus());
    }

    @Test
    void staleExpectedVersion_throwsVersionConflict() {
        Long rowId = addRow(ImportRowStatus.NEEDS_REVIEW, normalizedJson("100.00"), null).getId();
        flushClear();

        assertThrows(RowVersionConflictException.class, () ->
                importRowReviewService.reviewRow(SHOP_A, rowId, RowReviewAction.NO_MATCH, 99L, null, null, reviewer));
    }

    @Test
    void rowNotInReviewableStatus_throwsRowReviewException() {
        Long rowId = addRow(ImportRowStatus.APPLIED, normalizedJson("100.00"), null).getId();
        flushClear();

        assertThrows(RowReviewException.class, () ->
                importRowReviewService.reviewRow(SHOP_A, rowId, RowReviewAction.NO_MATCH, 0L, null, null, reviewer));
    }

    @Test
    void unknownRowId_returnsEmpty() {
        Optional<ImportRow> result = importRowReviewService.reviewRow(
                SHOP_A, 999_999L, RowReviewAction.NO_MATCH, null, null, null, reviewer);
        assertTrue(result.isEmpty());
    }

    @Test
    void bulkReview_mixedCompatibility_succeedsForValidRowsAndReportsFailuresForOthers() {
        Long reviewable1 = addRow(ImportRowStatus.NEEDS_REVIEW, normalizedJson("10.00"), null).getId();
        Long reviewable2 = addRow(ImportRowStatus.INVALID, normalizedJson("20.00"), null).getId();
        Long notReviewable = addRow(ImportRowStatus.APPLIED, normalizedJson("30.00"), null).getId();
        long missingId = 424242L;
        flushClear();

        BulkReviewResult result = importRowReviewService.bulkReview(
                SHOP_A, List.of(reviewable1, reviewable2, notReviewable, missingId), RowReviewAction.IGNORE, "bulk cleanup", reviewer);

        assertEquals(2, result.succeededRowIds().size());
        assertTrue(result.succeededRowIds().containsAll(List.of(reviewable1, reviewable2)));
        assertEquals(2, result.failures().size());
        assertTrue(result.failures().containsKey(notReviewable));
        assertTrue(result.failures().containsKey(missingId));

        assertEquals(ImportRowStatus.IGNORED, reload(reviewable1).getStatus());
        assertEquals(ImportRowStatus.IGNORED, reload(reviewable2).getStatus());
        assertEquals(ImportRowStatus.APPLIED, reload(notReviewable).getStatus(), "incompatible row must be left untouched");
    }

    @Test
    void bulkReview_matchAction_isRejectedAsUnsupportedForBulk() {
        Long rowId = addRow(ImportRowStatus.NEEDS_REVIEW, normalizedJson("10.00"), null).getId();
        flushClear();

        assertThrows(RowReviewException.class, () ->
                importRowReviewService.bulkReview(SHOP_A, List.of(rowId), RowReviewAction.MATCH, null, reviewer));
    }

    // ===== helpers =====

    private ImportRow addRow(ImportRowStatus status, String normalizedData, Product matchedProduct) {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_A).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(1L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_A).supplierSource(source).importFile(importFile).attemptNumber(1).build());
        ImportRow row = ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1).rawData("{}")
                .normalizedData(normalizedData).status(status).build();
        if (matchedProduct != null) {
            row.setMatchedProduct(matchedProduct);
        }
        return importRowRepository.save(row);
    }

    private String normalizedJson(String price) {
        NormalizedRowData data = new NormalizedRowData(
                "Brand", "line", null, new BigDecimal("100"), "ml", null, null, false, false,
                "SKU-1", null, new BigDecimal(price), 5, "brand line", "fp-1", RowAttributeNormalizer.NORMALIZATION_VERSION);
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ImportRow reload(Long rowId) {
        return importRowRepository.findById(rowId).orElseThrow();
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
        ImportRowReviewService importRowReviewService(
                ImportRowRepository importRowRepository,
                ProductRepository productRepository,
                MatchDecisionRepository matchDecisionRepository,
                ObjectMapper objectMapper) {
            return new ImportRowReviewService(importRowRepository, productRepository, matchDecisionRepository, objectMapper);
        }
    }
}
