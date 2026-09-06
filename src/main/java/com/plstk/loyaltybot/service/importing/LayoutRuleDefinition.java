package com.plstk.loyaltybot.service.importing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable-once-persisted, provider-neutral column mapping rule for one supplier workbook layout.
 * Serialized as the JSON stored in {@link com.plstk.loyaltybot.entity.importing.ImportRuleVersion#getRuleDefinition()}.
 * Structure matches docs/ARCHITECTURE.md §8, restricted to the closed target-field vocabulary below
 * so neither a human nor DeepSeek can smuggle in an arbitrary expression/column.
 *
 * <p>{@code expectedHeaderSignature} is populated by the backend (never trusted from AI) from the
 * actual header row of the file this rule version was created from. A later batch reuses this rule
 * without calling AI only if its own header row for the same sheet matches this signature exactly —
 * see {@link SpreadsheetParser#matchesKnownLayout}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LayoutRuleDefinition {

    public static final String FIELD_EXTERNAL_SKU = "externalSku";
    public static final String FIELD_BARCODE = "barcode";
    public static final String FIELD_RAW_NAME = "rawName";
    public static final String FIELD_BRAND = "brand";
    public static final String FIELD_SUPPLIER_PRICE = "supplierPrice";
    public static final String FIELD_STOCK = "stock";

    /** Closed vocabulary: schema also enforces this via {@code additionalProperties:false}. */
    public static final Set<String> ALLOWED_FIELDS = Set.of(
            FIELD_EXTERNAL_SKU, FIELD_BARCODE, FIELD_RAW_NAME, FIELD_BRAND, FIELD_SUPPLIER_PRICE, FIELD_STOCK);

    /** At least one of these must resolve on a row for it to have a stable identifier. */
    public static final Set<String> IDENTIFIER_FIELDS = Set.of(FIELD_EXTERNAL_SKU, FIELD_BARCODE);

    private List<String> sheetSelectors;

    /** 1-based row number containing column headers (e.g. 6 for the known supplier price file). */
    private Integer headerRow;

    /** 1-based row number of the first data row (typically headerRow + 1). */
    private Integer firstDataRow;

    private Map<String, LayoutColumnMapping> columns;

    private List<LayoutSkipRule> skipRules;

    private Map<String, String> defaults;

    /** sheetName -> trimmed header cell values read at headerRow when this rule was created/reused. */
    private Map<String, List<String>> expectedHeaderSignature;
}
