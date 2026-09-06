package com.plstk.loyaltybot.service.importing;

import java.util.List;

/**
 * Parse result for one sheet selected by {@link LayoutRuleDefinition#getSheetSelectors()} and
 * actually present in the workbook. {@code headerResolved=false} means one or more structurally
 * required target fields (rawName/supplierPrice/an identifier) could not be located on the header
 * row — the sheet contributes zero rows in that case rather than guessing column positions.
 */
public record SheetParseResult(
        String sheetName,
        boolean headerResolved,
        List<String> missingRequiredFields,
        List<String> headerRowValues,
        List<ParsedRow> rows) {
}
