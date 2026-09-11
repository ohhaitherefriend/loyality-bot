package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-031 (Section 6): {@link SupplierLinkFingerprintMigrationService#backfill} calling {@code
 * this.recomputeOne(...)} on the SAME bean instance used to be a Spring AOP self-invocation - a
 * call from one method of a bean to another method of the SAME instance bypasses the
 * {@code @Transactional} proxy entirely, so no transaction genuinely opened, while {@code
 * SupplierProductLink.product} is a {@code LAZY} association. {@link SupplierLinkFingerprintRecomputer}
 * fixes this by moving the actual recompute+persist work to a SEPARATE bean, so the call from
 * {@code backfill()} is a genuine cross-bean call that Spring's proxy actually intercepts.
 *
 * <p>Every test here deliberately ends the {@code @DataJpaTest}-managed test transaction (via
 * {@link TestTransaction#end()}) BEFORE invoking {@code backfill()} - a {@code @DataJpaTest}'s
 * default single wrap-the-whole-method-in-one-transaction behavior would otherwise leave an
 * incidental transaction/session open on this thread regardless of whether the self-invocation
 * bug were still present, completely masking the very failure mode this fix addresses (in
 * production there is no such incidental transaction). Ending it first means {@code backfill()}
 * genuinely runs the way it does in production: with NO transaction open on this thread except
 * whatever {@code @Transactional} methods it (or the beans it calls) open themselves.
 */
@DataJpaTest
@Import(SupplierLinkFingerprintMigrationServiceTest.TestConfig.class)
class SupplierLinkFingerprintMigrationServiceTest {

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SupplierProductLinkRepository supplierProductLinkRepository;
    @Autowired
    private SupplierLinkFingerprintMigrationService migrationService;
    @Autowired
    private EntityManager entityManager;

    // A fresh shopId per test method: several tests below deliberately COMMIT real data (via
    // TestTransaction.flagForCommit()+end(), see class javadoc) instead of relying on
    // @DataJpaTest's default rollback, so data from an earlier test method in this class would
    // otherwise still be visible/colliding in the next method's setUp (shared embedded H2 context).
    private final String shopId = "shop-backfill-" + System.nanoTime();

    private Supplier supplier;

    @BeforeEach
    void setUp() {
        supplier = supplierRepository.save(Supplier.builder().shopId(shopId).name("Backfill Supplier").build());
        entityManager.flush();
    }

    /**
     * The core Section 6 regression: a stale link's {@code product} association is {@code LAZY} -
     * recomputing its fingerprint REQUIRES a genuinely active transaction/session to load it. This
     * only passes because {@code recomputeOne} now runs through a real cross-bean
     * {@code @Transactional} call, with NO surrounding transaction on this thread (see class
     * javadoc) - exactly reproducing the conditions the self-invocation bug would have failed
     * under in production.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void backfill_recomputesStaleLink_lazyProductLoadedInsideAGenuineTransaction() {
        Product product = productRepository.save(Product.builder()
                .shopId(shopId).brand("Chanel").name("Chanel No 5 100 ml").currency("RUB").build());
        SupplierProductLink staleLink = supplierProductLinkRepository.save(SupplierProductLink.builder()
                .shopId(shopId).supplier(supplier).product(product).externalSku("SKU-STALE")
                .fingerprint("chanel|no 5|100|ml|||false|false") // legacy v1-style, no "vN:" prefix
                .normalizationVersion(1)
                .confirmedSource(LinkConfirmationSource.AUTOMATIC)
                .build());
        entityManager.flush();
        entityManager.clear();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        migrationService.backfill();

        TestTransaction.start();
        SupplierProductLink reloaded = supplierProductLinkRepository.findById(staleLink.getId()).orElseThrow();
        assertEquals(RowAttributeNormalizer.NORMALIZATION_VERSION, reloaded.getNormalizationVersion(),
                "a stale link must be recomputed to the CURRENT normalization version");
        assertTrue(reloaded.getFingerprint().startsWith("v" + RowAttributeNormalizer.NORMALIZATION_VERSION + ":"),
                "the recomputed fingerprint must carry the current version's literal prefix: " + reloaded.getFingerprint());
        assertNotEquals("chanel|no 5|100|ml|||false|false", reloaded.getFingerprint());
    }

    /**
     * Section 6: one link's recompute failure must be isolated to its OWN transaction and must
     * never block or corrupt any other link's recompute in the same run. Simulated with a
     * normalizer wrapper that deterministically throws for one specific product, standing in for
     * any real-world per-record failure (bad data, transient error, etc.) without needing to
     * corrupt actual DB state.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void backfill_oneFailingLink_isIsolated_othersStillMigrateSuccessfully() {
        Product boom = productRepository.save(Product.builder()
                .shopId(shopId).brand("Boom").name("BOOM-TRIGGER 100 ml").currency("RUB").build());
        Product fine = productRepository.save(Product.builder()
                .shopId(shopId).brand("Chanel").name("Chanel No 5 100 ml").currency("RUB").build());
        SupplierProductLink failingLink = supplierProductLinkRepository.save(SupplierProductLink.builder()
                .shopId(shopId).supplier(supplier).product(boom).externalSku("SKU-BOOM")
                .fingerprint("boom|old|100|ml|||false|false").normalizationVersion(1)
                .confirmedSource(LinkConfirmationSource.AUTOMATIC).build());
        SupplierProductLink okLink = supplierProductLinkRepository.save(SupplierProductLink.builder()
                .shopId(shopId).supplier(supplier).product(fine).externalSku("SKU-FINE")
                .fingerprint("chanel|no 5|100|ml|||false|false").normalizationVersion(1)
                .confirmedSource(LinkConfirmationSource.AUTOMATIC).build());
        entityManager.flush();
        entityManager.clear();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        migrationService.backfill();

        TestTransaction.start();
        SupplierProductLink reloadedFailing = supplierProductLinkRepository.findById(failingLink.getId()).orElseThrow();
        SupplierProductLink reloadedOk = supplierProductLinkRepository.findById(okLink.getId()).orElseThrow();

        assertEquals(1, reloadedFailing.getNormalizationVersion(),
                "a failing link's own transaction must roll back - it must remain at its OLD version, not "
                        + "silently corrupted or partially updated");
        assertEquals("boom|old|100|ml|||false|false", reloadedFailing.getFingerprint());

        assertEquals(RowAttributeNormalizer.NORMALIZATION_VERSION, reloadedOk.getNormalizationVersion(),
                "the OTHER link in the same run must still migrate successfully despite the first one failing");
        assertTrue(reloadedOk.getFingerprint().startsWith("v" + RowAttributeNormalizer.NORMALIZATION_VERSION + ":"));
    }

    /**
     * Section 6: idempotency - running {@code backfill()} again once every link is already at the
     * current version must be a safe no-op (the repository query itself naturally excludes
     * already-migrated links), never double-processing or erroring.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void backfill_secondRun_onceFullyMigrated_isANoOp() {
        Product product = productRepository.save(Product.builder()
                .shopId(shopId).brand("Chanel").name("Chanel No 5 100 ml").currency("RUB").build());
        SupplierProductLink link = supplierProductLinkRepository.save(SupplierProductLink.builder()
                .shopId(shopId).supplier(supplier).product(product).externalSku("SKU-1")
                .fingerprint("chanel|no 5|100|ml|||false|false").normalizationVersion(1)
                .confirmedSource(LinkConfirmationSource.AUTOMATIC).build());
        entityManager.flush();
        entityManager.clear();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        migrationService.backfill();
        migrationService.backfill(); // second run must not throw / must not re-touch anything

        TestTransaction.start();
        SupplierProductLink reloaded = supplierProductLinkRepository.findById(link.getId()).orElseThrow();
        assertEquals(RowAttributeNormalizer.NORMALIZATION_VERSION, reloaded.getNormalizationVersion());
    }

    /**
     * Section 6: bounded batch + continuation - a single run only recomputes up to {@code
     * fingerprintBackfill.batchSize} links (bounded DB/CPU work per scheduled tick); the remaining
     * stale links are picked up by a LATER run (simulating "continuation after restart"), never
     * silently skipped forever.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void backfill_boundedBatchSize_leavesRemainingStaleLinksForALaterRun() {
        for (int i = 0; i < 3; i++) {
            Product product = productRepository.save(Product.builder()
                    .shopId(shopId).brand("Brand" + i).name("Product " + i + " 100 ml").currency("RUB").build());
            supplierProductLinkRepository.save(SupplierProductLink.builder()
                    .shopId(shopId).supplier(supplier).product(product).externalSku("SKU-" + i)
                    .fingerprint("legacy-" + i).normalizationVersion(1)
                    .confirmedSource(LinkConfirmationSource.AUTOMATIC).build());
        }
        entityManager.flush();
        entityManager.clear();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        // TestConfig below configures batchSize=2 - this run must migrate only 2 of the 3.
        migrationService.backfill();

        TestTransaction.start();
        long migratedAfterFirstRun = supplierProductLinkRepository.findAll().stream()
                .filter(l -> shopId.equals(l.getShopId()))
                .filter(l -> l.getNormalizationVersion().equals(RowAttributeNormalizer.NORMALIZATION_VERSION))
                .count();
        assertEquals(2, migratedAfterFirstRun, "a single run must respect the configured batch-size bound");
        TestTransaction.end();

        // A later run (simulating the next scheduled tick / a post-restart resume) picks up the remainder.
        migrationService.backfill();

        TestTransaction.start();
        long migratedAfterSecondRun = supplierProductLinkRepository.findAll().stream()
                .filter(l -> shopId.equals(l.getShopId()))
                .filter(l -> l.getNormalizationVersion().equals(RowAttributeNormalizer.NORMALIZATION_VERSION))
                .count();
        assertEquals(3, migratedAfterSecondRun, "the remaining stale link must be picked up by a later run, never permanently skipped");
    }

    /** A normalizer that deterministically fails for one specific product, to test per-record isolation without corrupting real state. */
    static class FlakyRowAttributeNormalizer extends RowAttributeNormalizer {
        FlakyRowAttributeNormalizer(BrandAliasResolver brandAliasResolver) {
            super(brandAliasResolver);
        }

        @Override
        public NormalizedRowData normalizeProduct(String shopId, Product product) {
            if (product.getName() != null && product.getName().startsWith("BOOM-TRIGGER")) {
                throw new RuntimeException("Simulated recompute failure for product " + product.getId());
            }
            return super.normalizeProduct(shopId, product);
        }
    }

    @TestConfiguration
    static class TestConfig {

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
            return new FlakyRowAttributeNormalizer(brandAliasResolver);
        }

        @Bean
        SupplierLinkFingerprintRecomputer supplierLinkFingerprintRecomputer(
                SupplierProductLinkRepository supplierProductLinkRepository, RowAttributeNormalizer normalizer) {
            return new SupplierLinkFingerprintRecomputer(supplierProductLinkRepository, normalizer);
        }

        @Bean
        @Primary
        SupplierImportProperties supplierImportProperties() {
            SupplierImportProperties properties = new SupplierImportProperties();
            properties.getMatching().getFingerprintBackfill().setEnabled(true);
            properties.getMatching().getFingerprintBackfill().setBatchSize(2);
            return properties;
        }

        @Bean
        SupplierLinkFingerprintMigrationService supplierLinkFingerprintMigrationService(
                SupplierProductLinkRepository supplierProductLinkRepository,
                SupplierLinkFingerprintRecomputer recomputer,
                SupplierImportProperties properties) {
            return new SupplierLinkFingerprintMigrationService(supplierProductLinkRepository, recomputer, properties);
        }
    }
}
