package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.BrandAlias;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4 regression coverage: a catalog with 1000+ products, where every target product's id is
 * far past the old hardcoded {@code candidate-fetch-limit=300} "first N by id" window, must still be
 * reachable by fuzzy/AI candidate search - see {@code SimpleProductCandidateFetcher} javadoc for the
 * bug this fixes. Runs against real (H2) repositories, no mocks, so the SQL shortlist queries are
 * actually exercised.
 */
@DataJpaTest
class LargeCatalogMatchingTest {

    private static final String SHOP_ID = "shop-large-catalog";
    private static final int FILLER_COUNT = 1200;

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private BrandAliasRepository brandAliasRepository;
    @Autowired
    private SupplierProductLinkRepository supplierProductLinkRepository;
    @Autowired
    private EntityManager entityManager;

    private final RowAttributeNormalizer normalizer = new RowAttributeNormalizer();
    private CandidateSearchService candidateSearchService;
    private DeterministicMatchResolver matchResolver;

    private Long chanelProductId;
    private Long diorProductId;

    @BeforeEach
    void setUp() {
        BrandNormalizer brandNormalizer = new BrandNormalizer();
        BrandAliasResolver brandAliasResolver = new BrandAliasResolver(brandAliasRepository, brandNormalizer);
        ProductCandidateFetcher fetcher = new SimpleProductCandidateFetcher(productRepository, brandAliasResolver, brandNormalizer);
        CandidateScorer scorer = new CandidateScorer(brandAliasResolver, new CriticalAttributeConflictChecker());
        SupplierImportProperties properties = new SupplierImportProperties();
        candidateSearchService = new CandidateSearchService(fetcher, normalizer, scorer, properties, brandAliasResolver);
        matchResolver = new DeterministicMatchResolver(
                supplierProductLinkRepository, productRepository, normalizer,
                new CriticalAttributeConflictChecker(), candidateSearchService);

        brandAliasRepository.save(BrandAlias.builder()
                .shopId(SHOP_ID).canonicalBrand("Chanel").alias("Chanel").normalizedAlias("chanel").build());
        brandAliasRepository.save(BrandAlias.builder()
                .shopId(SHOP_ID).canonicalBrand("Chanel").alias("Channel").normalizedAlias("channel").build());
        brandAliasRepository.save(BrandAlias.builder()
                .shopId(SHOP_ID).canonicalBrand("Chanel").alias("Шанель").normalizedAlias("шанель").build());

        // 1200 unrelated filler products, ids 1..1200 (each with a distinct brand/name so they never
        // collide with the target brand/name-token searches below).
        List<Product> filler = new ArrayList<>();
        for (int i = 0; i < FILLER_COUNT; i++) {
            filler.add(Product.builder()
                    .shopId(SHOP_ID).brand("Filler" + i).name("Generic filler item number " + i + " 30 ml")
                    .currency("RUB").build());
        }
        productRepository.saveAll(filler);
        entityManager.flush();

        // Target products saved LAST -> their ids are guaranteed to be > FILLER_COUNT, i.e. far past
        // the pre-fix default candidate-fetch-limit of 300.
        chanelProductId = productRepository.save(Product.builder()
                .shopId(SHOP_ID).brand("Chanel").name("Chanel No 5 100 ml").currency("RUB").build()).getId();
        diorProductId = productRepository.save(Product.builder()
                .shopId(SHOP_ID).brand("Dior").name("Dior Sauvage 100 ml").currency("RUB").build()).getId();
        entityManager.flush();

        assertTrue(chanelProductId > 300, "test setup must place the target product's id past the old fetch limit");
        assertTrue(diorProductId > 300, "test setup must place the target product's id past the old fetch limit");
    }

    @Test
    void chanelExactBrandAndName_autoMatchesViaSafeFingerprint_despiteIdFarPast300() {
        NormalizedRowData row = normalizeRow("Chanel", "Chanel No 5 100 ml");

        MatchResolution resolution = matchResolver.resolve(SHOP_ID, 999L, row);

        assertTrue(resolution.isResolved(), "an exact brand+name+volume match must resolve even though its id is > 1200");
        assertEquals(chanelProductId, resolution.matchedProductId());
    }

    @Test
    void channelMisspelling_surfacesChanelAsFuzzyCandidate_neverAutoMatched() {
        NormalizedRowData row = normalizeRow("Channel", "Channel No 5 100 ml");

        MatchResolution resolution = matchResolver.resolve(SHOP_ID, 999L, row);

        assertTrue(!resolution.isResolved(), "a misspelled brand must never be auto-matched, only offered as a candidate");
        assertTrue(resolution.candidates().stream().anyMatch(c -> c.productId().equals(chanelProductId)),
                "Chanel must still be found as a fuzzy candidate for a 'Channel'-branded row");
        assertTrue(resolution.candidates().stream()
                .filter(c -> c.productId().equals(chanelProductId))
                .anyMatch(c -> c.matchedAttributes().contains("brandAlias")));
    }

    @Test
    void shanelCyrillicSpelling_surfacesChanelAsFuzzyCandidate() {
        NormalizedRowData row = normalizeRow("Шанель", "Chanel No 5 100 ml");

        List<ScoredCandidate> scored = candidateSearchService.search(SHOP_ID, row);

        assertTrue(scored.stream().anyMatch(c -> c.productId().equals(chanelProductId)),
                "Cyrillic 'Шанель' must still find the Latin-spelled Chanel product");
    }

    @Test
    void diorTypo_diorr_stillSurfacedByNameTokenOverlap_despiteIdFarPast300() {
        NormalizedRowData row = normalizeRow("Diorr", "Diorr Sauvage 100 ml");

        List<ScoredCandidate> scored = candidateSearchService.search(SHOP_ID, row);

        assertTrue(scored.stream().anyMatch(c -> c.productId().equals(diorProductId)),
                "a typo'd brand ('Diorr' vs 'Dior') must still surface the real product via name-token overlap");
    }

    @Test
    void diorCyrillicTransliteration_matchesWithoutAnyConfiguredAlias() {
        NormalizedRowData row = normalizeRow("Диор", "Диор Sauvage 100 ml");

        List<ScoredCandidate> scored = candidateSearchService.search(SHOP_ID, row);

        assertTrue(scored.stream().anyMatch(c -> c.productId().equals(diorProductId)),
                "'Dior'/'Диор' is a regular letter-for-letter transliteration - no explicit BrandAlias row needed");
    }

    private NormalizedRowData normalizeRow(String brand, String name) {
        return normalizer.normalize(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, brand,
                LayoutRuleDefinition.FIELD_RAW_NAME, name));
    }
}
