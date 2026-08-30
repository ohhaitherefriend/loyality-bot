package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.commerce.ProductImportBatch;
import com.plstk.loyaltybot.repository.ProductImportBatchRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductImportServiceTest {

    private static final String SHOP_ID = "shop-1";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductImportBatchRepository importBatchRepository;

    @InjectMocks
    private ProductImportService productImportService;

    @BeforeEach
    void setUp() {
        when(importBatchRepository.save(any(ProductImportBatch.class))).thenAnswer(invocation -> {
            ProductImportBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                batch.setId(1L);
            }
            return batch;
        });
        when(productRepository.findByShopIdAndSourceSheetAndSupplierArticle(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void detectFormat_recognizesSimpleSheet() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Лист1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Артикул");
            header.createCell(1).setCellValue("Наименование");
            header.createCell(2).setCellValue("Цена / шт / руб");

            assertEquals(ProductImportService.ImportFormat.SIMPLE, productImportService.detectFormat(workbook));
        }
    }

    @Test
    void detectFormat_prefersStandardWhenBothPresent() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet standard = workbook.createSheet("Парфюмерия");
            standard.createRow(5).createCell(3).setCellValue("Бренд");

            Sheet simple = workbook.createSheet("Лист1");
            Row header = simple.createRow(0);
            header.createCell(0).setCellValue("Артикул");
            header.createCell(1).setCellValue("Наименование");
            header.createCell(2).setCellValue("Цена");

            assertEquals(ProductImportService.ImportFormat.STANDARD, productImportService.detectFormat(workbook));
        }
    }

    @Test
    void importPriceList_importsSimpleFormatRows() throws Exception {
        byte[] bytes = buildSimpleWorkbook(
                new String[][]{
                        {"AM001", "Chanel Les Beiges Water Fresh Tint 30 ml", "7500"},
                        {"AM002", "Kevin Murphy Shampoo 250 ml", "3200"},
                });

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "simple-price.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes);

        ProductImportService.ImportResult result = productImportService.importPriceList(
                SHOP_ID, file, BigDecimal.valueOf(35), false, true);

        assertEquals("COMPLETED", result.status());
        assertEquals(2, result.importedCount());
        assertEquals(0, result.updatedCount());
        assertEquals(0, result.skippedCount());

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository, atLeastOnce()).save(productCaptor.capture());

        Product first = productCaptor.getAllValues().stream()
                .filter(product -> "AM001".equals(product.getSupplierArticle()))
                .findFirst()
                .orElseThrow();
        assertEquals("Chanel Les", first.getBrand());
        assertEquals("Chanel Les Beiges Water Fresh Tint 30 ml", first.getName());
        assertEquals(0, new BigDecimal("7500").compareTo(first.getSupplierPrice()));
        assertEquals(0, new BigDecimal("10125").compareTo(first.getSalePrice()));
        assertEquals("Лист1", first.getSourceSheet());
    }

    @Test
    void importPriceList_rejectsUnknownFormat() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Random");
            workbook.getSheetAt(0).createRow(0).createCell(0).setCellValue("Column A");
            workbook.write(out);
            bytes = out.toByteArray();
        }

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "bad.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> productImportService.importPriceList(
                SHOP_ID, file, BigDecimal.valueOf(35), false, true));
        assertTrue(error.getMessage().contains("Unsupported Excel format"));
    }

    @Test
    void inferBrandFromProductName_extractsLeadingBrand() {
        assertEquals("Chanel Les", productImportService.inferBrandFromProductName("Chanel Les Beiges Water Fresh Tint 30 ml"));
        assertEquals("Kevin Murphy", productImportService.inferBrandFromProductName("Kevin Murphy Shampoo 250 ml"));
        assertEquals("BVLGARI", productImportService.inferBrandFromProductName("BVLGARI Набор Petits et Mamans"));
    }

    @Test
    void importPriceList_importsAttachedSimpleFileWhenPresent() throws Exception {
        Path sample = Path.of(System.getProperty("user.home"),
                "Downloads/Telegram Desktop/Oribe_+_Kevin_Murphy_+_DSD_+_Charlotte_Tilbury_+_Laneige_+_…_price (2).xlsx");
        if (!Files.exists(sample)) {
            return;
        }

        MockMultipartFile file = new MockMultipartFile(
                "file",
                sample.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(sample));

        ProductImportService.ImportResult result = productImportService.importPriceList(
                SHOP_ID, file, BigDecimal.valueOf(35), false, true);

        assertEquals("COMPLETED", result.status());
        assertTrue(result.importedCount() > 400, "expected hundreds of imported rows, got " + result.importedCount());
    }

    private byte[] buildSimpleWorkbook(String[][] rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Лист1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Артикул");
            header.createCell(1).setCellValue("Наименование");
            header.createCell(2).setCellValue("Цена / шт / руб");

            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(rows[i][0]);
                row.createCell(1).setCellValue(rows[i][1]);
                row.createCell(2).setCellValue(new BigDecimal(rows[i][2]).doubleValue());
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }
}
