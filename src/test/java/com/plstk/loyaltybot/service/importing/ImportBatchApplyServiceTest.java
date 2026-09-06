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

        SupplierOffer offer = supplierOfferRepository.findByShopIdAndSupplierIdAndProductId(SHOP_A, supplier.getId(), product.getId())
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
    void existingOfferSamePriceAndStock_isCountedUnchanged() {
        SupplierSource source = saveSource(SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product product = saveProduct("Existing", false);
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).product(product)
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
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).product(product)
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

        SupplierOffer offer = supplierOfferRepository.findByShopIdAndSupplierIdAndProductId(SHOP_A, supplier.getId(), product.getId())
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
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).product(product)
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

    @Test
    void deltaSnapshot_neverDeactivatesMissingOffers() {
        SupplierSource source = saveSource(SnapshotMode.DELTA, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        Product product = saveProduct("StaysActive", true);
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).product(product)
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
        SupplierOffer offer = supplierOfferRepository.findByShopIdAndSupplierIdAndProductId(SHOP_A, supplier.getId(), product.getId())
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
                .shopId(SHOP_A).supplier(supplier).supplierSource(sourceA).product(productA)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).active(true).build());
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(sourceB).product(productB)
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

    @Test
    void multiSupplierAvailability_oneSupplierDeactivated_productStaysVisibleViaOtherSupplier() {
        Supplier supplier2 = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier Two").build());
        SupplierSource source1 = saveSource(supplier, SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        SupplierSource source2 = saveSource(supplier2, SnapshotMode.FULL, "SCOPE", new BigDecimal("30.00"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);

        Product product = saveProduct("SharedAcrossSuppliers", true);
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source1).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("100.00")).active(true).build());
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier2).supplierSource(source2).product(product)
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

    private NormalizedRowData normalized(String externalSku, String barcode, String price, String brand, Integer stock) {
        return new NormalizedRowData(
                brand, "line", null, new BigDecimal("100"), "ml", null, null, false, false,
                externalSku, barcode, price != null ? new BigDecimal(price) : null, stock,
                (brand == null ? "" : brand.toLowerCase()) + " line", "fp-" + externalSku);
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
        ImportBatchApplyWriter importBatchApplyWriter(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                ProductRepository productRepository,
                SupplierOfferRepository supplierOfferRepository,
                SupplierProductLinkRepository supplierProductLinkRepository,
                ShopSettingsRepository shopSettingsRepository,
                PricingService pricingService,
                CatalogAvailabilityService catalogAvailabilityService,
                ObjectMapper objectMapper) {
            return new ImportBatchApplyWriter(
                    importBatchRepository, importRowRepository, productRepository, supplierOfferRepository,
                    supplierProductLinkRepository, shopSettingsRepository, pricingService, catalogAvailabilityService,
                    objectMapper);
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
