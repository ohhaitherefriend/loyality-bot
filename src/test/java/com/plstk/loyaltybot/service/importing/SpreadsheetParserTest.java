package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.service.importing.fixtures.SupplierWorkbookFixtures;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpreadsheetParserTest {

    private final SupplierImportProperties properties = new SupplierImportProperties();
    private final SpreadsheetParser parser = new SpreadsheetParser(properties);
    private final LayoutRuleValidator validator = new LayoutRuleValidator(new ObjectMapper());

    private LayoutRuleDefinition standardRule() {
        LayoutRuleValidationResult result = validator.validate(SupplierWorkbookFixtures.standardLayoutRuleJson());
        assertTrue(result.valid(), () -> "fixture rule must be valid, errors=" + result.errors());
        return result.rule();
    }

    @Test
    void parse_standardLayout_splitsValidAndInvalidRowsWithoutFailingTheSheet() {
        Workbook workbook = SupplierWorkbookFixtures.standardLayoutWorkbook();
        LayoutRuleDefinition rule = standardRule();

        WorkbookParseResult result = parser.parse(workbook, rule, null);

        // Cosmetics: 2 valid + 1 invalid (missing price) + 1 valid = 4 rows (category/blank rows skipped).
        // Perfume: 2 valid rows.
        assertEquals(6, result.totalRows());
        assertEquals(5, result.validRows());
        assertEquals(1, result.invalidRows());
        assertTrue(result.anySheetHeaderResolved());

        List<ParsedRow> invalidRows = result.allRows().stream().filter(r -> !r.valid()).toList();
        assertEquals(1, invalidRows.size());
        assertEquals("ART-3", invalidRows.get(0).rawValues().get(LayoutRuleDefinition.FIELD_EXTERNAL_SKU));
        assertTrue(invalidRows.get(0).invalidReason().toLowerCase().contains("price"));
    }

    @Test
    void parse_categoryAndBlankRows_areSkippedEntirely_notCountedAsInvalid() {
        Workbook workbook = SupplierWorkbookFixtures.standardLayoutWorkbook();
        LayoutRuleDefinition rule = standardRule();

        WorkbookParseResult result = parser.parse(workbook, rule, null);

        boolean anyRowMentionsCategoryBrand = result.allRows().stream()
                .anyMatch(r -> "Уход за лицом".equals(r.rawValues().get(LayoutRuleDefinition.FIELD_BRAND)));
        assertFalse(anyRowMentionsCategoryBrand, "category row must be skipped by skipRules, not parsed as data");
    }

    @Test
    void headerSignature_matchesForStandardLayout_andRejectsDrift() {
        Workbook standard = SupplierWorkbookFixtures.standardLayoutWorkbook();
        LayoutRuleDefinition rule = standardRule();
        Map<String, List<String>> signature = parser.computeHeaderSignature(standard, rule);
        assertFalse(signature.isEmpty());
        rule.setExpectedHeaderSignature(signature);

        assertTrue(parser.matchesKnownLayout(standard, rule), "same file must match its own signature");

        Workbook drifted = SupplierWorkbookFixtures.driftedLayoutWorkbook();
        assertFalse(parser.matchesKnownLayout(drifted, rule), "drifted layout must NOT match the old signature");
    }

    @Test
    void parse_missingPriceColumn_collapsesValidRowRatio() {
        Workbook workbook = SupplierWorkbookFixtures.standardLayoutWithMissingPricesWorkbook();
        LayoutRuleDefinition rule = standardRule();

        WorkbookParseResult result = parser.parse(workbook, rule, null);

        assertTrue(result.validRowRatio() < 0.5,
                () -> "expected collapsed valid ratio, got " + result.validRowRatio());
    }

    @Test
    void sampleForAiDetection_isBoundedAndIncludesHeaderRow() {
        Workbook workbook = SupplierWorkbookFixtures.standardLayoutWorkbook();

        List<LayoutSheetSample> samples = parser.sampleForAiDetection(workbook);

        assertEquals(2, samples.size());
        LayoutSheetSample cosmetics = samples.get(0);
        assertEquals(SupplierWorkbookFixtures.SHEET_COSMETICS, cosmetics.sheetName());
        assertTrue(cosmetics.sampleRows().size() <= properties.getParser().getAiSampleRows());
        // header row is index 5 (0-based), well within the default 15-row sample.
        List<String> headerRowSample = cosmetics.sampleRows().get(5);
        assertTrue(headerRowSample.contains("Номенклатура"));
        assertTrue(headerRowSample.contains("Цена"));
    }

    @Test
    void parse_ruleWithoutMappedRequiredField_leavesSheetUnresolved() {
        Workbook workbook = SupplierWorkbookFixtures.standardLayoutWorkbook();
        LayoutRuleDefinition rule = LayoutRuleDefinition.builder()
                .sheetSelectors(List.of(SupplierWorkbookFixtures.SHEET_COSMETICS))
                .headerRow(6)
                .firstDataRow(7)
                .columns(Map.of(
                        LayoutRuleDefinition.FIELD_RAW_NAME,
                        LayoutColumnMapping.builder().headerAliases(List.of("НетТакогоЗаголовка")).type(LayoutColumnType.STRING).build(),
                        LayoutRuleDefinition.FIELD_SUPPLIER_PRICE,
                        LayoutColumnMapping.builder().headerAliases(List.of("Цена")).type(LayoutColumnType.DECIMAL).build(),
                        LayoutRuleDefinition.FIELD_EXTERNAL_SKU,
                        LayoutColumnMapping.builder().headerAliases(List.of("Артикул")).type(LayoutColumnType.STRING).build()))
                .build();

        WorkbookParseResult result = parser.parse(workbook, rule, null);

        assertEquals(0, result.totalRows());
        assertFalse(result.anySheetHeaderResolved());
    }
}
