package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.LinkConfirmationSource;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of {@link ImportBatchNormalizingService}: the deterministic stage order
 * (link -&gt; barcode -&gt; fingerprint -&gt; fuzzy fallthrough), every explicit case from prompt 04
 * (Chanel/Шанель/Channel, 50/100 ml, tester/set, duplicate barcode, cross-shop isolation), and the
 * NORMALIZING -&gt; MATCHING batch transition/idempotency.
 */
@DataJpaTest
@Import(ImportBatchNormalizingServiceTest.TestConfig.class)
class ImportBatchNormalizingServiceTest {

    private static final String SHOP_A = "shop-a";
    private static final String SHOP_B = "shop-b";

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
    private SupplierProductLinkRepository supplierProductLinkRepository;
    @Autowired
    private MatchDecisionRepository matchDecisionRepository;
    @Autowired
    private ImportBatchNormalizingService importBatchNormalizingService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    private Supplier supplier;
    private SupplierSource supplierSource;

    @BeforeEach
    void setUp() {
        supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Test Supplier").build());
        supplierSource = supplierSourceRepository.save(
                SupplierSource.builder().shopId(SHOP_A).supplier(supplier).label("main").build());
        entityManager.flush();
    }

    @Test
    void supplierProductLinkHit_producesLearnedMatch() {
        Product product = saveProduct(SHOP_A, "Nivea", "Крем для рук 100 мл", null);
        supplierProductLinkRepository.save(SupplierProductLink.builder()
                .shopId(SHOP_A).supplier(supplier).product(product).externalSku("SKU-1")
                .confirmedSource(LinkConfirmationSource.MANUAL).build());

        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_EXTERNAL_SKU, "SKU-1",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Крем для рук 100 мл",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "220.50")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.LEARNED_MATCH, row.getStatus());
        assertEquals(product.getId(), row.getMatchedProduct().getId());
        assertEquals(ImportBatchStatus.MATCHING, importBatchRepository.findById(batchId).orElseThrow().getStatus());

        List<com.plstk.loyaltybot.entity.importing.MatchDecision> decisions = matchDecisionRepository.findByImportRowId(row.getId());
        assertEquals(1, decisions.size());
        assertEquals(MatchDecisionType.LEARNED, decisions.get(0).getDecisionType());
    }

    @Test
    void uniqueExactBarcode_noLink_producesExactMatch() {
        saveProduct(SHOP_A, "Brand", "Aroma Cream 50 ml", "4600000000017");

        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_BARCODE, "4600000000017",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Aroma Cream 50 ml",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "450.00")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.EXACT_MATCH, row.getStatus());
        assertTrue(row.getMatchedProduct() != null);
    }

    @Test
    void duplicateBarcode_fallsThroughToFuzzy_neverAutoMatched() {
        saveProduct(SHOP_A, "Brand", "Aroma Cream 50 ml", "9999999999999");
        saveProduct(SHOP_A, "Brand", "Aroma Cream 50 ml", "9999999999999");

        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_BARCODE, "9999999999999",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Aroma Cream 50 ml",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "450.00")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.PENDING, row.getStatus(), "duplicate barcode must never be auto-matched");
        assertNull(row.getMatchedProduct());
        assertTrue(row.getCandidateSearchResult() != null && !row.getCandidateSearchResult().isBlank());
    }

