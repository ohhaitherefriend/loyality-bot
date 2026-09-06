package com.plstk.loyaltybot.service.importing;

import org.springframework.stereotype.Component;

/**
 * Wired instead of {@link DeepSeekSpreadsheetLayoutDetector} when no API key is configured. The
 * application must start fine with no AI provider enabled; any batch that actually needs AI layout
 * detection (no reusable known-layout rule) is safely quarantined with a clear reason instead of the
 * pipeline crashing or, worse, guessing a layout.
 */
@Component
public class DisabledSpreadsheetLayoutDetector implements AiSpreadsheetLayoutDetector {

    @Override
    public LayoutDetectionResponse detect(LayoutDetectionRequest request) {
        return LayoutDetectionResponse.failure(
                "AI layout detection is disabled (no DeepSeek API key configured)", false, "disabled");
    }
}
