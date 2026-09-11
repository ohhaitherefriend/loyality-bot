package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.PriceRoundingPolicy;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.ShopSettingsRepository;
import com.plstk.loyaltybot.repository.SupplierOfferRepository;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the Prompt 06 apply/reconciliation stage: {@link ImportBatchApplyService}
 * + {@link ImportBatchApplyWriter} + {@link CatalogAvailabilityService}. Covers offer upsert
 * (price/rounding/commission), FULL-vs-DELTA reconciliation, snapshotScope isolation,
 * disappearance/reactivation, multi-supplier availability, {@code manualHidden}, double/concurrent
 * apply idempotency, transactional rollback on error, and automatic resume after a simulated
 * restart.
 */
@DataJpaTest
@Import(ImportBatchApplyServiceTest.TestConfig.class)
class ImportBatchApplyServiceTest {

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
    private SupplierProductLinkRepository supplierProductLinkRepository;
    @Autowired
    private ShopSettingsRepository shopSettingsRepository;
    @Autowired
    private ImportBatchApplyService importBatchApplyService;
    @Autowired
    private RowAttributeNormalizer rowAttributeNormalizer;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    private Supplier supplier;

    @BeforeEach
    void setUp() {
        supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Test Supplier").build());
        entityManager.flush();
    }

