package com.plstk.loyaltybot.service.importing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One explainable candidate from {@link CandidateSearchService}: a catalog product plus a
 * transparent score breakdown, so a human (or the future AI matcher in Prompt 05) can see WHY a
 * candidate was ranked where it was, not just a single opaque number.
 *
 * @param componentScores named contributions to {@code totalScore} (e.g. "nameSimilarity",
 *     "brandBonus", "brandAliasBonus", "attributeBonus", "conflictPenalty")
 * @param matchedAttributes human-readable tags for what matched (e.g. "brand", "brandAlias",
 *     "volume", "concentration", "shade")
 * @param conflicts critical conflict codes from {@link CriticalAttributeConflictChecker} - a
 *     non-empty list here means this candidate must never be auto-matched
 */
public record ScoredCandidate(
        Long productId,
        String productName,
        BigDecimal totalScore,
        Map<String, BigDecimal> componentScores,
        List<String> matchedAttributes,
        List<String> conflicts,
        NormalizedRowData candidateAttributes) {

    public boolean hasConflicts() {
        return conflicts != null && !conflicts.isEmpty();
    }
}