    @Test
    void safeFingerprint_unlinkedCatalogProduct_producesExactMatch() {
        saveProduct(SHOP_A, "Nivea", "Крем для рук 100 мл", null);

        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Nivea",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Крем для рук 100 мл",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "220.50")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.EXACT_MATCH, row.getStatus());
        assertTrue(row.getMatchedProduct() != null);
    }

    @Test
    void chanelShanelChannel_fuzzyCandidate_taggedBrandAlias_neverAutoMatched() {
        Product chanel = saveProduct(SHOP_A, "Chanel", "Chanel No 5 100 ml", "3145891234560");

        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Channel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Channel No 5 100 ml",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "12500.00")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.PENDING, row.getStatus(),
                "misspelled brand must never be treated as an identical fingerprint match");
        assertNull(row.getMatchedProduct());
        assertTrue(row.getCandidateSearchResult().contains("brandAlias"));
        assertTrue(row.getCandidateSearchResult().contains("\"productId\":" + chanel.getId()));
    }

    @Test
    void volumeConflict_50vs100ml_excludedFromMatch_flaggedInCandidates() {
        saveProduct(SHOP_A, "Brand", "Aroma Cream 100 ml", null);

        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Brand",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Aroma Cream 50 ml",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "450.00")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.PENDING, row.getStatus());
        assertNull(row.getMatchedProduct());
        assertTrue(row.getCandidateSearchResult().contains("VOLUME_UNIT"));
    }

    @Test
    void testerVsRetail_excludedFromMatch_flaggedInCandidates() {
        saveProduct(SHOP_A, "Chanel", "Chanel No 5 100 ml", null);

        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 Тестер 100 ml",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "9000.00")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.PENDING, row.getStatus());
        assertNull(row.getMatchedProduct());
        assertTrue(row.getCandidateSearchResult().contains("TESTER_VS_RETAIL"));
    }

    @Test
    void crossShopIsolation_neverMatchesOrShowsAnotherShopsProduct() {
        saveProduct(SHOP_B, "Brand", "Aroma Cream 50 ml", "7777777777777");

        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_BARCODE, "7777777777777",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Aroma Cream 50 ml",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "450.00")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.PENDING, row.getStatus());
        assertNull(row.getMatchedProduct(), "another shop's product must never be auto-matched");
        assertTrue(row.getCandidateSearchResult() == null || row.getCandidateSearchResult().isBlank(),
                "another shop's product must never even appear as a fuzzy candidate");
    }

    @Test
    void batchTransitionsToMatching_onlyAfterProcessing_andIsIdempotent() {
        saveProduct(SHOP_A, "Brand", "Aroma Cream 50 ml", "1111111111111");
        Long batchId = createBatchWithRows(row(Map.of(
                LayoutRuleDefinition.FIELD_BARCODE, "1111111111111",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Aroma Cream 50 ml",
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, "450.00")));

        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportBatchStatus.MATCHING, importBatchRepository.findById(batchId).orElseThrow().getStatus());
        int decisionsAfterFirstRun = matchDecisionRepository.findByImportRowId(onlyRow(batchId).getId()).size();
        assertEquals(1, decisionsAfterFirstRun);

        // Re-running on an already-MATCHING batch must be a complete no-op (claim fails).
        importBatchNormalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();

        assertEquals(ImportBatchStatus.MATCHING, importBatchRepository.findById(batchId).orElseThrow().getStatus());
        assertEquals(decisionsAfterFirstRun, matchDecisionRepository.findByImportRowId(onlyRow(batchId).getId()).size(),
                "re-running on an already-processed batch must not duplicate MatchDecision rows");
    }

    private Product saveProduct(String shopId, String brand, String name, String barcode) {
        return productRepository.save(Product.builder()
                .shopId(shopId).brand(brand).name(name).barcode(barcode).currency("RUB").build());
    }

    private Map<String, String> row(Map<String, String> values) {
        return new HashMap<>(values);
    }

    private Long createBatchWithRows(Map<String, String> rawValues) {
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
                .status(ImportBatchStatus.NORMALIZING)
                .attemptNumber(1)
                .build());

        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A)
                .importBatch(batch)
                .sourceSheet("Sheet1")
                .sourceRowNumber(1)
                .rawData(toJson(rawValues))
                .status(ImportRowStatus.PENDING)
                .build());

        entityManager.flush();
        return batch.getId();
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

        @Bean
        RowAttributeNormalizer rowAttributeNormalizer() {
            return new RowAttributeNormalizer();
        }

        @Bean
        BrandAliasResolver brandAliasResolver() {
            return new BrandAliasResolver();
        }

        @Bean
        CriticalAttributeConflictChecker criticalAttributeConflictChecker() {
            return new CriticalAttributeConflictChecker();
        }

        @Bean
        CandidateScorer candidateScorer(BrandAliasResolver brandAliasResolver, CriticalAttributeConflictChecker conflictChecker) {
            return new CandidateScorer(brandAliasResolver, conflictChecker);
        }

        @Bean
        ProductCandidateFetcher productCandidateFetcher(ProductRepository productRepository) {
            return new SimpleProductCandidateFetcher(productRepository);
        }

        @Bean
        CandidateSearchService candidateSearchService(
                ProductCandidateFetcher candidateFetcher, RowAttributeNormalizer normalizer,
                CandidateScorer scorer, SupplierImportProperties properties) {
            return new CandidateSearchService(candidateFetcher, normalizer, scorer, properties);
        }

        @Bean
        DeterministicMatchResolver deterministicMatchResolver(
                SupplierProductLinkRepository supplierProductLinkRepository,
                ProductRepository productRepository,
                RowAttributeNormalizer normalizer,
                CriticalAttributeConflictChecker conflictChecker,
                CandidateSearchService candidateSearchService) {
            return new DeterministicMatchResolver(
                    supplierProductLinkRepository, productRepository, normalizer, conflictChecker, candidateSearchService);
        }

        @Bean
        ImportBatchNormalizeWriter importBatchNormalizeWriter(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                ProductRepository productRepository,
                MatchDecisionRepository matchDecisionRepository,
                ObjectMapper objectMapper) {
            return new ImportBatchNormalizeWriter(
                    importBatchRepository, importRowRepository, productRepository, matchDecisionRepository, objectMapper);
        }

        @Bean
        ImportBatchNormalizingService importBatchNormalizingService(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                RowAttributeNormalizer normalizer,
                DeterministicMatchResolver matchResolver,
                ObjectMapper objectMapper,
                ImportBatchNormalizeWriter writer) {
            return new ImportBatchNormalizingService(
                    importBatchRepository, importRowRepository, normalizer, matchResolver, objectMapper, writer);
        }
    }
}
