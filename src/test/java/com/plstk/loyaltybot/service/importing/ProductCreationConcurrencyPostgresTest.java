package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.PriceRoundingPolicy;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.Supplier;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-031 (Section 4): proves the DB-level creation race is actually closed, using a REAL
 * PostgreSQL container (Testcontainers) - not H2 - and two genuinely concurrent, latch-coordinated
 * transactions running on separate threads (separate physical DB connections), each simulating a
 * DIFFERENT supplier's apply batch independently deciding {@code NEW_PRODUCT} for the SAME
 * canonical product identity (same brand+fingerprint) at the same time.
 *
 * <p>This specifically exercises {@link PostgresAdvisoryProductCreationLock} - the cross-instance
 * mechanism - rather than {@link LocalProductCreationLock} (a bare JVM {@code synchronized}/local
 * cache, explicitly documented as insufficient alone: two SEPARATE application instances would
 * each have their own JVM-local lock that knows nothing about the other). Running both threads in
 * one JVM against a real Postgres backend still faithfully exercises the actual serialization
 * mechanism ({@code pg_advisory_xact_lock}), since the lock/unlock semantics are entirely
 * server-side and unaware of which process/thread holds the underlying connection.
 *
 * <p><b>Environment note:</b> requires a local Docker daemon to start the PostgreSQL container -
 * same requirement as {@code FlywayPostgresSchemaTest}. If Docker is unavailable this test class
 * cannot run at all (container startup fails before any test method executes) - see
 * {@code docs/STATE.md} for this sandbox's current Docker availability status.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(ProductCreationConcurrencyPostgresTest.TestConfig.class)
class ProductCreationConcurrencyPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("loyalty_bot_concurrency_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    private static final String SHOP_ID = "shop-concurrency-race";

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
    private ImportBatchApplyService importBatchApplyService;
    @Autowired
    private RowAttributeNormalizer rowAttributeNormalizer;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    /**
     * Two DIFFERENT suppliers' batches, each independently deciding {@code NEW_PRODUCT} for the
     * exact same canonical product (same brand+fingerprint, different {@code externalSku}s),
     * released to run at the SAME instant via a {@link CountDownLatch} barrier. Without the
     * cross-instance lock, both transactions' {@code checkProductCreation} could observe "zero
     * matches" before either commits and both insert - producing two products. With the lock, the
     * second transaction blocks at {@code pg_advisory_xact_lock} until the first commits, then
     * re-observes the real, now-populated DB state and reuses the product the first one created.
     */
    @Test
    void twoSuppliersConcurrentlyImportSameNewProduct_onlyOneProductIsEverCreated() throws Exception {
        Supplier supplierA = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier A").build());
        Supplier supplierB = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier B").build());
        SupplierSource sourceA = saveSource(supplierA, "SCOPE_A");
        SupplierSource sourceB = saveSource(supplierB, "SCOPE_B");

        String sharedFingerprint = fingerprintFor("Nivea", "Nivea Cream 100 ml");
        Long batchA = createBatch(sourceA);
        Long batchB = createBatch(sourceB);
        addNewProductRow(batchA, sourceA.getSupplier(), "SKU-FROM-A", sharedFingerprint);
        addNewProductRow(batchB, sourceB.getSupplier(), "SKU-FROM-B", sharedFingerprint);

        // Make the setup genuinely committed and visible to the two worker threads' own separate
        // connections/transactions - a @DataJpaTest's default single rolled-back transaction would
        // otherwise hide this data from the other threads entirely (different DB session).
        TestTransaction.flagForCommit();
        TestTransaction.end();

        CountDownLatch startBarrier = new CountDownLatch(2);
        CountDownLatch releaseGate = new CountDownLatch(1);
        AtomicReference<Throwable> threadAError = new AtomicReference<>();
        AtomicReference<Throwable> threadBError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> runReleased(startBarrier, releaseGate, () -> importBatchApplyService.applyNewly(batchA), threadAError));
            executor.submit(() -> runReleased(startBarrier, releaseGate, () -> importBatchApplyService.applyNewly(batchB), threadBError));

            // Wait for both threads to reach the barrier (both ready to call applyNewly at once),
            // then release them simultaneously.
            assertTrue(startBarrier.await(10, TimeUnit.SECONDS), "both worker threads must reach the start barrier");
            releaseGate.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "both concurrent applies must finish within the timeout");
        }
        assertNull(threadAError.get(), () -> "supplier A's apply thread must not throw: " + threadAError.get());
        assertNull(threadBError.get(), () -> "supplier B's apply thread must not throw: " + threadBError.get());

        TestTransaction.start();
        List<Product> products = productRepository.findAll();
        assertEquals(1, products.size(),
                "two suppliers concurrently importing the SAME new canonical product must result in exactly ONE product");
        Long theProduct = products.get(0).getId();

        ImportBatch reloadedA = importBatchRepository.findById(batchA).orElseThrow();
        ImportBatch reloadedB = importBatchRepository.findById(batchB).orElseThrow();
        assertEquals(ImportBatchStatus.APPLIED, reloadedA.getStatus(), "supplier A's batch must apply successfully");
        assertEquals(ImportBatchStatus.APPLIED, reloadedB.getStatus(), "supplier B's batch must apply successfully");

        List<SupplierProductLink> links = supplierProductLinkRepository.findAll();
        assertEquals(2, links.size(), "each supplier must still get its own SupplierProductLink to the shared product");
        assertTrue(links.stream().allMatch(l -> l.getProduct().getId().equals(theProduct)));

        assertEquals(2, supplierOfferRepository.findByShopIdAndProductIdAndActiveTrue(SHOP_ID, theProduct).size(),
                "both suppliers' offers for the shared product must both exist and be active");
    }

    private void runReleased(CountDownLatch startBarrier, CountDownLatch releaseGate, Runnable work, AtomicReference<Throwable> error) {
        try {
            startBarrier.countDown();
            releaseGate.await();
            work.run();
        } catch (Throwable t) {
            error.set(t);
        }
    }

    private SupplierSource saveSource(Supplier supplier, String scope) {
        return supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_ID).supplier(supplier).label("source-" + scope)
                .snapshotMode(SnapshotMode.DELTA).snapshotScope(scope)
                .autoApply(true).shadowMode(false)
                .commissionPercentOverride(BigDecimal.ZERO)
                .roundingPolicy(PriceRoundingPolicy.WHOLE_UNIT_HALF_UP)
                .build());
    }

    private Long createBatch(SupplierSource source) {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_ID).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(10L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_ID).supplierSource(source).importFile(importFile)
                .status(ImportBatchStatus.AUTO_APPROVED).attemptNumber(1).build());
        entityManager.flush();
        return batch.getId();
    }

    private void addNewProductRow(Long batchId, Supplier supplier, String externalSku, String fingerprint) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        NormalizedRowData normalized = new NormalizedRowData(
                "Nivea", "line", null, new BigDecimal("100"), "ml", null, null, false, false,
                externalSku, null, new BigDecimal("100.00"), 5,
                "nivea line", fingerprint, RowAttributeNormalizer.NORMALIZATION_VERSION);
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_ID).importBatch(batch).sourceRowNumber(1)
                .rawData(toJson(java.util.Map.of(
                        LayoutRuleDefinition.FIELD_BRAND, "Nivea",
                        LayoutRuleDefinition.FIELD_RAW_NAME, "Nivea Cream 100 ml",
                        LayoutRuleDefinition.FIELD_EXTERNAL_SKU, externalSku)))
                .normalizedData(toJson(normalized))
                .status(ImportRowStatus.AUTO_APPROVED)
                .build());
        entityManager.flush();
    }

    private String fingerprintFor(String brand, String rawName) {
        java.util.Map<String, String> raw = new java.util.HashMap<>();
        raw.put(LayoutRuleDefinition.FIELD_BRAND, brand);
        raw.put(LayoutRuleDefinition.FIELD_RAW_NAME, rawName);
        return rowAttributeNormalizer.normalize(SHOP_ID, raw).fingerprint();
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

        // ADR-031 (Section 4): the REAL cross-instance lock under test here - never the JVM-local
        // fallback, which would trivially "pass" this single-JVM test without proving anything
        // about the actual cross-instance mechanism used in production.
        @Bean
        ProductCreationLock productCreationLock(EntityManager entityManager) {
            return new PostgresAdvisoryProductCreationLock(entityManager);
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
