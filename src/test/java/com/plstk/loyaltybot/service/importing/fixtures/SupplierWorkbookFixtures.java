package com.plstk.loyaltybot.service.importing.fixtures;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Builds in-memory XLSX workbooks matching the real supplier price file structure documented in
 * the workspace rules (sheets "Косметика и уход"/"Парфюмерия", header on row 6, columns
 * GUID/Бренд/Артикул/Штрихкод/Номенклатура/Цена/Заказ/Сумма) and a schema-drift variant, without
 * ever committing a binary fixture file to the repository.
 */
public final class SupplierWorkbookFixtures {

    public static final String SHEET_COSMETICS = "Косметика и уход";
    public static final String SHEET_PERFUME = "Парфюмерия";

    private static final int PRICE_DATE_ROW_INDEX = 3; // human row 4
    private static final int STANDARD_HEADER_ROW_INDEX = 5; // human row 6
    private static final int DRIFTED_HEADER_ROW_INDEX = 2; // human row 3

    private SupplierWorkbookFixtures() {
    }

    public record FixtureRow(
            String guid, String brand, String article, String barcode, String name, BigDecimal price) {

        static FixtureRow category(String brand) {
            return new FixtureRow(null, brand, null, null, null, null);
        }

        static FixtureRow blank() {
            return new FixtureRow(null, null, null, null, null, null);
        }
    }

