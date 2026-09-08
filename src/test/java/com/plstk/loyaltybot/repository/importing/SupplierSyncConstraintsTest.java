package com.plstk.loyaltybot.repository.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierOfferRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class SupplierSyncConstraintsTest {

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
    private SupplierOfferRepository supplierOfferRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void supplierUniqueConstraint_isScopedPerShop_notGlobal() {
        Supplier shopASupplier = supplierRepository.save(newSupplier(SHOP_A, "ACME"));
        Supplier shopBSupplier = supplierRepository.save(newSupplier(SHOP_B, "ACME"));
        entityManager.flush();

        assertTrue(shopASupplier.getId() > 0);
        assertTrue(shopBSupplier.getId() > 0);
        assertEquals(List.of(shopASupplier.getId()),
                supplierRepository.findByShopId(SHOP_A).stream().map(Supplier::getId).toList());
        assertEquals(List.of(shopBSupplier.getId()),
                supplierRepository.findByShopId(SHOP_B).stream().map(Supplier::getId).toList());
    }

    @Test
    void supplierUniqueConstraint_rejectsDuplicateNameWithinSameShop() {
        supplierRepository.save(newSupplier(SHOP_A, "ACME"));
        entityManager.flush();

        // IDENTITY generation flushes the insert immediately on save(), not on the next flush().
        assertThrows(DataIntegrityViolationException.class,
                () -> supplierRepository.save(newSupplier(SHOP_A, "ACME")));
    }

    @Test
    void tenantIsolation_repositoryQueriesNeverCrossShops() {
        Supplier shopASupplier = supplierRepository.save(newSupplier(SHOP_A, "Supplier A"));
        SupplierSource shopASource = supplierSourceRepository.save(newSource(SHOP_A, shopASupplier));
        Supplier shopBSupplier = supplierRepository.save(newSupplier(SHOP_B, "Supplier B"));
        SupplierSource shopBSource = supplierSourceRepository.save(newSource(SHOP_B, shopBSupplier));
        entityManager.flush();

        // Same content hash allowed for two different shops - the unique key includes shopId.
        String sameSha = "a".repeat(64);
        ImportFile shopAFile = importFileRepository.save(newFile(SHOP_A, shopASource, sameSha));
        ImportFile shopBFile = importFileRepository.save(newFile(SHOP_B, shopBSource, sameSha));
        entityManager.flush();

        assertTrue(importFileRepository.findByShopIdAndSupplierSourceIdAndSha256(SHOP_A, shopASource.getId(), sameSha).isPresent());
        assertTrue(importFileRepository.findByShopIdAndSupplierSourceIdAndSha256(SHOP_B, shopBSource.getId(), sameSha).isPresent());
        // Cross-shop lookup with the wrong shopId must not leak the other tenant's file.
        assertTrue(importFileRepository.findByShopIdAndSupplierSourceIdAndSha256(SHOP_A, shopBSource.getId(), sameSha).isEmpty());
        assertTrue(shopAFile.getId() != null && shopBFile.getId() != null);
    }

    @Test
    void importFileUniqueConstraint_rejectsDuplicateShaWithinSameShopAndSource() {
        Supplier supplier = supplierRepository.save(newSupplier(SHOP_A, "Supplier"));
        SupplierSource source = supplierSourceRepository.save(newSource(SHOP_A, supplier));
        entityManager.flush();

        String sha = "b".repeat(64);
        importFileRepository.save(newFile(SHOP_A, source, sha));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class,
                () -> importFileRepository.save(newFile(SHOP_A, source, sha)));
    }

    @Test
    void importBatchUniqueConstraint_atMostOneBatchPerImportFile() {
        Supplier supplier = supplierRepository.save(newSupplier(SHOP_A, "Supplier"));
        SupplierSource source = supplierSourceRepository.save(newSource(SHOP_A, supplier));
        ImportFile file = importFileRepository.save(newFile(SHOP_A, source, "c".repeat(64)));
        entityManager.flush();

        importBatchRepository.save(newBatch(SHOP_A, source, file));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class,
                () -> importBatchRepository.save(newBatch(SHOP_A, source, file)));
    }

    @Test
    void supplierOfferUniqueConstraint_oneActiveOfferRowPerSupplierScopeAndProduct() {
        Supplier supplier = supplierRepository.save(newSupplier(SHOP_A, "Supplier"));
        SupplierSource source = supplierSourceRepository.save(newSource(SHOP_A, supplier));
        Product product = productRepository.save(Product.builder()
                .shopId(SHOP_A)
                .name("Test product")
                .build());
        entityManager.flush();

        supplierOfferRepository.save(newOffer(SHOP_A, supplier, source, "SUPPLIER_ALL", product));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class,
                () -> supplierOfferRepository.save(newOffer(SHOP_A, supplier, source, "SUPPLIER_ALL", product)));
    }

    /**
     * Stage 3: identity is (shop, supplier, snapshotScope, product) - two independent scopes of the
     * same supplier offering the same product must be two distinct, coexisting rows, not a
     * constraint violation and not a silent overwrite.
     */
    @Test
    void supplierOfferUniqueConstraint_allowsDifferentScopesForSameSupplierAndProduct() {
        Supplier supplier = supplierRepository.save(newSupplier(SHOP_A, "Supplier"));
        SupplierSource source = supplierSourceRepository.save(newSource(SHOP_A, supplier));
        Product product = productRepository.save(Product.builder()
                .shopId(SHOP_A)
                .name("Test product")
                .build());
        entityManager.flush();

        supplierOfferRepository.save(newOffer(SHOP_A, supplier, source, "SCOPE_A", product));
        supplierOfferRepository.save(newOffer(SHOP_A, supplier, source, "SCOPE_B", product));
        entityManager.flush();

        assertEquals(2, supplierOfferRepository.findByShopIdAndProductIdAndActiveTrue(SHOP_A, product.getId()).size());
    }

    private Supplier newSupplier(String shopId, String name) {
        return Supplier.builder().shopId(shopId).name(name).build();
    }

    private SupplierSource newSource(String shopId, Supplier supplier) {
        return SupplierSource.builder().shopId(shopId).supplier(supplier).label("main").build();
    }

    private ImportFile newFile(String shopId, SupplierSource source, String sha256) {
        return ImportFile.builder()
                .shopId(shopId)
                .supplierSource(source)
                .sha256(sha256)
                .sizeBytes(1024L)
                .mediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .originalFilename("price.xlsx")
                .storageKey(shopId + "/" + sha256 + ".xlsx")
                .receivedAt(LocalDateTime.now())
                .build();
    }

    private ImportBatch newBatch(String shopId, SupplierSource source, ImportFile file) {
        return ImportBatch.builder()
                .shopId(shopId)
                .supplierSource(source)
                .importFile(file)
                .status(ImportBatchStatus.STORED)
                .attemptNumber(1)
                .build();
    }

    private SupplierOffer newOffer(String shopId, Supplier supplier, SupplierSource source, String snapshotScope, Product product) {
        return SupplierOffer.builder()
                .shopId(shopId)
                .supplier(supplier)
                .supplierSource(source)
                .snapshotScope(snapshotScope)
                .product(product)
                .supplierPrice(new BigDecimal("1000.00"))
                .appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("1300.00"))
                .active(true)
                .build();
    }
}