    @Test
    void newProduct_isCreatedHiddenThenBecomesVisible_withCommissionAdjustedPrice() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-1", null, "100.00", "Brand", 5), null, "New Perfume 100 ml");
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.APPLIED, batch.getStatus());
        assertEquals(1, batch.getOffersAddedCount());
        assertEquals(0, batch.getOffersUpdatedCount());

        List<Product> products = productRepository.findAll();
        assertEquals(1, products.size());
        Product product = products.get(0);
        assertTrue(product.getVisible(), "a new product with an active offer and no manualHidden must be visible");
        assertEquals(new BigDecimal("130.00"), product.getSalePrice());

        SupplierOffer offer = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_A, supplier.getId(), source.getSnapshotScope(), product.getId())
                .orElseThrow();
        assertTrue(offer.getActive());
        assertEquals(new BigDecimal("100.00"), offer.getSupplierPrice());
        assertEquals(new BigDecimal("30.00"), offer.getAppliedCommissionPercent());
        assertEquals(new BigDecimal("130.00"), offer.getCalculatedSitePrice());
        assertEquals(5, offer.getStockQuantity());
        assertEquals(batchId, offer.getLastSeenBatch().getId());

        Optional<SupplierProductLink> link = supplierProductLinkRepository
                .findByShopIdAndSupplierIdAndExternalSku(SHOP_A, supplier.getId(), "SKU-1");
        assertTrue(link.isPresent(), "a safe automatic decision must persist a SupplierProductLink");
        assertEquals(product.getId(), link.get().getProduct().getId());

        ImportRow row = onlyRow(batchId);
        assertEquals(ImportRowStatus.APPLIED, row.getStatus());
        assertEquals(product.getId(), row.getMatchedProduct().getId());
    }

    @Test
    void newProduct_duplicateRowWithinSameBatch_reusesJustCreatedProduct_neverCreatesTwo() {
        // ADR-030 (Section 5): two rows of the SAME apply batch, both decided NEW_PRODUCT during
        // matching (matchedProduct == null), with an identical brand+fingerprint (e.g. a duplicate
        // line in the supplier's own file) - the second row must reuse the product the first row
        // just created within this same transaction, never create a sibling duplicate.
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", BigDecimal.ZERO, PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        String sharedFingerprint = fingerprintFor("Nivea", "Nivea Cream 100 ml");
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalizedWithFingerprint("SKU-1", "100.00", "Nivea", sharedFingerprint), null, "Nivea Cream 100 ml");
        addRow(batchId, normalizedWithFingerprint("SKU-2", "100.00", "Nivea", sharedFingerprint), null, "Nivea Cream 100 ml");
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        List<Product> products = productRepository.findAll();
        assertEquals(1, products.size(), "a duplicate row for the same product identity must never create a second Product");

        List<ImportRow> rows = importRowRepository.findByImportBatchId(batchId);
        assertEquals(2, rows.size());
        assertEquals(products.get(0).getId(), rows.get(0).getMatchedProduct().getId());
        assertEquals(products.get(0).getId(), rows.get(1).getMatchedProduct().getId());
    }

    @Test
    void newProduct_identicalFingerprintAlreadyAppliedByEarlierBatch_reusesExistingProduct_neverCreatesDuplicate() {
        // ADR-030 (Section 5): the NEW_PRODUCT decision for a row is made during the earlier
        // MATCHING stage, against the catalog as it existed then. If a DIFFERENT, independently
        // decided batch (e.g. from a different supplier, or a retried duplicate submission) already
        // applied and created an identical product by the time THIS batch's apply actually runs,
        // apply-time re-verification must catch it and reuse the existing product instead of
        // creating a duplicate that would otherwise silently double the catalog.
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", BigDecimal.ZERO, PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        String sharedFingerprint = fingerprintFor("Nivea", "Nivea Cream 100 ml");

        Long firstBatchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(firstBatchId, normalizedWithFingerprint("SKU-1", "100.00", "Nivea", sharedFingerprint), null, "Nivea Cream 100 ml");
        flushClear();
        importBatchApplyService.applyNewly(firstBatchId);
        flushClear();
        assertEquals(1, productRepository.findAll().size());
        Long firstProductId = productRepository.findAll().get(0).getId();

        Long secondBatchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(secondBatchId, normalizedWithFingerprint("SKU-2", "100.00", "Nivea", sharedFingerprint), null, "Nivea Cream 100 ml");
        flushClear();
        importBatchApplyService.applyNewly(secondBatchId);
        flushClear();

        List<Product> products = productRepository.findAll();
        assertEquals(1, products.size(), "a second, independently-decided NEW_PRODUCT for an identical "
                + "fingerprint must never create a duplicate once one already exists");
        assertEquals(firstProductId, products.get(0).getId());

        ImportRow secondRow = onlyRow(secondBatchId);
        assertEquals(ImportRowStatus.APPLIED, secondRow.getStatus());
        assertEquals(firstProductId, secondRow.getMatchedProduct().getId());
    }

    /**
     * ADR-031 (Section 1, scenario B / Section 3): the catalog ALREADY contains two structurally
     * identical products (e.g. a historical duplicate-data situation) when a row that was decided
     * {@code NEW_PRODUCT} during the earlier matching stage reaches apply. Apply-time
     * re-verification must recognize this as {@code ProductCreationCheck.Ambiguous}, never collapse
     * "zero matches" and "multiple matches" into the same "OK to create" outcome - the whole batch
     * must abort (roll back, no partial storefront update) rather than silently creating a THIRD
     * duplicate product or auto-picking either existing one.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void newProduct_apply_findsTwoExistingIdenticalProducts_abortsBatch_neverCreatesThirdDuplicate() {
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", BigDecimal.ZERO, PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        String sharedFingerprint = fingerprintFor("Nivea", "Nivea Cream 100 ml");
        // Two pre-existing catalog products, structurally identical to each other and to the
        // incoming row - a historical duplicate-data situation, exactly as the report describes.
        Product duplicateA = productRepository.save(Product.builder()
                .shopId(SHOP_A).brand("Nivea").name("Nivea Cream 100 ml").currency("RUB").build());
        Product duplicateB = productRepository.save(Product.builder()
                .shopId(SHOP_A).brand("Nivea").name("Nivea Cream 100 ml").currency("RUB").build());
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalizedWithFingerprint("SKU-NEW", "100.00", "Nivea", sharedFingerprint), null, "Nivea Cream 100 ml");
        flushClear();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        importBatchApplyService.applyNewly(batchId);

        TestTransaction.start();
        assertEquals(ImportBatchStatus.FAILED, reloadBatch(batchId).getStatus(),
                "an ambiguous apply-time re-verification must abort/fail the whole batch, never partially apply");
        assertEquals(2, productRepository.findAll().size(),
                "an ambiguous match at apply time must never create a third duplicate product");
        assertTrue(supplierOfferRepository.findAll().isEmpty(),
                "no offer must be persisted when the batch aborts on ambiguity");
        Product reloadedA = reloadProduct(duplicateA.getId());
        Product reloadedB = reloadProduct(duplicateB.getId());
        assertFalse(reloadedA.getVisible(), "an ambiguous decision must never auto-select either existing product either");
        assertFalse(reloadedB.getVisible());
    }

    /**
     * ADR-031 (Section 1, scenario C / Section 5): a row's {@code normalizedData} was persisted by
     * an OLDER {@code RowAttributeNormalizer} version (simulated here with an explicit stale
     * {@code normalizationVersion} and a fingerprint format that would never equal the CURRENT
     * algorithm's output) before reaching apply as a {@code NEW_PRODUCT} decision. Apply must never
     * trust the stale fingerprint as-is (which would find "no match" and create a duplicate) -
     * {@code refreshIfStale} recomputes fresh from the row's own persisted raw data using the
     * current algorithm, correctly finding the already-existing product and reusing it.
     */
    @Test
    void newProduct_apply_staleNormalizationVersion_recomputesFresh_reusesExistingProduct_neverDuplicates() {
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", BigDecimal.ZERO, PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product existing = productRepository.save(Product.builder()
                .shopId(SHOP_A).brand("Chanel").name("Chanel No 5 100 ml").currency("RUB").build());
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        // A stale, pre-upgrade normalizedData blob: old normalizationVersion + a fingerprint format
        // that could never equal the current algorithm's output for this same product - simulating
        // a row that sat AUTO_APPROVED across a normalization-version upgrade. rawData still has
        // the genuine raw brand/name the row was parsed from, so refreshIfStale can recompute.
        NormalizedRowData staleNormalized = new NormalizedRowData(
                "Chanel", "old-line-format", null, new BigDecimal("100"), "ml", null, null, false, false,
                "SKU-STALE", null, new BigDecimal("100.00"), null,
                "chanel old-line-format", "chanel|old-line-format|100|ml|||false|false", 1);
        java.util.Map<String, String> raw = java.util.Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml");
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1)
                .rawData(toJson(raw)).normalizedData(toJson(staleNormalized))
                .status(ImportRowStatus.AUTO_APPROVED).build());
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        assertEquals(ImportBatchStatus.APPLIED, reloadBatch(batchId).getStatus());
        List<Product> products = productRepository.findAll();
        assertEquals(1, products.size(),
                "a stale-version NEW_PRODUCT row must reuse the existing product once refreshed, never duplicate it");
        assertEquals(existing.getId(), products.get(0).getId());

        ImportRow appliedRow = onlyRow(batchId);
        assertEquals(ImportRowStatus.APPLIED, appliedRow.getStatus());
        assertEquals(existing.getId(), appliedRow.getMatchedProduct().getId());
    }

    /**
     * ADR-031 (Section 5): a row already matched to a specific product (EXACT/AI_MATCH, decided
     * during the earlier matching stage) whose {@code normalizedData} is stale-version must be
     * re-verified against fresh raw data before its stored decision is trusted at apply time - if
     * the current algorithm/alias config no longer agrees, the whole batch aborts for review
     * rather than silently applying an offer against what may now be the wrong product.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void matchedRow_staleNormalizationVersion_noLongerAgreesWithFreshData_abortsForReview() {
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", BigDecimal.ZERO, PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        // The row was matched (during an earlier matching run) to THIS product - but that product's
        // real brand/name ("Dior Sauvage 100 ml") structurally disagrees with what fresh
        // re-normalization of the row's own raw data ("Chanel No 5 100 ml") produces below.
        Product wronglyMatched = productRepository.save(Product.builder()
                .shopId(SHOP_A).brand("Dior").name("Dior Sauvage 100 ml").currency("RUB").build());
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        NormalizedRowData staleNormalized = new NormalizedRowData(
                "Chanel", "old-line-format", null, new BigDecimal("100"), "ml", null, null, false, false,
                "SKU-STALE-MATCHED", null, new BigDecimal("100.00"), null,
                "chanel old-line-format", "chanel|old-line-format|100|ml|||false|false", 1);
        java.util.Map<String, String> raw = java.util.Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml");
        ImportRow row = ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1)
                .rawData(toJson(raw)).normalizedData(toJson(staleNormalized))
                .status(ImportRowStatus.AUTO_APPROVED).build();
        row.setMatchedProduct(wronglyMatched);
        importRowRepository.save(row);
        flushClear();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        importBatchApplyService.applyNewly(batchId);

        TestTransaction.start();
        assertEquals(ImportBatchStatus.FAILED, reloadBatch(batchId).getStatus(),
                "a stale-version matched decision that no longer agrees with fresh data must abort the batch");
        assertTrue(supplierOfferRepository.findAll().isEmpty(),
                "no offer must be persisted against a match that no longer safely agrees");
        assertEquals(1, productRepository.findAll().size(), "no new/duplicate product must be created either");
    }

    /**
     * ADR-031 (Section 5): the counterpart safe case - a stale-version matched row that, once
     * re-normalized fresh from raw data, STILL genuinely agrees with the matched product, must
     * apply normally (never spuriously rejected just because the stored version number is old).
     */
    @Test
    void matchedRow_staleNormalizationVersion_stillAgreesWithFreshData_appliesNormally() {
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", BigDecimal.ZERO, PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product correctlyMatched = productRepository.save(Product.builder()
                .shopId(SHOP_A).brand("Chanel").name("Chanel No 5 100 ml").currency("RUB").build());
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        NormalizedRowData staleNormalized = new NormalizedRowData(
                "Chanel", "old-line-format", null, new BigDecimal("100"), "ml", null, null, false, false,
                "SKU-STALE-BUT-OK", null, new BigDecimal("150.00"), null,
                "chanel old-line-format", "chanel|old-line-format|100|ml|||false|false", 1);
        java.util.Map<String, String> raw = java.util.Map.of(
                LayoutRuleDefinition.FIELD_BRAND, "Chanel",
                LayoutRuleDefinition.FIELD_RAW_NAME, "Chanel No 5 100 ml");
        ImportRow row = ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1)
                .rawData(toJson(raw)).normalizedData(toJson(staleNormalized))
                .status(ImportRowStatus.AUTO_APPROVED).build();
        row.setMatchedProduct(correctlyMatched);
        importRowRepository.save(row);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        assertEquals(ImportBatchStatus.APPLIED, reloadBatch(batchId).getStatus(),
                "a stale-version match that still genuinely agrees with fresh data must apply normally");
        assertEquals(1, productRepository.findAll().size());
        ImportRow appliedRow = onlyRow(batchId);
        assertEquals(ImportRowStatus.APPLIED, appliedRow.getStatus());
        assertEquals(correctlyMatched.getId(), appliedRow.getMatchedProduct().getId());
    }

    @Test
    void existingOfferSamePriceAndStock_isCountedUnchanged() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product product = saveProduct("Existing", false);
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).snapshotScope(source.getSnapshotScope()).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).stockQuantity(5).active(true).build());
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-1", null, "100.00", "Existing", 5), product, null);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(1, batch.getOffersUpdatedCount());
        assertEquals(0, batch.getOffersPriceChangedCount());
        assertEquals(1, batch.getOffersUnchangedCount());
    }

    @Test
    void existingOfferPriceChange_incrementsPriceChangedCounter_andRecalculatesSalePrice() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product product = saveProduct("Existing", false);
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).snapshotScope(source.getSnapshotScope()).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).stockQuantity(5).active(true).build());
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-1", null, "200.00", "Existing", 8), product, null);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(1, batch.getOffersPriceChangedCount());
        assertEquals(0, batch.getOffersUnchangedCount());

        SupplierOffer offer = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_A, supplier.getId(), source.getSnapshotScope(), product.getId())
                .orElseThrow();
        assertEquals(new BigDecimal("260.00"), offer.getCalculatedSitePrice());
        assertEquals(8, offer.getStockQuantity());
        assertEquals(new BigDecimal("260.00"), reloadProduct(product.getId()).getSalePrice());
    }

    @Test
    void fullSnapshot_disappearedOffer_deactivatesAndHidesProduct() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product product = saveProduct("Disappearing", true);
        SupplierOffer existingOffer = supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).snapshotScope(source.getSnapshotScope()).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).stockQuantity(5).active(true).build());
        flushClear();

        // Next FULL batch from the same supplier+scope no longer contains this product at all.
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.APPLIED, batch.getStatus());
        assertEquals(1, batch.getProductsRemovedFromStorefrontCount());

        SupplierOffer reloadedOffer = supplierOfferRepository.findById(existingOffer.getId()).orElseThrow();
        assertFalse(reloadedOffer.getActive());
        assertTrue(reloadedOffer.getDeactivatedAt() != null);

        Product reloadedProduct = reloadProduct(product.getId());
        assertFalse(reloadedProduct.getVisible(), "product with zero active offers must not be visible on storefront");
    }

    /**
     * Six-bug hardening pass regression: a FULL snapshot batch that has one row present in the file
     * but which failed processing (e.g. an invalid price, reproduced by the report as "two rows
     * apply, a third becomes INVALID") must NOT deactivate/hide that product - only a product
     * genuinely absent from the file (no row referencing it at all) may be deactivated.
     */
    @Test
    void fullSnapshot_rowPresentButInvalid_isProtectedFromDeactivation_onlyGenuinelyAbsentOffersDeactivate() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product presentButRowFailed = saveProduct("PresentButRowFailed", true);
        Product genuinelyAbsent = saveProduct("GenuinelyAbsent", true);

        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).snapshotScope(source.getSnapshotScope())
                .product(presentButRowFailed).externalSku("SKU-BAD-PRICE")
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).stockQuantity(5).active(true).build());
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).snapshotScope(source.getSnapshotScope())
                .product(genuinelyAbsent).externalSku("SKU-ABSENT")
                .supplierPrice(new BigDecimal("50.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("65.00")).stockQuantity(3).active(true).build());
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        // This row IS in the file (its raw externalSku is recorded) but its price failed validation
        // at parse time, so it never reached AUTO_APPROVED/matching - "genuinelyAbsent" has no row
        // at all this batch, i.e. it truly disappeared from the supplier's snapshot.
        addInvalidRowWithRawExternalSku(batchId, "SKU-BAD-PRICE");
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.APPLIED, batch.getStatus());
        assertEquals(1, batch.getProductsRemovedFromStorefrontCount(),
                "only the genuinely-absent product must be removed from the storefront");
        assertEquals(1, batch.getOffersProtectedFromDeactivationCount(),
                "the row-failed-but-present offer must be counted as protected, not silently untouched");

        assertTrue(reloadProduct(presentButRowFailed.getId()).getVisible(),
                "a product whose row failed processing (but is present in the file by identifier) must stay visible");
        assertFalse(reloadProduct(genuinelyAbsent.getId()).getVisible(),
                "a product genuinely absent from the file must still be deactivated/hidden");
    }

    @Test
    void deltaSnapshot_neverDeactivatesMissingOffers() {
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product product = saveProduct("StaysActive", true);
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).snapshotScope(source.getSnapshotScope()).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).stockQuantity(5).active(true).build());
        flushClear();

        // DELTA batch says nothing about this product (e.g. it wasn't part of this partial update).
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(0, batch.getProductsRemovedFromStorefrontCount());
        SupplierOffer offer = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_A, supplier.getId(), source.getSnapshotScope(), product.getId())
                .orElseThrow();
        assertTrue(offer.getActive(), "DELTA snapshots must never deactivate offers outside their own rows");
        assertTrue(reloadProduct(product.getId()).getVisible());
    }

    @Test
    void reappearedOffer_automaticallyReturnsProductToStorefront() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        // Simulate a product that was previously hidden because its only offer had gone stale/inactive.
        Product product = saveProduct("Reappearing", false);
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        // Deterministic matching already resolved this row to the existing (hidden) product.
        addRow(batchId, normalized("SKU-1", null, "150.00", "Reappearing", 3), product, null);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(1, batch.getProductsReactivatedCount());
        Product reloaded = reloadProduct(product.getId());
        assertTrue(reloaded.getVisible(), "a reappearing offer must automatically make the product visible again");
        assertEquals(new BigDecimal("195.00"), reloaded.getSalePrice());
    }

    @Test
    void scopeIsolation_fullSnapshotOfOneScope_doesNotTouchOffersInAnotherScope_sameSupplier() {
        SupplierSource sourceA = saveSource(SnapshotMode.FULL, "SCOPE_A", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        SupplierSource sourceB = saveSource(SnapshotMode.FULL, "SCOPE_B", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);

        Product productA = saveProduct("InScopeA", true);
        Product productB = saveProduct("InScopeB", true);
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(sourceA).snapshotScope(sourceA.getSnapshotScope()).product(productA)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).active(true).build());
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(sourceB).snapshotScope(sourceB.getSnapshotScope()).product(productB)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).active(true).build());
        flushClear();

        // A FULL snapshot for scope A that no longer lists productA must never deactivate scope B's offer.
        Long batchId = createBatch(sourceA, ImportBatchStatus.AUTO_APPROVED);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        assertFalse(reloadProduct(productA.getId()).getVisible(), "scope A's own stale offer must be deactivated");
        assertTrue(reloadProduct(productB.getId()).getVisible(), "scope B must be completely unaffected by scope A's apply");
    }

    /**
     * Stage 3 regression: the SAME product supplied under two independent {@code snapshotScope}s of
     * the SAME supplier (e.g. two category price-list files) must persist as two distinct
     * {@code SupplierOffer} rows. Before the (shop, supplier, snapshotScope, product) identity fix,
     * a bare (shop, supplier, product) key made the second scope's apply silently overwrite/steal the
     * first scope's offer row, so scope A's own FULL reconciliation could no longer find "its" offer
     * and either wrongly deactivated the wrong scope's data or lost track of the product entirely.
     */
    @Test
    void sameProductInTwoScopes_ofSameSupplier_areIndependentOffersThatDoNotStealEachOther() {
        SupplierSource sourceA = saveSource(SnapshotMode.FULL, "SCOPE_A", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        SupplierSource sourceB = saveSource(SnapshotMode.FULL, "SCOPE_B", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product shared = saveProduct("SharedAcrossScopes", true);

        Long batchA = createBatch(sourceA, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchA, normalized("SKU-A", null, "100.00", "Brand", 5), shared, null);
        flushClear();
        importBatchApplyService.applyNewly(batchA);
        flushClear();

        Long batchB = createBatch(sourceB, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchB, normalized("SKU-B", null, "200.00", "Brand", 3), shared, null);
        flushClear();
        importBatchApplyService.applyNewly(batchB);
        flushClear();

        assertEquals(2, supplierOfferRepository.findByShopIdAndProductIdAndActiveTrue(SHOP_A, shared.getId()).size(),
                "two independent scopes of the same supplier for the same product must be two distinct offer rows");

        SupplierOffer offerA = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_A, supplier.getId(), "SCOPE_A", shared.getId())
                .orElseThrow();
        SupplierOffer offerB = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_A, supplier.getId(), "SCOPE_B", shared.getId())
                .orElseThrow();
        assertTrue(offerA.getActive());
        assertTrue(offerB.getActive());
        assertEquals(new BigDecimal("100.00"), offerA.getSupplierPrice());
        assertEquals(new BigDecimal("200.00"), offerB.getSupplierPrice());

        // Scope A's next FULL snapshot no longer lists the product: only scope A's own offer must
        // deactivate. Scope B's offer (and therefore the product's storefront visibility) is untouched.
        Long batchA2 = createBatch(sourceA, ImportBatchStatus.AUTO_APPROVED);
        flushClear();
        importBatchApplyService.applyNewly(batchA2);
        flushClear();

        SupplierOffer offerAAfter = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_A, supplier.getId(), "SCOPE_A", shared.getId())
                .orElseThrow();
        SupplierOffer offerBAfter = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_A, supplier.getId(), "SCOPE_B", shared.getId())
                .orElseThrow();
        assertFalse(offerAAfter.getActive(), "scope A's offer must deactivate when scope A's snapshot no longer lists it");
        assertTrue(offerBAfter.getActive(), "scope B's offer must be completely unaffected by scope A's apply");
        assertTrue(reloadProduct(shared.getId()).getVisible(), "product stays visible via scope B's still-active offer");
    }

    @Test
    void multiSupplierAvailability_oneSupplierDeactivated_productStaysVisibleViaOtherSupplier() {
        Supplier supplier2 = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier Two").build());
        SupplierSource source1 = saveSource(supplier, SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        SupplierSource source2 = saveSource(supplier2, SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);

        Product product = saveProduct("SharedAcrossSuppliers", true);
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source1).snapshotScope(source1.getSnapshotScope()).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("100.00")).active(true).build());
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier2).supplierSource(source2).snapshotScope(source2.getSnapshotScope()).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("200.00")).active(true).build());
        flushClear();

        // Supplier one's FULL snapshot no longer lists this product - only supplier one's offer goes away.
        Long batchId = createBatch(source1, ImportBatchStatus.AUTO_APPROVED);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(0, batch.getProductsRemovedFromStorefrontCount(), "the other supplier keeps the product available");

        Product reloaded = reloadProduct(product.getId());
        assertTrue(reloaded.getVisible());
        assertEquals(new BigDecimal("200.00"), reloaded.getSalePrice(), "public price recalculated to the sole remaining active offer");
    }

    @Test
    void wholeUnitRounding_roundsToNearestIntegerCurrencyUnit() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", BigDecimal.ZERO, PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-1", null, "36.335", "Brand", null), null, "Rounded Whole");
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        SupplierOffer offer = onlyOfferForBatch(batchId);
        assertEquals(new BigDecimal("36.00"), offer.getCalculatedSitePrice());
    }

    @Test
    void minorUnitRounding_roundsToCents() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", BigDecimal.ZERO, PriceRoundingPolicy.MINOR_UNIT_HALF_UP);
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-1", null, "36.335", "Brand", null), null, "Rounded Minor");
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        SupplierOffer offer = onlyOfferForBatch(batchId);
        assertEquals(new BigDecimal("36.34"), offer.getCalculatedSitePrice());
    }

    @Test
    void shopDefaultCommission_isUsed_whenSourceHasNoOverride() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", null, PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        shopSettingsRepository.save(ShopSettings.builder().shopId(SHOP_A).defaultCommissionPercent(new BigDecimal("20.00")).build());
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-1", null, "100.00", "Brand", null), null, "Shop Default Commission");
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        SupplierOffer offer = onlyOfferForBatch(batchId);
        assertEquals(new BigDecimal("20.00"), offer.getAppliedCommissionPercent());
        assertEquals(new BigDecimal("120.00"), offer.getCalculatedSitePrice());
    }

    @Test
    void manualHidden_isNeverClearedBySync_evenWithAnActiveOffer() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product product = productRepository.save(Product.builder()
                .shopId(SHOP_A).name("Manually Hidden").currency("RUB").visible(false).manualHidden(true).build());
        flushClear();

        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-1", null, "100.00", "Brand", 5), product, null);
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();

        Product reloaded = reloadProduct(product.getId());
        assertFalse(reloaded.getVisible(), "an explicit manualHidden override must survive automatic sync");
        assertTrue(reloaded.getManualHidden());
        // The active offer/public price still gets computed even while hidden.
        assertEquals(new BigDecimal("130.00"), reloaded.getSalePrice());
    }

    @Test
    void doubleApply_secondCallOnAlreadyAppliedBatch_isCompleteNoOp() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-1", null, "100.00", "Brand", 5), null, "Double Apply");
        flushClear();

        importBatchApplyService.applyNewly(batchId);
        flushClear();
        assertEquals(ImportBatchStatus.APPLIED, reloadBatch(batchId).getStatus());
        assertEquals(1, supplierOfferRepository.findAll().size());

        // Simulates a racing second scheduler tick / manual re-trigger hitting the same batch.
        importBatchApplyService.applyNewly(batchId);
        flushClear();

        assertEquals(ImportBatchStatus.APPLIED, reloadBatch(batchId).getStatus());
        assertEquals(1, supplierOfferRepository.findAll().size(), "a second apply attempt must never create a duplicate offer");
        assertEquals(1, productRepository.findAll().size(), "a second apply attempt must never create a duplicate product");
    }

    @Test
    // This test commits real data mid-test (see below) to exercise a genuine cross-transaction
    // rollback, so the shared in-memory H2 context must be recreated afterwards to keep other test
    // methods in this class isolated from that committed data.
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void applyFailure_rollsBackTransaction_andMarksBatchFailed() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Long batchId = createBatch(source, ImportBatchStatus.AUTO_APPROVED);
        addRow(batchId, normalized("SKU-VALID", null, "100.00", "Brand", 5), null, "Should Roll Back");
        // A row that reached apply with a null supplierPrice is an invariant violation - the writer
        // must throw rather than silently applying a bad row, and the whole batch must roll back.
        addInvalidRow(batchId);
        flushClear();

        // @DataJpaTest wraps the whole test method in one transaction by default, which would hide
        // this test's point entirely: ImportBatchApplyService is deliberately NOT @Transactional, so
        // in production claimForApplying/applyBatch/finalizeFailed each run in their OWN separate
        // physical transaction (see ImportBatchApplyService). Committing the setup and ending the
        // test transaction here lets those calls behave exactly as they do in production, so the
        // mid-apply rollback is real (and durable) instead of merely deferred to test teardown.
        TestTransaction.flagForCommit();
        TestTransaction.end();

        importBatchApplyService.applyNewly(batchId);

        TestTransaction.start();
        assertEquals(ImportBatchStatus.FAILED, reloadBatch(batchId).getStatus());
        assertTrue(supplierOfferRepository.findAll().isEmpty(), "no offer must be persisted when the apply transaction rolls back");
        assertTrue(productRepository.findByShopId(SHOP_A, Pageable.unpaged()).isEmpty(),
                "no product must be persisted when the apply transaction rolls back");
    }

    @Test
    void resumeApplying_afterSimulatedRestart_completesTheStuckBatch() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        // Batch is already APPLYING (as if a previous process crashed right after the claim
        // committed but before the apply itself ran) - rows are still untouched (AUTO_APPROVED).
        Long batchId = createBatch(source, ImportBatchStatus.APPLYING);
        addRow(batchId, normalized("SKU-1", null, "100.00", "Brand", 5), null, "Resumed After Restart");
        flushClear();

        importBatchApplyService.resumeApplying(batchId);
        flushClear();

        ImportBatch batch = reloadBatch(batchId);
        assertEquals(ImportBatchStatus.APPLIED, batch.getStatus());
        assertEquals(1, batch.getOffersAddedCount());
        assertEquals(ImportRowStatus.APPLIED, onlyRow(batchId).getStatus());
    }

    // ===== helpers =====

    private SupplierSource saveSource(
            SnapshotMode mode, String scope, BigDecimal commissionOverride, PriceRoundingPolicy roundingPolicy) {
        return saveSource(supplier, mode, scope, commissionOverride, roundingPolicy);
    }

    private SupplierSource saveSource(
            Supplier forSupplier, SnapshotMode mode, String scope, BigDecimal commissionOverride, PriceRoundingPolicy roundingPolicy) {
        return supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(forSupplier).label("main-" + System.nanoTime())
                .snapshotMode(mode).snapshotScope(scope)
                .autoApply(true).shadowMode(false)
                .commissionPercentOverride(commissionOverride)
                .roundingPolicy(roundingPolicy)
                .build());
    }

    private Product saveProduct(String name, boolean visible) {
        return productRepository.save(Product.builder()
                .shopId(SHOP_A).name(name).currency("RUB").visible(visible).build());
    }

    private Long createBatch(SupplierSource source, ImportBatchStatus status) {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_A).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(10L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_A).supplierSource(source).importFile(importFile)
                .status(status).attemptNumber(1).build());
        entityManager.flush();
        return batch.getId();
    }

    private ImportRow addRow(Long batchId, NormalizedRowData normalized, Product matchedProduct, String rawName) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        String rawData = rawName != null ? toJson(java.util.Map.of("rawName", rawName)) : "{}";
        ImportRow row = ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1).rawData(rawData)
                .normalizedData(toJson(normalized)).status(ImportRowStatus.AUTO_APPROVED).build();
        if (matchedProduct != null) {
            row.setMatchedProduct(matchedProduct);
        }
        return importRowRepository.save(row);
    }

    private void addInvalidRow(Long batchId) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(2).rawData("{}")
                .normalizedData(toJson(normalized("SKU-INVALID", null, null, "Brand", null)))
                .status(ImportRowStatus.AUTO_APPROVED).build());
    }

    /**
     * Mirrors what {@code SpreadsheetParser}/{@code ImportBatchParseWriter} actually persist for a
     * row that failed validation (e.g. bad price): {@code rawData} still carries the raw {@code
     * externalSku} that was in the file, but the row's status is {@code INVALID} and it never has
     * {@code normalizedData} - it never reached matching/normalization at all.
     */
    private void addInvalidRowWithRawExternalSku(Long batchId, String externalSku) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(2)
                .rawData(toJson(java.util.Map.of(LayoutRuleDefinition.FIELD_EXTERNAL_SKU, externalSku)))
                .status(ImportRowStatus.INVALID).build());
    }

    private NormalizedRowData normalized(String externalSku, String barcode, String price, String brand, Integer stock) {
        return new NormalizedRowData(
                brand, "line", null, new BigDecimal("100"), "ml", null, null, false, false,
                externalSku, barcode, price != null ? new BigDecimal(price) : null, stock,
                (brand == null ? "" : brand.toLowerCase()) + " line", "fp-" + externalSku,
                RowAttributeNormalizer.NORMALIZATION_VERSION);
    }

    /**
     * The REAL fingerprint {@link RowAttributeNormalizer} would compute for a catalog product with
     * this brand+name - used so the ADR-030 apply-time re-verification tests below compare against
     * exactly what {@link ImportBatchApplyWriter#resolveProduct} recomputes for the freshly-created
     * {@code Product} (same brand/name), rather than an arbitrary literal that would never actually
     * match the live algorithm's output.
     */
    private String fingerprintFor(String brand, String rawName) {
        java.util.Map<String, String> raw = new java.util.HashMap<>();
        raw.put(LayoutRuleDefinition.FIELD_BRAND, brand);
        raw.put(LayoutRuleDefinition.FIELD_RAW_NAME, rawName);
        return rowAttributeNormalizer.normalize(SHOP_A, raw).fingerprint();
    }

    /** Like {@link #normalized}, but with an explicit, independently-controlled fingerprint - used
     * to simulate two DIFFERENT rows (different externalSku) that are nonetheless the SAME real
     * product identity (ADR-030 Section 5 apply-time re-verification tests below). */
    private NormalizedRowData normalizedWithFingerprint(String externalSku, String price, String brand, String fingerprint) {
        return new NormalizedRowData(
                brand, "line", null, new BigDecimal("100"), "ml", null, null, false, false,
                externalSku, null, price != null ? new BigDecimal(price) : null, null,
                (brand == null ? "" : brand.toLowerCase()) + " line", fingerprint,
                RowAttributeNormalizer.NORMALIZATION_VERSION);
    }

    private ImportBatch reloadBatch(Long batchId) {
        return importBatchRepository.findById(batchId).orElseThrow();
    }

    private Product reloadProduct(Long productId) {
        return productRepository.findByShopIdAndId(SHOP_A, productId).orElseThrow();
    }

    private ImportRow onlyRow(Long batchId) {
        List<ImportRow> rows = importRowRepository.findByImportBatchId(batchId);
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private SupplierOffer onlyOfferForBatch(Long batchId) {
        List<SupplierOffer> offers = supplierOfferRepository.findAll().stream()
                .filter(o -> o.getLastSeenBatch() != null && o.getLastSeenBatch().getId().equals(batchId))
                .toList();
        assertEquals(1, offers.size());
        return offers.get(0);
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
        PricingService pricingService(com.plstk.loyaltybot.config.SupplierImportProperties properties) {
            return new PricingService(properties);
        }

        @Bean
        CatalogAvailabilityService catalogAvailabilityService(
                ProductRepository productRepository,
                SupplierOfferRepository supplierOfferRepository,
                PricingService pricingService) {
            return new CatalogAvailabilityService(productRepository, supplierOfferRepository, pricingService);
        }

        @Bean
        BrandNormalizer brandNormalizer() {
            return new BrandNormalizer();
        }

        @Bean
        BrandAliasResolver brandAliasResolver(BrandAliasRepository brandAliasRepository, BrandNormalizer brandNormalizer) {
            return new BrandAliasResolver(brandAliasRepository, brandNormalizer);
        }

        @Bean
        RowAttributeNormalizer rowAttributeNormalizer(BrandAliasResolver brandAliasResolver) {
            return new RowAttributeNormalizer(brandAliasResolver);
        }

        @Bean
        CriticalAttributeConflictChecker criticalAttributeConflictChecker() {
            return new CriticalAttributeConflictChecker();
        }

        @Bean
        ProductCreationLock productCreationLock() {
            return new LocalProductCreationLock();
        }

        @Bean
        ImportBatchApplyWriter importBatchApplyWriter(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                ProductRepository productRepository,
                SupplierOfferRepository supplierOfferRepository,
                SupplierProductLinkRepository supplierProductLinkRepository,
                ShopSettingsRepository shopSettingsRepository,
                PricingService pricingService,
                CatalogAvailabilityService catalogAvailabilityService,
                ObjectMapper objectMapper,
                RowAttributeNormalizer rowAttributeNormalizer,
                CriticalAttributeConflictChecker criticalAttributeConflictChecker,
                BrandAliasResolver brandAliasResolver,
                ProductCreationLock productCreationLock) {
            return new ImportBatchApplyWriter(
                    importBatchRepository, importRowRepository, productRepository, supplierOfferRepository,
                    supplierProductLinkRepository, shopSettingsRepository, pricingService, catalogAvailabilityService,
                    objectMapper, rowAttributeNormalizer, criticalAttributeConflictChecker, brandAliasResolver,
                    productCreationLock);
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
        ImportBatchApplyService importBatchApplyService(ImportBatchApplyWriter writer, SupplierImportMetrics metrics) {
            return new ImportBatchApplyService(writer, metrics);
        }
    }
}