    /** Standard layout: header row 6, matches docs/workspace rule column positions exactly. */
    public static Workbook standardLayoutWorkbook() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        buildStandardSheet(workbook, SHEET_COSMETICS, cosmeticsRows());
        buildStandardSheet(workbook, SHEET_PERFUME, perfumeRows());
        return workbook;
    }

    /**
     * Same standard layout/header positions as {@link #standardLayoutWorkbook()}, but with
     * caller-supplied row content on both sheets - lets end-to-end reconciliation tests build a
     * sequence of snapshots for the same {@code SupplierSource} where specific SKUs appear,
     * disappear and reappear across batches while still reusing the one published rule version
     * (same header signature every time, so no repeated AI layout detection).
     */
    public static Workbook standardLayoutWorkbook(List<FixtureRow> cosmeticsRows, List<FixtureRow> perfumeRows) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        buildStandardSheet(workbook, SHEET_COSMETICS, cosmeticsRows);
        buildStandardSheet(workbook, SHEET_PERFUME, perfumeRows);
        return workbook;
    }

    public static FixtureRow row(String article, String brand, String name, BigDecimal price) {
        return new FixtureRow("guid-" + article, brand, article, null, name, price);
    }

    /** Same standard layout, but the cosmetics sheet's price column is entirely blank/garbage. */
    public static Workbook standardLayoutWithMissingPricesWorkbook() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        List<FixtureRow> rowsWithoutPrice = cosmeticsRows().stream()
                .map(r -> new FixtureRow(r.guid(), r.brand(), r.article(), r.barcode(), r.name(), null))
                .toList();
        buildStandardSheet(workbook, SHEET_COSMETICS, rowsWithoutPrice);
        buildStandardSheet(workbook, SHEET_PERFUME, perfumeRows());
        return workbook;
    }

    /**
     * Schema drift fixture: same data, but header moved to row 3, column order shifted and one
     * header renamed ("Наименование" instead of "Номенклатура") — simulates a supplier changing
     * their export template. A rule published against the standard layout must NOT match this file.
     */
    public static Workbook driftedLayoutWorkbook() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(SHEET_COSMETICS);
        Row header = sheet.createRow(DRIFTED_HEADER_ROW_INDEX);
        String[] headers = {"Артикул", "Наименование", "Бренд", "Штрихкод", "Цена"};
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        int rowIndex = DRIFTED_HEADER_ROW_INDEX + 1;
        for (FixtureRow row : cosmeticsRows()) {
            if (row.article() == null && row.name() == null) {
                continue; // skip category/blank rows for the drift fixture, keep it minimal
            }
            Row r = sheet.createRow(rowIndex++);
            r.createCell(0).setCellValue(row.article());
            r.createCell(1).setCellValue(row.name());
            r.createCell(2).setCellValue(row.brand());
            if (row.barcode() != null) {
                r.createCell(3).setCellValue(row.barcode());
            }
            if (row.price() != null) {
                r.createCell(4).setCellValue(row.price().doubleValue());
            }
        }
        return workbook;
    }

    public static byte[] toBytes(Workbook workbook) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<FixtureRow> cosmeticsRows() {
        return List.of(
                new FixtureRow("guid-1", "L'Oreal", "ART-1", "4600000000017", "Крем для лица 50 мл", new BigDecimal("450.00")),
                new FixtureRow("guid-2", "Nivea", "ART-2", "4600000000024", "Крем для рук 100 мл", new BigDecimal("220.50")),
                FixtureRow.category("Уход за лицом"),
                new FixtureRow("guid-3", "Nivea", "ART-3", "4600000000031", "Гель для душа 250 мл", null),
                FixtureRow.blank(),
                new FixtureRow("guid-4", "Garnier", "ART-4", "4600000000048", "Шампунь 400 мл", new BigDecimal("310.00"))
        );
    }

    private static List<FixtureRow> perfumeRows() {
        return List.of(
                new FixtureRow("guid-10", "Chanel", "PRF-1", "3145891234560", "Chanel No 5 100 ml", new BigDecimal("12500.00")),
                new FixtureRow("guid-11", "Dior", "PRF-2", "3348901234561", "Sauvage 100 ml", new BigDecimal("9800.00"))
        );
    }

    private static void buildStandardSheet(XSSFWorkbook workbook, String sheetName, List<FixtureRow> rows) {
        Sheet sheet = workbook.createSheet(sheetName);

        Row priceDateRow = sheet.createRow(PRICE_DATE_ROW_INDEX);
        priceDateRow.createCell(0).setCellValue("Цены указаны на: 25.06.2026");

        Row header = sheet.createRow(STANDARD_HEADER_ROW_INDEX);
        String[] headers = {"GUID", "", "", "Бренд", "Артикул", "Штрихкод", "Номенклатура", "Цена", "", "Заказ", "Сумма"};
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }

        int rowIndex = STANDARD_HEADER_ROW_INDEX + 1;
        for (FixtureRow row : rows) {
            Row r = sheet.createRow(rowIndex++);
            if (row.guid() != null) {
                r.createCell(0).setCellValue(row.guid());
            }
            if (row.brand() != null) {
                r.createCell(3).setCellValue(row.brand());
            }
            if (row.article() != null) {
                r.createCell(4).setCellValue(row.article());
            }
            if (row.barcode() != null) {
                r.createCell(5).setCellValue(row.barcode());
            }
            if (row.name() != null) {
                r.createCell(6).setCellValue(row.name());
            }
            if (row.price() != null) {
                r.createCell(7).setCellValue(row.price().doubleValue());
            }
        }
    }

    /** The strict JSON rule a human/AI would produce for {@link #standardLayoutWorkbook()}. */
    public static String standardLayoutRuleJson() {
        return """
                {
                  "sheetSelectors": ["%s", "%s"],
                  "headerRow": 6,
                  "firstDataRow": 7,
                  "columns": {
                    "externalSku": {"headerAliases": ["Артикул"], "type": "STRING", "required": true},
                    "barcode": {"headerAliases": ["Штрихкод"], "type": "BARCODE", "required": false},
                    "rawName": {"headerAliases": ["Номенклатура"], "type": "STRING", "required": true},
                    "brand": {"headerAliases": ["Бренд"], "type": "STRING", "required": false},
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL", "required": true}
                  },
                  "skipRules": [
                    {"column": "brand", "matches": "^Уход за лицом$"}
                  ],
                  "defaults": {"currency": "RUB"}
                }
                """.formatted(SHEET_COSMETICS, SHEET_PERFUME);
    }
}
