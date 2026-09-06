package com.plstk.loyaltybot.service.importing;

/**
 * Result of one {@link AiCatalogMatcher#match} call. {@code success=true} only means a JSON-looking
 * response was obtained from the provider - it says nothing about whether the JSON is a *valid*
 * match response; that is {@link CatalogMatchResponseValidator}'s job. {@code retryableFailure}
 * tells the caller whether the underlying error class is retryable (timeout/429/5xx) purely for
 * audit/logging; the matcher implementation itself already performs retry/backoff before returning
 * a failure. {@code promptTokens}/{@code completionTokens}/{@code promptVersion} exist purely for
 * the {@code MatchDecision} audit trail (Prompt 05 explicit requirement) and may be {@code null}
 * when unavailable (e.g. disabled provider, or a provider that doesn't report usage).
 */
public record AiMatchResponse(
        boolean success,
        String rawContent,
        String errorMessage,
        boolean retryableFailure,
        String provider,
        String model,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs) {

    public static AiMatchResponse success(
            String rawContent, String provider, String model, String promptVersion,
            Integer promptTokens, Integer completionTokens, long latencyMs) {
        return new AiMatchResponse(
                true, rawContent, null, false, provider, model, promptVersion, promptTokens, completionTokens, latencyMs);
    }

    public static AiMatchResponse failure(String errorMessage, boolean retryableFailure, String provider) {
        return new AiMatchResponse(false, null, errorMessage, retryableFailure, provider, null, null, null, null, 0L);
    }
}
