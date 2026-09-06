package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import jakarta.persistence.EntityManager;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(ManualImportUploadServiceTest.TestConfig.class)
class ManualImportUploadServiceTest {

    private static final String SHOP_A = "shop-a";
    private static final String SHOP_B = "shop-b";
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

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
    private ManualImportUploadService manualImportUploadService;
    @Autowired
    private EntityManager entityManager;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("supplier-import.storage.base-path", tempDir::toString);
        registry.add("supplier-import.storage.max-file-size-bytes", () -> "10000");
    }

    private SupplierSource sourceA;

    @BeforeEach
    void setUp() {
        Supplier supplier = supplierRepository.save(
                Supplier.builder().shopId(SHOP_A).name("Supplier A").build());
        sourceA = supplierSourceRepository.save(
                SupplierSource.builder().shopId(SHOP_A).supplier(supplier).label("main").build());
        entityManager.flush();
    }

    @Test
    void duplicateEmailThenManualAttachment_reusesSameFileAndBatch() throws IOException {
        byte[] workbook = xlsxBytes();
        IngestionResult emailResult = attachmentIngestionService.ingest(
                SHOP_A,
                sourceA.getId(),
                new AttachmentMetadata("price.xlsx", XLSX_MEDIA_TYPE, "{\"channel\":\"EMAIL\"}"),
                new ByteArrayInputStream(workbook));

        IngestionResult manualResult = manualImportUploadService.upload(
                SHOP_A, sourceA.getId(), xlsxFile(workbook), "request-1", 10L);
        entityManager.flush();

        assertFalse(emailResult.alreadyExisted());
        assertTrue(manualResult.alreadyExisted());
        assertEquals(emailResult.importFile().getId(), manualResult.importFile().getId());
        assertEquals(emailResult.importBatch().getId(), manualResult.importBatch().getId());
        assertEquals(1, importFileRepository.count());
        assertEquals(1, importBatchRepository.count());
    }

    @Test
    void repeatedManualRequest_isIdempotent() throws IOException {
        byte[] workbook = xlsxBytes();

        IngestionResult first = manualImportUploadService.upload(
                SHOP_A, sourceA.getId(), xlsxFile(workbook), "same-request", 10L);
        IngestionResult second = manualImportUploadService.upload(
                SHOP_A, sourceA.getId(), xlsxFile(workbook), "same-request", 10L);
        entityManager.flush();

        assertFalse(first.alreadyExisted());
        assertTrue(second.alreadyExisted());
        assertEquals(first.importBatch().getId(), second.importBatch().getId());
        assertEquals(1, importFileRepository.count());
        assertEquals(1, importBatchRepository.count());
    }

    @Test
    void sourceFromAnotherTenant_isRejected() throws IOException {
        byte[] workbook = xlsxBytes();

        assertThrows(IllegalArgumentException.class, () -> manualImportUploadService.upload(
                SHOP_B, sourceA.getId(), xlsxFile(workbook), "request-tenant", 10L));

        assertEquals(0, importFileRepository.count());
        assertEquals(0, importBatchRepository.count());
    }

    @Test
    void unsupportedExtensionOrMediaType_isRejectedBeforeIngestion() {
        MockMultipartFile wrongExtension = new MockMultipartFile(
                "file", "price.csv", "text/csv", "a,b".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile wrongMediaType = new MockMultipartFile(
                "file", "price.xlsx", "text/plain", new byte[]{0x50, 0x4B, 0x03, 0x04});

        assertThrows(ManualImportUploadService.ManualUploadValidationException.class,
                () -> manualImportUploadService.upload(
                        SHOP_A, sourceA.getId(), wrongExtension, "request-type-1", 10L));
        assertThrows(ManualImportUploadService.ManualUploadValidationException.class,
                () -> manualImportUploadService.upload(
                        SHOP_A, sourceA.getId(), wrongMediaType, "request-type-2", 10L));
        assertEquals(0, importFileRepository.count());
    }

    @Test
    void spoofedXlsxWithWrongSignature_isRejectedBeforeIngestion() {
        MockMultipartFile spoofed = new MockMultipartFile(
                "file", "price.xlsx", XLSX_MEDIA_TYPE, "not an xlsx".getBytes(StandardCharsets.UTF_8));

        assertThrows(ManualImportUploadService.ManualUploadValidationException.class,
                () -> manualImportUploadService.upload(
                        SHOP_A, sourceA.getId(), spoofed, "request-signature", 10L));
        assertEquals(0, importFileRepository.count());
    }

    @Test
    void oversizedFile_isRejectedBeforeIngestion() {
        byte[] oversized = new byte[10_001];
        oversized[0] = 0x50;
        oversized[1] = 0x4B;
        oversized[2] = 0x03;
        oversized[3] = 0x04;
        MockMultipartFile file = new MockMultipartFile("file", "price.xlsx", XLSX_MEDIA_TYPE, oversized);

        assertThrows(ManualImportUploadService.ManualUploadTooLargeException.class,
                () -> manualImportUploadService.upload(
                        SHOP_A, sourceA.getId(), file, "request-size", 10L));
        assertEquals(0, importFileRepository.count());
    }

    private static MockMultipartFile xlsxFile(byte[] content) {
        return new MockMultipartFile("file", "price.xlsx", XLSX_MEDIA_TYPE, content);
    }

    private static byte[] xlsxBytes() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Price").createRow(0).createCell(0).setCellValue("Product");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

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

        @Bean
        ManualImportUploadService manualImportUploadService(
                AttachmentIngestionService attachmentIngestionService,
                SupplierImportProperties properties,
                ObjectMapper objectMapper) {
            return new ManualImportUploadService(attachmentIngestionService, properties, objectMapper);
        }
    }
}
