package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.service.importing.fixtures.SupplierWorkbookFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutRuleValidatorTest {

    private final LayoutRuleValidator validator = new LayoutRuleValidator(new ObjectMapper());

    @Test
    void validRule_isAccepted() {
        LayoutRuleValidationResult result = validator.validate(SupplierWorkbookFixtures.standardLayoutRuleJson());

        assertTrue(result.valid(), () -> "expected valid, errors=" + result.errors());
        assertEquals(2, result.rule().getSheetSelectors().size());
        assertEquals(6, result.rule().getHeaderRow());
        assertTrue(result.rule().getColumns().containsKey(LayoutRuleDefinition.FIELD_SUPPLIER_PRICE));
    }

    @Test
    void inventedColumn_isRejectedBySchema() {
        String json = """
                {
                  "sheetSelectors": ["Косметика и уход"],
                  "headerRow": 6,
                  "firstDataRow": 7,
                  "columns": {
                    "rawName": {"headerAliases": ["Номенклатура"], "type": "STRING"},
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL"},
                    "externalSku": {"headerAliases": ["Артикул"], "type": "STRING"},
                    "totallyInventedField": {"headerAliases": ["Что-то"], "type": "STRING"}
                  }
                }
                """;

        LayoutRuleValidationResult result = validator.validate(json);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.toLowerCase().contains("additional")
                        || e.toLowerCase().contains("invented")),
                () -> "expected an additionalProperties/invented-field error, got " + result.errors());
    }

    @Test
    void malformedJson_isRejected() {
        LayoutRuleValidationResult result = validator.validate("{ this is not : valid json ][");

        assertFalse(result.valid());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void emptyResponse_isRejected() {
        assertFalse(validator.validate("").valid());
        assertFalse(validator.validate(null).valid());
        assertFalse(validator.validate("   ").valid());
    }

    @Test
    void missingRequiredRawName_isRejected() {
        String json = """
                {
                  "sheetSelectors": ["Косметика и уход"],
                  "headerRow": 6,
                  "firstDataRow": 7,
                  "columns": {
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL"},
                    "externalSku": {"headerAliases": ["Артикул"], "type": "STRING"}
                  }
                }
                """;

        LayoutRuleValidationResult result = validator.validate(json);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("rawName")));
    }

    @Test
    void missingIdentifier_isRejected() {
        String json = """
                {
                  "sheetSelectors": ["Косметика и уход"],
                  "headerRow": 6,
                  "firstDataRow": 7,
                  "columns": {
                    "rawName": {"headerAliases": ["Номенклатура"], "type": "STRING"},
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL"}
                  }
                }
                """;

        LayoutRuleValidationResult result = validator.validate(json);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.toLowerCase().contains("identifier")));
    }

    @Test
    void ambiguousDuplicateAlias_isRejected() {
        String json = """
                {
                  "sheetSelectors": ["Косметика и уход"],
                  "headerRow": 6,
                  "firstDataRow": 7,
                  "columns": {
                    "rawName": {"headerAliases": ["Номенклатура", "Артикул"], "type": "STRING"},
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL"},
                    "externalSku": {"headerAliases": ["Артикул"], "type": "STRING"}
                  }
                }
                """;

        LayoutRuleValidationResult result = validator.validate(json);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Артикул")));
    }

    @Test
    void headerRowNotBeforeFirstDataRow_isRejected() {
        String json = """
                {
                  "sheetSelectors": ["Косметика и уход"],
                  "headerRow": 7,
                  "firstDataRow": 7,
                  "columns": {
                    "rawName": {"headerAliases": ["Номенклатура"], "type": "STRING"},
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL"},
                    "externalSku": {"headerAliases": ["Артикул"], "type": "STRING"}
                  }
                }
                """;

        LayoutRuleValidationResult result = validator.validate(json);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("firstDataRow")));
    }

    @Test
    void aiResponseWrappedInMarkdownFence_isStillExtracted() {
        String wrapped = "Here is the rule:\n```json\n" + SupplierWorkbookFixtures.standardLayoutRuleJson() + "\n```";

        LayoutRuleValidationResult result = validator.validate(wrapped);

        assertTrue(result.valid(), () -> "expected valid, errors=" + result.errors());
    }
}
