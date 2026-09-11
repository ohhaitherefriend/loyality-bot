package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * ADR-030/031: an explicit, persisted answer to two ORTHOGONAL questions about a row's
 * deterministic identity check, never collapsed into one boolean (the report's core complaint
 * about the original {@code requiredStagesCompleted} design):
 * <ol>
 *   <li>{@link #identityOutcome()} - what did the unbounded, brand-scoped exact-fingerprint check
 *       ({@code DeterministicMatchResolver#resolveViaSafeFingerprint}) actually FIND among the
 *       candidates it considered: {@link IdentityOutcome#NONE} (zero), {@link IdentityOutcome#UNIQUE}
 *       (exactly one - already resolved deterministically, so this outcome should never itself
 *       reach an unresolved {@code MatchResolution}), or {@link IdentityOutcome#AMBIGUOUS} (more
 *       than one catalog product structurally identical to this row - a data-quality situation
 *       that must NEVER be auto-resolved to any of them, nor treated as "safe to create a new
 *       one" either);</li>
 *   <li>{@link #searchState()} - HOW RELIABLE is that finding: did the required stage(s) actually
 *       run to completion over the full qualifying candidate set ({@link SearchState#COMPLETE}),
 *       could they not even be scoped/fully executed ({@link SearchState#LIMITED}), did an
 *       unexpected error occur while running them ({@link SearchState#ERRORED}), or is the
 *       previously-computed result too old to trust without recomputation ({@link
 *       SearchState#STALE}, e.g. a different {@code normalizationVersion} or a shop alias
 *       configuration change since it was computed)?</li>
 * </ol>
 *
 * <p>Automatic {@code NEW_PRODUCT} creation ({@code ImportBatchMatchingService}) or automatic
 * reuse of an existing product (apply-time re-verification, {@code ImportBatchApplyWriter}) is
 * safe ONLY when {@link #permitsAutomaticNewProduct()}/{@link #permitsAutomaticDecision()} is
 * true - {@code searchState() == COMPLETE AND identityOutcome() != AMBIGUOUS}. Critically, an AI
 * {@code NO_MATCH} response is NEVER by itself proof that {@link #identityOutcome()} is really
 * {@code NONE} catalog-wide - it only describes the AI's opinion of the bounded fuzzy shortlist it
 * was shown; the deterministic, unbounded check captured here is the only thing trusted for that.
 *
 * @param identityOutcome what the deterministic exact-identity check found among the candidates it
 *     considered - see class javadoc. Never {@code null}.
 * @param searchState how reliable/fresh that finding is - see class javadoc. Never {@code null}.
 * @param reason human-readable explanation when {@code searchState != COMPLETE} or {@code
 *     identityOutcome == AMBIGUOUS}; {@code null} otherwise
 * @param normalizationVersion the {@link RowAttributeNormalizer#NORMALIZATION_VERSION} active when
 *     this was computed - lets a future version bump tell fresh diagnostics apart from stale ones
 */
public record SearchCompleteness(
        IdentityOutcome identityOutcome, SearchState searchState, String reason, Integer normalizationVersion) {

    public enum IdentityOutcome {
        /** The deterministic check found zero structurally-identical catalog products. */
        NONE,
        /** The deterministic check found exactly one - already resolved by the caller, so an
         *  unresolved {@code MatchResolution} should never itself carry this outcome. */
        UNIQUE,
        /** The deterministic check found MORE THAN ONE structurally-identical catalog product -
         *  a data-quality anomaly that must always be routed to human review, never auto-resolved
         *  to either candidate and never treated as grounds for a safe {@code NEW_PRODUCT}. */
        AMBIGUOUS
    }

    public enum SearchState {
        /** The required stage(s) ran to completion over the FULL qualifying candidate set, with no
         *  premature truncation before ranking/comparison. */
        COMPLETE,
        /** The check could not be scoped or fully executed (e.g. missing brand/fingerprint, or a
         *  candidate source had to truncate before every qualifying candidate was considered). */
        LIMITED,
        /** An unexpected error occurred while running the required search stage(s). */
        ERRORED,
        /** A previously-persisted result that cannot be trusted as-is (different {@code
         *  normalizationVersion} than current, or the shop's alias/identity configuration has
         *  changed since) - must be recomputed before being relied on for an automatic decision. */
        STALE
    }

    /** Legacy data persisted before this diagnostic existed - MUST be treated as "unknown", never as "complete". */
    public static boolean isUnknown(SearchCompleteness completeness) {
        return completeness == null;
    }

    /**
     * True only when the search is fully reliable (searchState == COMPLETE) AND found no existing
     * structurally-identical product (identityOutcome == NONE) - the one combination safe enough
     * to ground an automatic {@code NEW_PRODUCT} decision on. An AI {@code NO_MATCH} against a
     * bounded fuzzy shortlist is irrelevant to this - see class javadoc.
     */
    @JsonIgnore
    public boolean permitsAutomaticNewProduct() {
        return searchState == SearchState.COMPLETE && identityOutcome == IdentityOutcome.NONE;
    }

    /**
     * True when the search is fully reliable AND did not find more than one structurally-identical
     * candidate. Used by apply-time re-verification, where a {@code UNIQUE} outcome (the confirmed
     * existing product) and a {@code NONE} outcome (safe to create) are both legitimate depending
     * on the caller's context, but {@code AMBIGUOUS} or an unreliable searchState never is.
     */
    @JsonIgnore
    public boolean permitsAutomaticDecision() {
        return searchState == SearchState.COMPLETE && identityOutcome != IdentityOutcome.AMBIGUOUS;
    }

    @JsonIgnore
    public boolean isAmbiguous() {
        return identityOutcome == IdentityOutcome.AMBIGUOUS;
    }

    /** True if the required deterministic identity check actually ran to completion (regardless of what it found). */
    @JsonIgnore
    public boolean requiredStagesCompleted() {
        return searchState == SearchState.COMPLETE;
    }

    public static SearchCompleteness completed(int normalizationVersion) {
        return new SearchCompleteness(IdentityOutcome.NONE, SearchState.COMPLETE, null, normalizationVersion);
    }

    public static SearchCompleteness incomplete(String reason, int normalizationVersion) {
        return new SearchCompleteness(IdentityOutcome.NONE, SearchState.LIMITED, reason, normalizationVersion);
    }

    public static SearchCompleteness ambiguous(String reason, int normalizationVersion) {
        return new SearchCompleteness(IdentityOutcome.AMBIGUOUS, SearchState.COMPLETE, reason, normalizationVersion);
    }

    public static SearchCompleteness errored(String reason, int normalizationVersion) {
        return new SearchCompleteness(IdentityOutcome.NONE, SearchState.ERRORED, reason, normalizationVersion);
    }

    public static SearchCompleteness stale(String reason, Integer normalizationVersion) {
        return new SearchCompleteness(IdentityOutcome.NONE, SearchState.STALE, reason, normalizationVersion);
    }
}
