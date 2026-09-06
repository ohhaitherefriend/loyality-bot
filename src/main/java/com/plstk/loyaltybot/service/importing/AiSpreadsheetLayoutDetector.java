package com.plstk.loyaltybot.service.importing;

/**
 * Provider-neutral interface: determines the column mapping/structure of an unknown supplier
 * workbook. See {@link DeepSeekSpreadsheetLayoutDetector} for the DeepSeek implementation and
 * {@link DisabledSpreadsheetLayoutDetector} for the no-op used when no provider is configured.
 * Never receives more than {@link LayoutDetectionRequest} (bounded workbook metadata/sample rows)
 * and must never be trusted directly — the caller always runs the response through
 * {@link LayoutRuleValidator} and an automatic preview parse before publishing a rule version.
 */
public interface AiSpreadsheetLayoutDetector {

    LayoutDetectionResponse detect(LayoutDetectionRequest request);
}
