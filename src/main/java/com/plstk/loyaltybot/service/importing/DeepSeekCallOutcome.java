package com.plstk.loyaltybot.service.importing;

/**
 * Transport-level result of one {@link DeepSeekHttpClient#chatCompletion} call, shared by
 * {@link DeepSeekCatalogMatcher} and {@link DeepSeekSpreadsheetLayoutDetector}. Deliberately says
 * nothing about whether {@link #content()} is semantically valid — that is each caller's own
 * validator's job (e.g. {@code CatalogMatchResponseValidator}, {@code LayoutRuleValidator}).
 */
public record DeepSeekCallOutcome(
        boolean success,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs,
        String errorMessage,
        boolean retryableFailure) {

    public static DeepSeekCallOutcome success(
            String content, Integer promptTokens, Integer completionTokens, long latencyMs) {
        return new DeepSeekCallOutcome(true, content, promptTokens, completionTokens, latencyMs, null, false);
    }

    public static DeepSeekCallOutcome failure(String errorMessage, boolean retryableFailure) {
        return new DeepSeekCallOutcome(false, null, null, null, 0L, errorMessage, retryableFailure);
    }
}
