package com.plstk.loyaltybot.service.importing;

import java.util.List;

/**
 * Bounded, PII-free sample of one workbook sheet sent to {@link AiSpreadsheetLayoutDetector}: sheet
 * metadata plus a small number of leading rows so the model can locate the header row and infer
 * column semantics. Row/column counts and cell lengths are already truncated by the caller
 * ({@link SpreadsheetParser#sampleForAiDetection}) according to {@code supplier-import.parser.*}.
 */
public record LayoutSheetSample(
        String sheetName,
        int totalRowCount,
        int totalColumnCount,
        List<List<String>> sampleRows) {
}
