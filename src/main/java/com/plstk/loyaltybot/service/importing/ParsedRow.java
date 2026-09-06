package com.plstk.loyaltybot.service.importing;

import java.util.Map;

/**
 * One data row read from a supplier workbook. {@code rawValues} only contains target fields that
 * were actually resolved to a header column; a per-row failure never throws, it just sets
 * {@code valid=false} with a human-readable {@code invalidReason} so one bad row cannot fail the
 * whole batch.
 */
public record ParsedRow(
        String sheetName,
        int sourceRowNumber,
        Map<String, String> rawValues,
        boolean valid,
        String invalidReason) {
}
