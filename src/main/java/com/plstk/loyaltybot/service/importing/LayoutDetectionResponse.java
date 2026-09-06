package com.plstk.loyaltybot.service.importing;

/**
 * Result of one {@link AiSpreadsheetLayoutDetector#detect} call. {@code success=true} only means a
 * JSON-looking response was obtained from the provider — it says nothing about whether the JSON is a
 * *valid* rule; that is {@link LayoutRuleValidator}'s job. {@code retryableFailure} tells the caller
 * whether the underlying error class is retryable (timeout/429/5xx) purely for audit/logging; the
 * detector implementation itself already performs the retry/backoff before returning a failure.
 */
public record LayoutDetectionResponse(
        boolean success,
        String rawContent,
        String errorMessage,
        boolean retryableFailure,
        String provider,
        String model,
        long latencyMs) {

    public static LayoutDetectionResponse success(String rawContent, String provider, String model, long latencyMs) {
        return new LayoutDetectionResponse(true, rawContent, null, false, provider, model, latencyMs);
    }

    public static LayoutDetectionResponse failure(String errorMessage, boolean retryableFailure, String provider) {
        return new LayoutDetectionResponse(false, null, errorMessage, retryableFailure, provider, null, 0L);
    }
}
