package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({AttachmentIngestionServiceTest.TestServicesConfig.class})
class AttachmentIngestionServiceTest {

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
    private AttachmentIngestionService attachmentIngestionService;
    @Autowired
    private EntityManager entityManager;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideStorageBasePath(DynamicPropertyRegistry registry) {
        registry.add("supplier-import.storage.base-path", tempDir::toString);
    }

    private SupplierSource sourceA;
    private SupplierSource sourceB;

    @BeforeEach
    void setUp() {
        Supplier supplierA = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier A").build());
        sourceA = supplierSourceRepository.save(
                SupplierSource.builder().shopId(SHOP_A).supplier(supplierA).label("main").build());
        Supplier supplierB = supplierRepository.save(Supplier.builder().shopId(SHOP_B).name("Supplier B").build());
        sourceB = supplierSourceRepository.save(
                SupplierSource.builder().shopId(SHOP_B).supplier(supplierB).label("main").build());
        entityManager.flush();
    }

    @Test
    void ingest_duplicateAttachment_isIdempotent_andCreatesExactlyOneStoredBatch() throws IOException {
        AttachmentMetadata metadata = new AttachmentMetadata("price-25-06.xlsx", "application/octet-stream", null);
        byte[] bytes = "identical price list content".getBytes(StandardCharsets.UTF_8);

        IngestionResult first = attachmentIngestionService.ingest(
                SHOP_A, sourceA.getId(), metadata, new ByteArrayInputStream(bytes));
        entityManager.flush();
        IngestionResult second = attachmentIngestionService.ingest(
                SHOP_A, sourceA.getId(), metadata, new ByteArrayInputStream(bytes));
        entityManager.flush();

        assertFalse(first.alreadyExisted());
        assertTrue(second.alreadyExisted());
        assertEquals(first.importFile().getId(), second.importFile().getId());
        assertEquals(first.importBatch().getId(), second.importBatch().getId());

        assertEquals(1, importFileRepository.count());
        assertEquals(1, importBatchRepository.count());
    }

    @Test
    void ingest_sameBytes_differentShops_createsSeparateFilesAndBatches() throws IOException {
        AttachmentMetadata metadata = new AttachmentMetadata("price.xlsx", "application/octet-stream", null);
        byte[] bytes = "same bytes different tenants".getBytes(StandardCharsets.UTF_8);

        IngestionResult resultA = attachmentIngestionService.ingest(
                SHOP_A, sourceA.getId(), metadata, new ByteArrayInputStream(bytes));
        IngestionResult resultB = attachmentIngestionService.ingest(
                SHOP_B, sourceB.getId(), metadata, new ByteArrayInputStream(bytes));
        entityManager.flush();

        assertFalse(resultA.alreadyExisted());
        assertFalse(resultB.alreadyExisted());
        assertTrue(!resultA.importFile().getId().equals(resultB.importFile().getId()));
        assertEquals(SHOP_A, resultA.importFile().getShopId());
        assertEquals(SHOP_B, resultB.importFile().getShopId());
        assertEquals(2, importFileRepository.count());
        assertEquals(2, importBatchRepository.count());
    }

    @Test
    void ingest_differentContent_createsSeparateBatches() throws IOException {
        AttachmentMetadata metadata = new AttachmentMetadata("price.xlsx", "application/octet-stream", null);

        IngestionResult first = attachmentIngestionService.ingest(
                SHOP_A, sourceA.getId(), metadata,
                new ByteArrayInputStream("version one".getBytes(StandardCharsets.UTF_8)));
        IngestionResult second = attachmentIngestionService.ingest(
                SHOP_A, sourceA.getId(), metadata,
                new ByteArrayInputStream("version two".getBytes(StandardCharsets.UTF_8)));
        entityManager.flush();

        assertFalse(first.alreadyExisted());
        assertFalse(second.alreadyExisted());
        assertTrue(!first.importBatch().getId().equals(second.importBatch().getId()));
        assertEquals(2, importFileRepository.count());
        assertEquals(2, importBatchRepository.count());
    }

    @TestConfiguration
    static class TestServicesConfig {
        @Bean
        ImportFileStorage importFileStorage(SupplierImportProperties properties) {
            return new LocalImportFileStorage(properties);
        }

        @Bean
        ImportFileBatchInsertWriter importFileBatchInsertWriter(
                ImportFileRepository importFileRepository, ImportBatchRepository importBatchRepository) {
            return new ImportFileBatchInsertWriter(importFileRepository, importBatchRepository);
        }

        @Bean
        ImportFileBatchWriter importFileBatchWriter(
                ImportFileRepository importFileRepository,
                ImportBatchRepository importBatchRepository,
                ImportFileBatchInsertWriter insertWriter) {
            return new ImportFileBatchWriter(importFileRepository, importBatchRepository, insertWriter);
        }

        @Bean
        AttachmentIngestionService attachmentIngestionService(
                SupplierSourceRepository supplierSourceRepository,
                ImportFileStorage importFileStorage,
                SupplierImportProperties properties,
                ImportFileBatchWriter importFileBatchWriter) {
            return new AttachmentIngestionService(
                    supplierSourceRepository, importFileStorage, properties, importFileBatchWriter);
        }
    }
}
