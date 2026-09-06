package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.MatchDecisionType;

import java.util.List;

/**
 * Outcome of {@link DeterministicMatchResolver#resolve}: either a safely resolved deterministic
 * match (link/barcode/fingerprint - always conflict-free and unambiguous by construction), or an
 * unresolved row carrying the top explainable fuzzy candidates for the future AI matcher (Prompt 05).
 *
 * <p>Carries only the matched product's id (never the JPA entity itself): this resolver runs
 * outside any open transaction (candidate search does its own short-lived repository calls), so a
 * lazily-associated {@code Product} read from e.g. {@code SupplierProductLink.getProduct()} would
 * risk a {@code LazyInitializationException} if dereferenced later. The persistence writer resolves
 * the id back to a managed reference inside its own transaction.
 */
public record MatchResolution(Long matchedProductId, MatchDecisionType decisionType, List<ScoredCandidate> candidates) {

    public boolean isResolved() {
        return matchedProductId != null;
    }

    public static MatchResolution resolved(Long productId, MatchDecisionType decisionType) {
        return new MatchResolution(productId, decisionType, List.of());
    }

    public static MatchResolution unresolved(List<ScoredCandidate> candidates) {
        return new MatchResolution(null, null, candidates);
    }
}
