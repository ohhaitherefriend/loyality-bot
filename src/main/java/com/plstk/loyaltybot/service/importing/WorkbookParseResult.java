package com.plstk.loyaltybot.service.importing;

import java.util.List;

public record WorkbookParseResult(
        List<SheetParseResult> sheets,
        int totalRows,
        int validRows,
        int invalidRows) {

    public double validRowRatio() {
        return totalRows == 0 ? 0.0 : (double) validRows / totalRows;
    }

    public boolean anySheetHeaderResolved() {
        return sheets.stream().anyMatch(SheetParseResult::headerResolved);
    }

    public List<ParsedRow> allRows() {
        return sheets.stream().flatMap(s -> s.rows().stream()).toList();
    }
}
