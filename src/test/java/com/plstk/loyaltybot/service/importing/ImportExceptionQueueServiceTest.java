package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 07 unified exception queue: row-level ({@code NEEDS_REVIEW}/{@code INVALID}) and
 * batch-level ({@code QUARANTINED}/{@code FAILED}/{@code NEEDS_ATTENTION}) listings, each shop-scoped
 * and paginated, with a supplier filter on the row queue.
 */
@DataJpaTest
@Import(ImportExceptionQueueServiceTest.TestConfig.class)
class ImportExceptionQueueServiceTest {

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
    private ImportExceptionQueueService importExceptionQueueService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    private Supplier supplierA;
    private SupplierSource sourceA;

    @BeforeEach
    void setUp() {
        supplierA = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier A").build());
        sourceA = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(supplierA).label("main").build());
        flushClear();
    }

    @Test
    void listRowExceptions_onlyReturnsNeedsReviewAndInvalid_forThisShop() {
        Long batchId = createBatch(sourceA, ImportBatchStatus.NEEDS_ATTENTION);
        addRow(batchId, ImportRowStatus.NEEDS_REVIEW, "Foo Perfume", "Brand A", "100.00");
        addRow(batchId, ImportRowStatus.INVALID, "Bar Cream", "Brand B", "50.00");
        addRow(batchId, ImportRowStatus.APPLIED, "Baz Applied", "Brand C", "20.00");

        Supplier supplierB = supplierRepository.save(Supplier.builder().shopId(SHOP_B).name("Supplier B").build());
        SupplierSource sourceB = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_B).supplier(supplierB).label("main").build());
        Long otherShopBatch = createBatch(sourceB, ImportBatchStatus.NEEDS_ATTENTION);
        addRowForShop(SHOP_B, otherShopBatch, ImportRowStatus.NEEDS_REVIEW, "Other Shop Row", "Brand X", "10.00");
        flushClear();

        Page<RowExceptionSummary> page = importExceptionQueueService.listRowExceptions(SHOP_A, null, 0, 20);

        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream().allMatch(r ->
                r.status() == ImportRowStatus.NEEDS_REVIEW || r.status() == ImportRowStatus.INVALID));
        assertTrue(page.getContent().stream().allMatch(r -> r.supplierId().equals(supplierA.getId())));
    }

    @Test
    void listRowExceptions_filtersBySupplierId() {
        Supplier supplierB = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier B").build());
        SupplierSource sourceB = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(supplierB).label("main").build());

        Long batchA = createBatch(sourceA, ImportBatchStatus.NEEDS_ATTENTION);
        addRow(batchA, ImportRowStatus.NEEDS_REVIEW, "From A", "Brand A", "100.00");
        Long batchB = createBatch(sourceB, ImportBatchStatus.NEEDS_ATTENTION);
        addRow(batchB, ImportRowStatus.NEEDS_REVIEW, "From B", "Brand B", "50.00");
        flushClear();

        Page<RowExceptionSummary> page = importExceptionQueueService.listRowExceptions(SHOP_A, supplierB.getId(), 0, 20);

        assertEquals(1, page.getTotalElements());
        assertEquals(supplierB.getId(), page.getContent().get(0).supplierId());
        assertEquals("From B", page.getContent().get(0).rawNamePreview());
    }

    @Test
    void listRowExceptions_previewsRawNameBrandAndPrice() {
        Long batchId = createBatch(sourceA, ImportBatchStatus.NEEDS_ATTENTION);
        addRow(batchId, ImportRowStatus.NEEDS_REVIEW, "Chanel No 5", "Chanel", "199.99");
        flushClear();

        Page<RowExceptionSummary> page = importExceptionQueueService.listRowExceptions(SHOP_A, null, 0, 20);

        assertEquals(1, page.getTotalElements());
        RowExceptionSummary summary = page.getContent().get(0);
        assertEquals("Chanel No 5", summary.rawNamePreview());
        assertEquals("Chanel", summary.brandPreview());
        assertEquals(new BigDecimal("199.99"), summary.supplierPricePreview());
    }

    @Test
    void listBatchExceptions_returnsQuarantinedFailedAndNeedsAttention_onlyForThisShop() {
        createBatch(sourceA, ImportBatchStatus.QUARANTINED);
        createBatch(sourceA, ImportBatchStatus.FAILED);
        createBatch(sourceA, ImportBatchStatus.NEEDS_ATTENTION);
        createBatch(sourceA, ImportBatchStatus.APPLIED);
        flushClear();

        Page<BatchExceptionSummary> page = importExceptionQueueService.listBatchExceptions(SHOP_A, 0, 20);

        assertEquals(3, page.getTotalElements());
        assertTrue(page.getContent().stream().noneMatch(b -> b.status() == ImportBatchStatus.APPLIED));
    }

    @Test
    void listBatchExceptions_pagination_respectsPageAndSize() {
        for (int i = 0; i < 5; i++) {
            createBatch(sourceA, ImportBatchStatus.QUARANTINED);
        }
        flushClear();

        Page<BatchExceptionSummary> firstPage = importExceptionQueueService.listBatchExceptions(SHOP_A, 0, 2);
        Page<BatchExceptionSummary> secondPage = importExceptionQueueService.listBatchExceptions(SHOP_A, 1, 2);

        assertEquals(5, firstPage.getTotalElements());
        assertEquals(3, firstPage.getTotalPages());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(2, secondPage.getContent().size());
    }

    // ===== helpers =====

    private Long createBatch(SupplierSource source, ImportBatchStatus status) {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(source.getShopId()).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(1L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(source.getShopId()).supplierSource(source).importFile(importFile)
                .status(status).attemptNumber(1).build());
        entityManager.flush();
        return batch.getId();
    }

    private void addRow(Long batchId, ImportRowStatus status, String rawName, String brand, String price) {
        addRowForShop(SHOP_A, batchId, status, rawName, brand, price);
    }

    private void addRowForShop(String shopId, Long batchId, ImportRowStatus status, String rawName, String brand, String price) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        String rawJson = toJson(Map.of(
                LayoutRuleDefinition.FIELD_RAW_NAME, rawName,
                LayoutRuleDefinition.FIELD_BRAND, brand,
                LayoutRuleDefinition.FIELD_SUPPLIER_PRICE, price));
        importRowRepository.save(ImportRow.builder()
                .shopId(shopId).importBatch(batch).sourceRowNumber(1).rawData(rawJson).status(status).build());
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
        ImportExceptionQueueService importExceptionQueueService(
                ImportRowRepository importRowRepository, ImportBatchRepository importBatchRepository, ObjectMapper objectMapper) {
            return new ImportExceptionQueueService(importRowRepository, importBatchRepository, objectMapper);
        }
    }
}
