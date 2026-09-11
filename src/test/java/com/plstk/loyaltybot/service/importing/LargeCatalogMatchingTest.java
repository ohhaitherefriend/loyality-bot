package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.BrandAlias;
import com.plstk.loyaltybot.entity.importing.LinkConfirmationSource;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private SupplierRepository supplierRepository;
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
                new CriticalAttributeConflictChecker(), candidateSearchService,
                brandNormalizer, brandAliasResolver);

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

    /**
     * Six-bug hardening pass regression: a single brand with MORE products than {@code
     * candidate-fetch-limit} (300) used to make {@code SimpleProductCandidateFetcher} skip its
     * name-based search step entirely, since the brand-only query alone already filled the limit -
     * permanently hiding any candidate the brand-only query's own page window happened to cut off
     * (reproduced by the report as "item #301 never becomes a candidate").
     */
    @Test
    void brandWithMoreThan300Items_stillSurfacesLateItemByName_viaCombinedBrandAndNameSearch() {
        String megaBrand = "MegaBrand";
        List<Product> megaBrandItems = new ArrayList<>();
        for (int i = 0; i < 305; i++) {
            megaBrandItems.add(Product.builder()
                    .shopId(SHOP_ID).brand(megaBrand).name(megaBrand + " Item " + i + " Distinctive" + i + " 50 ml")
                    .currency("RUB").build());
        }
        productRepository.saveAll(megaBrandItems);
        entityManager.flush();

        // The 301st item (0-indexed 300) - its name-distinctive token must still be searchable even
        // though the brand alone already has more matches than the fetch limit.
        NormalizedRowData row = normalizeRow(megaBrand, megaBrand + " Item 300 Distinctive300 50 ml");

        List<ScoredCandidate> scored = candidateSearchService.search(SHOP_ID, row);

        Long targetId = megaBrandItems.get(300).getId();
        assertTrue(scored.stream().anyMatch(c -> c.productId().equals(targetId)),
                "item #301 of a 305-item brand must still be reachable as a candidate by its distinctive name token");
    }

    /**
     * Six-bug hardening pass regression: {@code DeterministicMatchResolver}'s exact-supplier-article
     * fallback used to look up {@code Product.supplierArticle} scoped only by shop, not supplier -
     * two different suppliers coincidentally using the same internal article number for two
     * unrelated products (reproduced by the report as Dior matching Chanel as EXACT) must never be
     * silently trusted.
     */
    @Test
    void coincidentalArticleCollisionAcrossDifferentSuppliers_isNeverAutoMatchedAsExact() {
        Supplier supplierA = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier A").build());
        Supplier supplierB = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier B").build());

        Product chanel = productRepository.save(Product.builder()
                .shopId(SHOP_ID).brand("Chanel").name("Chanel No 5 100 ml")
                .supplierArticle("SAME-ARTICLE-123").currency("RUB").build());
        supplierProductLinkRepository.save(SupplierProductLink.builder()
                .shopId(SHOP_ID).supplier(supplierA).product(chanel).externalSku("SAME-ARTICLE-123")
                .confirmedSource(LinkConfirmationSource.AUTOMATIC).build());
        entityManager.flush();

        NormalizedRowData diorRowFromSupplierB = normalizer.normalize(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Dior",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Dior Sauvage 100 ml",
                LayoutRuleDefinition.FIELD_EXTERNAL_SKU, "SAME-ARTICLE-123"));

        MatchResolution resolution = matchResolver.resolve(SHOP_ID, supplierB.getId(), diorRowFromSupplierB);

        assertFalse(resolution.isResolved() && chanel.getId().equals(resolution.matchedProductId()),
                "a bare article-number coincidence from a DIFFERENT supplier must never auto-match "
                        + "onto a product already linked to another supplier");
    }

    /**
     * Follow-up to the six-bug hardening pass: the {@code SupplierProductLink}-based guard above
     * only rejects a coincidental article match when the candidate product is ALREADY linked to a
     * different supplier. It does nothing for a candidate with NO link at all yet - e.g. a legacy
     * manually catalogued product - which is exactly the scenario the follow-up report reproduced:
     * a brand-new Dior row matching an existing, never-linked Chanel product as EXACT purely
     * because the article string coincided. A required brand-identity check closes this gap.
     */
    @Test
    void coincidentalArticleCollisionAgainstNeverLinkedProduct_isNeverAutoMatchedAsExact() {
        Supplier supplierB = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier B").build());

        Product chanel = productRepository.save(Product.builder()
                .shopId(SHOP_ID).brand("Chanel").name("Chanel No 5 100 ml")
                .supplierArticle("SAME-ARTICLE-999").currency("RUB").build());
        // Deliberately NO SupplierProductLink at all for this product - e.g. it was catalogued by a
        // legacy manual import, never through the supplier-import pipeline.
        entityManager.flush();

        NormalizedRowData diorRowFromSupplierB = normalizer.normalize(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Dior",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Dior Sauvage 100 ml",
                LayoutRuleDefinition.FIELD_EXTERNAL_SKU, "SAME-ARTICLE-999"));

        MatchResolution resolution = matchResolver.resolve(SHOP_ID, supplierB.getId(), diorRowFromSupplierB);

        assertFalse(resolution.isResolved() && chanel.getId().equals(resolution.matchedProductId()),
                "a bare article-number coincidence must never auto-match across two different "
                        + "brands, even when the candidate has no SupplierProductLink at all yet");
    }

    /**
     * Confirms the brand-identity check does not break the legitimate case
     * {@code resolveViaExactSupplierArticle} exists for: a supplier's very first batch matching a
     * legacy, never-linked catalog product by article - as long as the row's own brand actually
     * agrees with the candidate's brand, it must still resolve as EXACT.
     */
    @Test
    void exactArticleMatch_againstNeverLinkedProduct_stillResolves_whenBrandsAgree() {
        Supplier supplierC = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier C").build());

        Product chanel = productRepository.save(Product.builder()
                .shopId(SHOP_ID).brand("Chanel").name("Chanel No 5 100 ml")
                .supplierArticle("LEGACY-ARTICLE-1").currency("RUB").build());
        entityManager.flush();

        NormalizedRowData chanelRowFromSupplierC = normalizer.normalize(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml",
                LayoutRuleDefinition.FIELD_EXTERNAL_SKU, "LEGACY-ARTICLE-1"));

        MatchResolution resolution = matchResolver.resolve(SHOP_ID, supplierC.getId(), chanelRowFromSupplierC);

        assertTrue(resolution.isResolved() && chanel.getId().equals(resolution.matchedProductId()),
                "a same-brand article match against a never-linked legacy product must still "
                        + "resolve as EXACT");
    }

    /**
     * ADR-029: closes the gap the brand-identity check (ADR-028) left open - two DIFFERENT products
     * of the SAME brand ("Coco Mademoiselle" vs "No 5") sharing a coincidental supplier article
     * must never auto-match as EXACT just because brand agrees and
     * {@link CriticalAttributeConflictChecker} finds no conflicting volume/concentration/shade/
     * tester/set (it never compares the line/name text itself). Reproduced verbatim by the report:
     * a "Chanel Coco Mademoiselle 100 ml" row matched an existing, never-linked "Chanel No 5 100
     * ml" product as EXACT.
     */
    @Test
    void sameBrandDifferentLineArticleCollision_isNeverAutoMatchedAsExact() {
        Supplier supplierD = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier D").build());

        Product noFive = productRepository.save(Product.builder()
                .shopId(SHOP_ID).brand("Chanel").name("Chanel No 5 100 ml")
                .supplierArticle("SHARED-ARTICLE-777").currency("RUB").build());
        // Deliberately NO SupplierProductLink at all - a legacy manually catalogued product.
        entityManager.flush();

        NormalizedRowData cocoMademoiselleRow = normalizer.normalize(Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel Coco Mademoiselle 100 ml",
                LayoutRuleDefinition.FIELD_EXTERNAL_SKU, "SHARED-ARTICLE-777"));

        MatchResolution resolution = matchResolver.resolve(SHOP_ID, supplierD.getId(), cocoMademoiselleRow);

        assertFalse(resolution.isResolved() && noFive.getId().equals(resolution.matchedProductId()),
                "a shared article number under the SAME brand must never auto-match two different "
                        + "product lines ('Coco Mademoiselle' vs 'No 5') as EXACT");
    }

    /**
     * ADR-029: reproduces the report's exact second scenario - 305 same-brand filler products
     * (each containing the digit substrings "5"/"50" that previously diluted the bounded,
     * ranked candidate pool past its limit) plus the real target already in the catalog. The
     * target must still auto-resolve as EXACT via the new unbounded, brand-scoped safe-fingerprint
     * stage - simply increasing {@code candidateFetchLimit} would not fix this, since the fix does
     * not depend on any bounded query at all for this deterministic decision.
     */
    @Test
    void brandWithMoreThan300Items_targetStillAutoMatchesExact_viaUnboundedSafeFingerprint() {
        List<Product> filler = new ArrayList<>();
        for (int i = 0; i < 305; i++) {
            filler.add(Product.builder()
                    .shopId(SHOP_ID).brand("Chanel").name("Chanel Coco Mademoiselle " + i + " 50 ml")
                    .currency("RUB").build());
        }
        productRepository.saveAll(filler);
        entityManager.flush();

        NormalizedRowData row = normalizeRow("Chanel", "Chanel No 5 100 ml");

        MatchResolution resolution = matchResolver.resolve(SHOP_ID, 999L, row);

        assertTrue(resolution.isResolved(),
                "the exact target must still auto-match even though its brand now has 306 products "
                        + "and 305 of them contain noisy digit substrings ('50') overlapping the "
                        + "target's own significant tokens ('5', '100')");
        assertEquals(chanelProductId, resolution.matchedProductId());
    }
}
