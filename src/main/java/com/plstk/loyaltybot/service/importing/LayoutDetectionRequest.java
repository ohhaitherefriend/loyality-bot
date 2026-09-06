package com.plstk.loyaltybot.service.importing;

import java.util.List;

/**
 * Input to {@link AiSpreadsheetLayoutDetector#detect}. Deliberately carries only workbook
 * structure/headers/sample rows — never mailbox/email metadata, buyer PII, shop secrets or API keys.
 */
public record LayoutDetectionRequest(
        String supplierSourceLabel,
        String snapshotScope,
        List<LayoutSheetSample> sheets) {
}
