package com.plstk.loyaltybot.service.importing;

/**
 * Provider-neutral interface: given one supplier row and at most {@code matching.max-candidates}
 * real catalog candidates, selects the single best {@code candidateId} or {@code NO_MATCH}. See
 * {@link DeepSeekCatalogMatcher} for the DeepSeek implementation and {@link DisabledCatalogMatcher}
 * for the no-op used when no provider is configured. Never receives more than {@link AiMatchRequest}
 * (bounded row + candidate attributes) and must never be trusted directly - the caller always runs
 * the response through {@link CatalogMatchResponseValidator} and its own critical-attribute-conflict
 * check before ever promoting a row to {@code AUTO_APPROVED} (docs/ARCHITECTURE.md §9.4/§10, D-009).
 */
public interface AiCatalogMatcher {

    AiMatchResponse match(AiMatchRequest request);
}
