package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Safe Apache POI reader shared by AI sampling, known-layout signature checks, automatic rule
 * preview and full parsing. Safety posture (docs/ARCHITECTURE.md §11):
 * <ul>
 *   <li>relies on Apache POI's built-in {@code ZipSecureFile} zip-bomb protection (never relaxed);</li>
 *   <li>never evaluates formulas — only cached values are ever read (see {@link #cellAsRawString});</li>
 *   <li>hard caps on sheets/rows/columns/cell length from {@code supplier-import.parser.*};</li>
 *   <li>a single bad row is caught and marked invalid, never propagated to fail the whole sheet/batch.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpreadsheetParser {

    private final SupplierImportProperties properties;

    public List<LayoutSheetSample> sampleForAiDetection(Workbook workbook) {
        SupplierImportProperties.Parser cfg = properties.getParser();
        List<LayoutSheetSample> samples = new ArrayList<>();
        int sheetLimit = Math.min(workbook.getNumberOfSheets(), cfg.getMaxSheets());
        for (int sheetIndex = 0; sheetIndex < sheetLimit; sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int totalRowCount = sheet.getLastRowNum() + 1;
            int totalColumnCount = 0;
            List<List<String>> sampleRows = new ArrayList<>();
            int rowLimit = Math.min(totalRowCount, cfg.getAiSampleRows());
            for (int rowIndex = 0; rowIndex < rowLimit; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    sampleRows.add(List.of());
                    continue;
                }
                int columnLimit = Math.min(row.getLastCellNum(), cfg.getAiSampleColumns());
                List<String> rowValues = new ArrayList<>();
                for (int columnIndex = 0; columnIndex < columnLimit; columnIndex++) {
                    String value = cellAsRawString(row.getCell(columnIndex), cfg.getAiSampleCellMaxLength());
                    rowValues.add(value == null ? "" : value);
                }
                totalColumnCount = Math.max(totalColumnCount, columnLimit);
                sampleRows.add(rowValues);
            }
            samples.add(new LayoutSheetSample(sheet.getSheetName(), totalRowCount, totalColumnCount, sampleRows));
        }
        return samples;
    }

    /**
     * Reads the actual header row for every sheet in {@code rule.sheetSelectors()} that is present
     * in this workbook. Used both to persist a new rule's signature and to check whether a later
     * batch's file matches a known, already-published rule.
     */
    public Map<String, List<String>> computeHeaderSignature(Workbook workbook, LayoutRuleDefinition rule) {
        Map<String, List<String>> signature = new LinkedHashMap<>();
        int headerRowIndex = rule.getHeaderRow() - 1;
        for (String sheetName : rule.getSheetSelectors()) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                continue;
            }
            signature.put(sheetName, readHeaderRowValues(sheet, headerRowIndex));
        }
        return signature;
    }

    /**
     * A rule is reusable without AI only if at least one of its selector sheets is present in the
     * new file and every selector sheet that IS present has an identical header row. Any mismatch
     * (schema drift) or total absence of the selector sheets means "not a known layout" — the caller
     * must fall back to AI detection.
     */
    public boolean matchesKnownLayout(Workbook workbook, LayoutRuleDefinition rule) {
        if (rule.getExpectedHeaderSignature() == null || rule.getExpectedHeaderSignature().isEmpty()) {
            return false;
        }
        Map<String, List<String>> actual = computeHeaderSignature(workbook, rule);
        if (actual.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, List<String>> entry : actual.entrySet()) {
            List<String> expected = rule.getExpectedHeaderSignature().get(entry.getKey());
            if (expected == null || !expected.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public WorkbookParseResult parse(Workbook workbook, LayoutRuleDefinition rule, Integer maxDataRowsOverride) {
        SupplierImportProperties.Parser cfg = properties.getParser();
        int rowCap = maxDataRowsOverride != null
                ? Math.min(maxDataRowsOverride, cfg.getMaxRowsPerSheet())
                : cfg.getMaxRowsPerSheet();

        List<SheetParseResult> sheetResults = new ArrayList<>();
        int totalRows = 0;
        int validRows = 0;
        int invalidRows = 0;

        for (String sheetName : rule.getSheetSelectors()) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                continue;
            }
            SheetParseResult sheetResult = parseSheet(sheet, rule, cfg, rowCap);
            sheetResults.add(sheetResult);
            for (ParsedRow row : sheetResult.rows()) {
                totalRows++;
                if (row.valid()) {
                    validRows++;
                } else {
                    invalidRows++;
                }
            }
        }

        return new WorkbookParseResult(sheetResults, totalRows, validRows, invalidRows);
    }

    private SheetParseResult parseSheet(
            Sheet sheet, LayoutRuleDefinition rule, SupplierImportProperties.Parser cfg, int rowCap) {

        int headerRowIndex = rule.getHeaderRow() - 1;
        int firstDataRowIndex = rule.getFirstDataRow() - 1;
        List<String> headerRowValues = readHeaderRowValues(sheet, headerRowIndex);

        HeaderResolution resolution = resolveHeader(headerRowValues, rule, cfg);
        if (!resolution.missingRequiredFields().isEmpty()) {
            log.info("Sheet '{}' header not fully resolved, missing required fields: {}",
                    sheet.getSheetName(), resolution.missingRequiredFields());
            return new SheetParseResult(
                    sheet.getSheetName(), false, resolution.missingRequiredFields(), headerRowValues, List.of());
        }

        List<ParsedRow> rows = new ArrayList<>();
        int lastRow = Math.min(sheet.getLastRowNum(), firstDataRowIndex + rowCap - 1);
        for (int rowIndex = firstDataRowIndex; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isRowBlank(row, resolution.resolvedColumns(), cfg)) {
                continue;
            }

            ParsedRow parsed;
            try {
                parsed = parseRow(sheet.getSheetName(), rowIndex + 1, row, rule, resolution, cfg);
            } catch (Exception e) {
                // A single row must never fail the sheet/batch.
                parsed = new ParsedRow(
                        sheet.getSheetName(), rowIndex + 1, Map.of(), false,
                        "Unexpected error reading row: " + e.getMessage());
            }
            if (parsed != null) {
                rows.add(parsed);
            }
        }

        return new SheetParseResult(sheet.getSheetName(), true, List.of(), headerRowValues, rows);
    }

    private ParsedRow parseRow(
            String sheetName,
            int sourceRowNumber,
            Row row,
            LayoutRuleDefinition rule,
            HeaderResolution resolution,
            SupplierImportProperties.Parser cfg) {

        Map<String, String> rawValues = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : resolution.resolvedColumns().entrySet()) {
            String value = cellAsRawString(row.getCell(entry.getValue()), cfg.getMaxCellLength());
            if (value != null) {
                rawValues.put(entry.getKey(), value);
            }
        }

        // skipRules: category/subtotal rows are dropped entirely, not counted invalid.
        if (rule.getSkipRules() != null) {
            for (LayoutSkipRule skipRule : rule.getSkipRules()) {
                String value = rawValues.get(skipRule.getColumn());
                if (value != null && value.matches(skipRule.getMatches())) {
                    return null;
                }
            }
        }

        String rawName = rawValues.get(LayoutRuleDefinition.FIELD_RAW_NAME);
        String supplierPriceRaw = rawValues.get(LayoutRuleDefinition.FIELD_SUPPLIER_PRICE);
        String externalSku = rawValues.get(LayoutRuleDefinition.FIELD_EXTERNAL_SKU);
        String barcode = rawValues.get(LayoutRuleDefinition.FIELD_BARCODE);

        if (isBlank(rawName)) {
            return new ParsedRow(sheetName, sourceRowNumber, rawValues, false, "Missing rawName");
        }
        BigDecimal supplierPrice = parseDecimal(supplierPriceRaw);
        if (supplierPrice == null) {
            return new ParsedRow(sheetName, sourceRowNumber, rawValues, false, "Missing/invalid supplierPrice");
        }
        if (isBlank(externalSku) && isBlank(barcode)) {
            return new ParsedRow(sheetName, sourceRowNumber, rawValues, false, "Missing stable identifier (externalSku/barcode)");
        }

        return new ParsedRow(sheetName, sourceRowNumber, rawValues, true, null);
    }

    private HeaderResolution resolveHeader(
            List<String> headerRowValues, LayoutRuleDefinition rule, SupplierImportProperties.Parser cfg) {

        Map<String, Integer> normalizedHeaders = new LinkedHashMap<>();
        for (int i = 0; i < headerRowValues.size() && i < cfg.getMaxColumns(); i++) {
            String normalized = normalizeHeader(headerRowValues.get(i));
            if (normalized != null && !normalized.isEmpty()) {
                normalizedHeaders.putIfAbsent(normalized, i);
            }
        }

        Map<String, Integer> resolvedColumns = new LinkedHashMap<>();
        List<String> missingRequired = new ArrayList<>();

        for (Map.Entry<String, LayoutColumnMapping> entry : rule.getColumns().entrySet()) {
            String field = entry.getKey();
            LayoutColumnMapping mapping = entry.getValue();
            Integer columnIndex = null;
            if (mapping.getHeaderAliases() != null) {
                for (String alias : mapping.getHeaderAliases()) {
                    String normalizedAlias = normalizeHeader(alias);
                    Integer candidate = normalizedHeaders.get(normalizedAlias);
                    if (candidate != null) {
                        columnIndex = candidate;
                        break;
                    }
                }
            }
            if (columnIndex != null) {
                resolvedColumns.put(field, columnIndex);
            } else if (isStructurallyRequired(field, rule)) {
                missingRequired.add(field);
            }
        }

        return new HeaderResolution(resolvedColumns, missingRequired);
    }

    /** rawName/supplierPrice are always required; an identifier is required only as a group. */
    private boolean isStructurallyRequired(String field, LayoutRuleDefinition rule) {
        if (LayoutRuleDefinition.FIELD_RAW_NAME.equals(field) || LayoutRuleDefinition.FIELD_SUPPLIER_PRICE.equals(field)) {
            return true;
        }
        if (LayoutRuleDefinition.IDENTIFIER_FIELDS.contains(field)) {
            boolean anyIdentifierMapped = LayoutRuleDefinition.IDENTIFIER_FIELDS.stream()
                    .anyMatch(rule.getColumns()::containsKey);
            // Only flag as "missing" if this is the sole identifier field declared by the rule.
            return anyIdentifierMapped && rule.getColumns().keySet().stream()
                    .filter(LayoutRuleDefinition.IDENTIFIER_FIELDS::contains)
                    .count() == 1;
        }
        return false;
    }

    private boolean isRowBlank(Row row, Map<String, Integer> resolvedColumns, SupplierImportProperties.Parser cfg) {
        for (Integer columnIndex : resolvedColumns.values()) {
            String value = cellAsRawString(row.getCell(columnIndex), cfg.getMaxCellLength());
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private List<String> readHeaderRowValues(Sheet sheet, int headerRowIndex) {
        Row headerRow = sheet.getRow(headerRowIndex);
        if (headerRow == null) {
            return List.of();
        }
        SupplierImportProperties.Parser cfg = properties.getParser();
        int columnLimit = Math.min(headerRow.getLastCellNum(), cfg.getMaxColumns());
        List<String> values = new ArrayList<>();
        for (int i = 0; i < columnLimit; i++) {
            String value = cellAsRawString(headerRow.getCell(i), cfg.getMaxCellLength());
            values.add(value == null ? "" : value.trim());
        }
        return values;
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal parseDecimal(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            String normalized = value.trim().replace(" ", "").replace(",", ".");
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads a cell as a trimmed, length-capped string without ever evaluating formulas: FORMULA
     * cells only ever surface their already-cached value (string or numeric), matching the existing
     * {@code ProductImportService.cellAsString} convention.
     */
    private String cellAsRawString(Cell cell, int maxLength) {
        if (cell == null) {
            return null;
        }
        String value = switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cachedFormulaValue(cell);
            default -> null;
        };
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.length() > maxLength) {
            value = value.substring(0, maxLength);
        }
        return value;
    }

    private String cachedFormulaValue(Cell cell) {
        try {
            return cell.getStringCellValue();
        } catch (IllegalStateException e) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private record HeaderResolution(Map<String, Integer> resolvedColumns, List<String> missingRequiredFields) {
        HeaderResolution {
            Objects.requireNonNull(resolvedColumns);
            Objects.requireNonNull(missingRequiredFields);
        }
    }
}
