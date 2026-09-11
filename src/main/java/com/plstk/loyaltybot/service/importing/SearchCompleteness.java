package com.plstk.loyaltybot.service.importing;

/**
 * ADR-030: an explicit, persisted answer to "did the required deterministic identity search
 * actually run to completion for this row?" - NOT the same question as "how many candidates were
 * found". Zero candidates/no deterministic match can mean either a genuinely new product OR a
 * search that could not be scoped/run at all (missing brand, unparsed fingerprint) - only the
 * FORMER is safe grounds for an automatic {@code NEW_PRODUCT} decision
 * ({@code ImportBatchMatchingService#evaluateNewProductOrReview}).
 *
 * <p>{@code required} is true only when the row had enough structured signal (a non-blank brand
 * AND fingerprint) for {@code DeterministicMatchResolver#resolveViaSafeFingerprint}'s UNBOUNDED,
 * brand-scoped catalog check to actually run - that check has no candidate-fetch-limit/pagination
 * at all, so once it runs, it is complete by construction; there is nothing left to "truncate".
 * When {@code required} is false, the row's own data was insufficient to run that check at all
 * (not a limitation of the search infrastructure) - {@code reason} explains why.
 *
 * @param requiredStagesCompleted true if the unbounded exact-identity check actually ran (had a
 *     usable brand + fingerprint), independently of whether it found a match
 * @param reason human-readable explanation when {@code requiredStagesCompleted} is false; {@code
 *     null} when true
 * @param normalizationVersion the {@link RowAttributeNormalizer#NORMALIZATION_VERSION} active when
 *     this was computed - lets a future version bump tell fresh diagnostics apart from stale ones
 */
public record SearchCompleteness(boolean requiredStagesCompleted, String reason, Integer normalizationVersion) {

    public static SearchCompleteness completed(int normalizationVersion) {
        return new SearchCompleteness(true, null, normalizationVersion);
    }

    public static SearchCompleteness incomplete(String reason, int normalizationVersion) {
        return new SearchCompleteness(false, reason, normalizationVersion);
    }

    /** Legacy data persisted before this diagnostic existed - MUST be treated as "unknown", never as "complete". */
    public static boolean isUnknown(SearchCompleteness completeness) {
        return completeness == null;
    }
}
