package com.plstk.loyaltybot.service.importing;

import org.springframework.stereotype.Component;

/**
 * Wired instead of {@link DeepSeekCatalogMatcher} when no API key is configured. The application
 * must start fine with no AI provider enabled; any row that actually needs AI matching (no
 * deterministic link/barcode/fingerprint match) is safely routed to {@code NEEDS_REVIEW} instead of
 * the pipeline crashing or, worse, guessing a match.
 */
@Component
public class DisabledCatalogMatcher implements AiCatalogMatcher {

    @Override
    public AiMatchResponse match(AiMatchRequest request) {
        return AiMatchResponse.failure("AI catalog matching is disabled (no DeepSeek API key configured)", false, "disabled");
    }
}
