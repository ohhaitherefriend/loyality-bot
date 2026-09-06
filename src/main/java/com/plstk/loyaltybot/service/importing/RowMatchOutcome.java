package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;

import java.math.BigDecimal;
import java.util.List;

/**
 * One {@link ImportRow}'s automation-gate outcome, computed by {@link ImportBatchMatchingService}
 * (outside any transaction - the AI HTTP call happens here) and persisted by
 * {@link ImportBatchMatchWriter} (inside one). {@code writeDecision=false} is used only for rows
 * that already had a {@code MatchDecision} written in Prompt 04 (deterministic EXACT/LEARNED match,
 * safe/conflict-free by construction) and are merely being promoted to {@code AUTO_APPROVED} here -
 * duplicating that audit row would misrepresent the pipeline's actual decision history.
 */
public record RowMatchOutcome(
        ImportRow row,
        ImportRowStatus status,
        Long matchedProductId,
        MatchDecisionType decisionType,
        BigDecimal confidenceScore,
        List<String> conflicts,
        String reason,
        String modelProvider,
        String modelName,
        String promptVersion,
        boolean writeDecision) {

    /** Prompt 04's EXACT_MATCH/LEARNED_MATCH rows are conflict-free/unambiguous by construction. */
    public static RowMatchOutcome promoteDeterministic(ImportRow row) {
        Long matchedProductId = row.getMatchedProduct() == null ? null : row.getMatchedProduct().getId();
        return new RowMatchOutcome(
                row, ImportRowStatus.AUTO_APPROVED, matchedProductId, null, null, List.of(), null, null, null, null, false);
    }

    public static RowMatchOutcome auto(
            ImportRow row, MatchDecisionType decisionType, Long matchedProductId, List<String> conflicts,
            String reason, String modelProvider, String modelName, String promptVersion, BigDecimal confidence) {
        return new RowMatchOutcome(
                row, ImportRowStatus.AUTO_APPROVED, matchedProductId, decisionType, confidence, conflicts, reason,
                modelProvider, modelName, promptVersion, true);
    }

    public static RowMatchOutcome review(
            ImportRow row, MatchDecisionType decisionType, Long matchedProductId, List<String> conflicts,
            String reason, String modelProvider, String modelName, String promptVersion, BigDecimal confidence) {
        return new RowMatchOutcome(
                row, ImportRowStatus.NEEDS_REVIEW, matchedProductId, decisionType, confidence, conflicts, reason,
                modelProvider, modelName, promptVersion, true);
    }
}
