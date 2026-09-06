package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;

/**
 * One {@link ImportRow}'s normalization + deterministic candidate search result, computed by
 * {@link ImportBatchNormalizingService} (outside any transaction) and persisted by
 * {@link ImportBatchNormalizeWriter} (inside one).
 */
public record RowNormalizationOutcome(
        ImportRow row,
        String normalizedDataJson,
        String candidateSearchResultJson,
        ImportRowStatus status,
        Long matchedProductId,
        MatchDecisionType decisionType) {

    public static RowNormalizationOutcome matched(
            ImportRow row, String normalizedDataJson, Long matchedProductId, MatchDecisionType decisionType) {
        ImportRowStatus status = decisionType == MatchDecisionType.LEARNED
                ? ImportRowStatus.LEARNED_MATCH
                : ImportRowStatus.EXACT_MATCH;
        return new RowNormalizationOutcome(row, normalizedDataJson, null, status, matchedProductId, decisionType);
    }

    public static RowNormalizationOutcome unresolved(ImportRow row, String normalizedDataJson, String candidateSearchResultJson) {
        return new RowNormalizationOutcome(
                row, normalizedDataJson, candidateSearchResultJson, ImportRowStatus.PENDING, null, null);
    }
}
