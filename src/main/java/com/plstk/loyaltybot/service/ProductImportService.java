package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.commerce.AvailabilityMode;
import com.plstk.loyaltybot.entity.commerce.ImageStatus;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.commerce.ProductImportBatch;
import com.plstk.loyaltybot.repository.ProductImportBatchRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductImportService {

    private static final List<String> STANDARD_SHEETS = List.of("Косметика и уход", "Парфюмерия");
    private static final int STANDARD_HEADER_ROW_INDEX = 5;
    private static final int STANDARD_PRICE_DATE_ROW_INDEX = 3;
    private static final int STANDARD_COL_GUID = 0;
    private static final int STANDARD_COL_BRAND = 3;
    private static final int STANDARD_COL_ARTICLE = 4;
    private static final int STANDARD_COL_BARCODE = 5;
    private static final int STANDARD_COL_NAME = 6;
    private static final int STANDARD_COL_PRICE = 7;
    private static final int SIMPLE_HEADER_SCAN_ROWS = 3;
    private static final Pattern PRICE_DATE_PATTERN = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})");
    private static final Pattern LEADING_BRAND_PATTERN =
            Pattern.compile("^((?:[A-Z][A-Za-z&.'\\-]+)(?:\\s+[A-Z][A-Za-z&.'\\-]+)?)");

    private final ProductRepository productRepository;
    private final ProductImportBatchRepository importBatchRepository;

    @Transactional
    public ImportResult importPriceList(
            String shopId,
            MultipartFile file,
            BigDecimal defaultMarkupPercent,
            boolean makeImportedVisible,
            boolean overwriteManualFields) {

        if (shopId == null || shopId.isBlank()) {
            throw new IllegalArgumentException("shopId is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }

        BigDecimal markup = defaultMarkupPercent != null ? defaultMarkupPercent : BigDecimal.valueOf(35);
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "import.xlsx";

        ProductImportBatch batch = ProductImportBatch.builder()
                .shopId(shopId)
                .filename(filename)
                .status("PROCESSING")
                .importedCount(0)
                .updatedCount(0)
                .skippedCount(0)
                .totalRows(0)
                .build();
        batch = importBatchRepository.save(batch);

        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = new XSSFWorkbook(inputStream)) {

            ImportCounters counters = new ImportCounters();
            LocalDate priceListDate = null;

            ImportFormat format = detectFormat(workbook);
            switch (format) {
                case STANDARD -> priceListDate = importStandardWorkbook(
                        shopId, workbook, markup, makeImportedVisible, overwriteManualFields, counters);
                case SIMPLE -> importSimpleWorkbook(
                        shopId, workbook, markup, makeImportedVisible, overwriteManualFields, counters);
                case UNKNOWN -> throw new IllegalArgumentException(
                        "Unsupported Excel format. Expected supplier sheets (Косметика и уход / Парфюмерия) "
                                + "or a simple price list with columns Артикул, Наименование and Цена.");
            }

            batch.setPriceListDate(priceListDate);
            batch.setTotalRows(counters.totalRows);
            batch.setImportedCount(counters.imported);
            batch.setUpdatedCount(counters.updated);
            batch.setSkippedCount(counters.skipped);
            batch.setStatus("COMPLETED");
            batch.setFinishedAt(LocalDateTime.now());
            importBatchRepository.save(batch);

            log.info("Import completed for shopId={} format={}: imported={}, updated={}, skipped={}",
                    shopId, format, counters.imported, counters.updated, counters.skipped);

            return new ImportResult(
                    batch.getId(),
                    filename,
                    priceListDate,
                    counters.totalRows,
                    counters.imported,
                    counters.updated,
                    counters.skipped,
                    "COMPLETED",
                    null);

        } catch (IOException e) {
            batch.setStatus("FAILED");
            batch.setErrorMessage(e.getMessage());
            batch.setFinishedAt(LocalDateTime.now());
            importBatchRepository.save(batch);
            log.error("Import failed for shopId={}", shopId, e);
            throw new IllegalStateException("Failed to import price list: " + e.getMessage(), e);
        }
    }

    ImportFormat detectFormat(Workbook workbook) {
        if (hasStandardSheets(workbook)) {
            return ImportFormat.STANDARD;
        }
        if (!findSimpleSheets(workbook).isEmpty()) {
            return ImportFormat.SIMPLE;
        }
        return ImportFormat.UNKNOWN;
    }

    private boolean hasStandardSheets(Workbook workbook) {
        for (String sheetName : STANDARD_SHEETS) {
            if (workbook.getSheet(sheetName) != null) {
                return true;
            }
        }
        return false;
    }

    private LocalDate importStandardWorkbook(
            String shopId,
            Workbook workbook,
            BigDecimal markup,
            boolean makeImportedVisible,
            boolean overwriteManualFields,
            ImportCounters counters) {

        LocalDate priceListDate = null;

        for (String sheetName : STANDARD_SHEETS) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                log.warn("Import sheet not found: {}", sheetName);
                continue;
            }

            if (priceListDate == null) {
                priceListDate = parsePriceListDate(sheet.getRow(STANDARD_PRICE_DATE_ROW_INDEX));
            }

            String categoryPath = null;
            int lastRow = sheet.getLastRowNum();
            for (int rowIndex = STANDARD_HEADER_ROW_INDEX + 1; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                counters.totalRows++;
                String brand = cellAsString(row.getCell(STANDARD_COL_BRAND));
                String article = cellAsString(row.getCell(STANDARD_COL_ARTICLE));
                String name = cellAsString(row.getCell(STANDARD_COL_NAME));
                String barcode = normalizeBarcode(row.getCell(STANDARD_COL_BARCODE));
                String guid = cellAsString(row.getCell(STANDARD_COL_GUID));
                BigDecimal supplierPrice = cellAsBigDecimal(row.getCell(STANDARD_COL_PRICE));

                if (isCategoryRow(brand, article, name, supplierPrice)) {
                    if (brand != null && !brand.isBlank()) {
                        categoryPath = brand.trim();
                    }
                    counters.skipped++;
                    continue;
                }

                if (!isProductRow(brand, article, name, supplierPrice)) {
                    counters.skipped++;
                    continue;
                }

                UpsertOutcome outcome = upsertProduct(
                        shopId,
                        sheetName,
                        rowIndex + 1,
                        guid,
                        brand.trim(),
                        article.trim(),
                        barcode,
                        name.trim(),
                        supplierPrice,
                        categoryPath,
                        priceListDate,
                        markup,
                        makeImportedVisible,
                        overwriteManualFields);

                if (outcome == UpsertOutcome.IMPORTED) {
                    counters.imported++;
                } else {
                    counters.updated++;
                }
            }
        }

        return priceListDate;
    }

    private void importSimpleWorkbook(
            String shopId,
            Workbook workbook,
            BigDecimal markup,
            boolean makeImportedVisible,
            boolean overwriteManualFields,
            ImportCounters counters) {

        for (SimpleSheetLayout layout : findSimpleSheets(workbook)) {
            Sheet sheet = workbook.getSheetAt(layout.sheetIndex());
            int lastRow = sheet.getLastRowNum();
            for (int rowIndex = layout.headerRowIndex() + 1; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                counters.totalRows++;
                String article = cellAsString(row.getCell(layout.articleColumn()));
                String name = cellAsString(row.getCell(layout.nameColumn()));
                BigDecimal supplierPrice = cellAsBigDecimal(row.getCell(layout.priceColumn()));

                if (!isSimpleProductRow(article, name, supplierPrice)) {
                    counters.skipped++;
                    continue;
                }

                String brand = inferBrandFromProductName(name.trim());
                UpsertOutcome outcome = upsertProduct(
                        shopId,
                        sheet.getSheetName(),
                        rowIndex + 1,
                        null,
                        brand,
                        article.trim(),
                        null,
                        name.trim(),
                        supplierPrice,
                        null,
                        null,
                        markup,
                        makeImportedVisible,
                        overwriteManualFields);

                if (outcome == UpsertOutcome.IMPORTED) {
                    counters.imported++;
                } else {
                    counters.updated++;
                }
            }
        }
    }

    List<SimpleSheetLayout> findSimpleSheets(Workbook workbook) {
        List<SimpleSheetLayout> layouts = new ArrayList<>();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            detectSimpleSheetLayout(workbook.getSheetAt(sheetIndex), sheetIndex)
                    .ifPresent(layouts::add);
        }
        return layouts;
    }

    Optional<SimpleSheetLayout> detectSimpleSheetLayout(Sheet sheet, int sheetIndex) {
        if (sheet == null) {
            return Optional.empty();
        }

        int maxScanRow = Math.min(SIMPLE_HEADER_SCAN_ROWS - 1, sheet.getLastRowNum());
        for (int rowIndex = 0; rowIndex <= maxScanRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            Integer articleColumn = null;
            Integer nameColumn = null;
            Integer priceColumn = null;

            for (Cell cell : row) {
                String header = normalizeHeader(cellAsString(cell));
                if (header == null) {
                    continue;
                }
                if (matchesArticleHeader(header)) {
                    articleColumn = cell.getColumnIndex();
                } else if (matchesNameHeader(header)) {
                    nameColumn = cell.getColumnIndex();
                } else if (matchesPriceHeader(header)) {
                    priceColumn = cell.getColumnIndex();
                }
            }

            if (articleColumn != null && nameColumn != null && priceColumn != null) {
                return Optional.of(new SimpleSheetLayout(sheetIndex, rowIndex, articleColumn, nameColumn, priceColumn));
            }
        }

        return Optional.empty();
    }

    String inferBrandFromProductName(String name) {
        if (name == null || name.isBlank()) {
            return "Import";
        }

        Matcher matcher = LEADING_BRAND_PATTERN.matcher(name.trim());
        if (matcher.find()) {
            String brand = matcher.group(1).trim();
            if (!brand.isBlank()) {
                return brand;
            }
        }

        String firstToken = name.trim().split("\\s+")[0];
        return firstToken.isBlank() ? "Import" : firstToken;
    }

    private String normalizeHeader(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('\u00a0', ' ');
    }

    private boolean matchesArticleHeader(String header) {
        return header.equals("артикул") || header.equals("sku") || header.startsWith("артикул ");
    }

    private boolean matchesNameHeader(String header) {
        return header.contains("наименование") || header.contains("номенклатура");
    }

    private boolean matchesPriceHeader(String header) {
        return header.contains("цена");
    }

    private boolean isSimpleProductRow(String article, String name, BigDecimal supplierPrice) {
        return article != null && !article.isBlank()
                && name != null && !name.isBlank()
                && supplierPrice != null;
    }

    private UpsertOutcome upsertProduct(
            String shopId,
            String sourceSheet,
            int sourceRow,
            String guid,
            String brand,
            String article,
            String barcode,
            String name,
            BigDecimal supplierPrice,
            String categoryPath,
            LocalDate priceListDate,
            BigDecimal markupPercent,
            boolean makeImportedVisible,
            boolean overwriteManualFields) {

        LocalDateTime now = LocalDateTime.now();
        Optional<Product> existingOpt = findExistingProduct(shopId, guid, barcode, sourceSheet, article);

        if (existingOpt.isPresent()) {
            Product product = existingOpt.get();
            product.setSupplierGuid(blankToNull(guid));
            product.setBrand(brand);
            product.setSupplierArticle(article);
            product.setBarcode(blankToNull(barcode));
            product.setName(name);
            product.setCategoryPath(categoryPath);
            product.setSupplierPrice(supplierPrice);
            product.setSourceSheet(sourceSheet);
            product.setSourceRow(sourceRow);
            product.setPriceListDate(priceListDate);
            product.setLastImportedAt(now);
            product.setActive(true);

            if (overwriteManualFields) {
                product.setSalePrice(calculateSalePrice(supplierPrice, markupPercent));
            }
            if (makeImportedVisible) {
                product.setVisible(true);
            }

            productRepository.save(product);
            return UpsertOutcome.UPDATED;
        }

        Product product = Product.builder()
                .shopId(shopId)
                .supplierGuid(blankToNull(guid))
                .sourceSheet(sourceSheet)
                .sourceRow(sourceRow)
                .brand(brand)
                .supplierArticle(article)
                .barcode(blankToNull(barcode))
                .name(name)
                .categoryPath(categoryPath)
                .supplierPrice(supplierPrice)
                .salePrice(calculateSalePrice(supplierPrice, markupPercent))
                .currency("RUB")
                .stockQuantity(null)
                .availabilityMode(AvailabilityMode.PREORDER)
                .visible(makeImportedVisible)
                .active(true)
                .imageStatus(ImageStatus.MISSING)
                .priceListDate(priceListDate)
                .lastImportedAt(now)
                .build();

        productRepository.save(product);
        return UpsertOutcome.IMPORTED;
    }

    private Optional<Product> findExistingProduct(
            String shopId, String guid, String barcode, String sourceSheet, String article) {

        if (guid != null && !guid.isBlank()) {
            Optional<Product> byGuid = productRepository.findByShopIdAndSupplierGuid(shopId, guid.trim());
            if (byGuid.isPresent()) {
                return byGuid;
            }
        }
        if (barcode != null && !barcode.isBlank()) {
            Optional<Product> byBarcode = productRepository.findByShopIdAndBarcode(shopId, barcode);
            if (byBarcode.isPresent()) {
                return byBarcode;
            }
        }
        return productRepository.findByShopIdAndSourceSheetAndSupplierArticle(shopId, sourceSheet, article);
    }

    private BigDecimal calculateSalePrice(BigDecimal supplierPrice, BigDecimal markupPercent) {
        if (supplierPrice == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal multiplier = BigDecimal.ONE.add(
                markupPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return supplierPrice.multiply(multiplier).setScale(0, RoundingMode.HALF_UP);
    }

    private boolean isCategoryRow(String brand, String article, String name, BigDecimal price) {
        boolean brandFilled = brand != null && !brand.isBlank();
        boolean articleBlank = article == null || article.isBlank();
        boolean nameBlank = name == null || name.isBlank();
        boolean priceBlank = price == null;
        return brandFilled && articleBlank && nameBlank && priceBlank;
    }

    private boolean isProductRow(String brand, String article, String name, BigDecimal supplierPrice) {
        return brand != null && !brand.isBlank()
                && article != null && !article.isBlank()
                && name != null && !name.isBlank()
                && supplierPrice != null;
    }

    private LocalDate parsePriceListDate(Row row) {
        if (row == null) {
            return null;
        }
        for (int i = 0; i <= row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell == null) {
                continue;
            }
            String text = cellAsString(cell);
            if (text == null) {
                continue;
            }
            Matcher matcher = PRICE_DATE_PATTERN.matcher(text);
            if (matcher.find()) {
                int day = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int year = Integer.parseInt(matcher.group(3));
                return LocalDate.of(year, month, day);
            }
        }
        return null;
    }

    private String cellAsString(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue())
                        .stripTrailingZeros()
                        .toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue().trim();
                } catch (IllegalStateException e) {
                    yield BigDecimal.valueOf(cell.getNumericCellValue())
                            .stripTrailingZeros()
                            .toPlainString();
                }
            }
            default -> null;
        };
    }

    private BigDecimal cellAsBigDecimal(Cell cell) {
        if (cell == null) {
            return null;
        }
        try {
            return switch (cell.getCellType()) {
                case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
                case STRING -> {
                    String value = cell.getStringCellValue();
                    if (value == null || value.isBlank()) {
                        yield null;
                    }
                    String normalized = value.trim()
                            .replace(" ", "")
                            .replace(",", ".");
                    yield new BigDecimal(normalized);
                }
                case FORMULA -> BigDecimal.valueOf(cell.getNumericCellValue());
                default -> null;
            };
        } catch (NumberFormatException | IllegalStateException e) {
            return null;
        }
    }

    private String normalizeBarcode(Cell cell) {
        String value = cellAsString(cell);
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.contains("E") || value.contains("e")) {
            try {
                return new BigDecimal(value).toPlainString();
            } catch (NumberFormatException ignored) {
                return value.trim();
            }
        }
        if (value.endsWith(".0")) {
            return value.substring(0, value.length() - 2);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    enum ImportFormat {
        STANDARD, SIMPLE, UNKNOWN
    }

    record SimpleSheetLayout(
            int sheetIndex,
            int headerRowIndex,
            int articleColumn,
            int nameColumn,
            int priceColumn
    ) {}

    private static final class ImportCounters {
        private int imported;
        private int updated;
        private int skipped;
        private int totalRows;
    }

    private enum UpsertOutcome {
        IMPORTED, UPDATED
    }

    public record ImportResult(
            Long batchId,
            String filename,
            LocalDate priceListDate,
            int totalRows,
            int importedCount,
            int updatedCount,
            int skippedCount,
            String status,
            String errorMessage
    ) {}
}
