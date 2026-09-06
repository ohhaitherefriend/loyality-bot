package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.DecidedBy;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One {@link com.plstk.loyaltybot.entity.importing.MatchDecision} in a row's audit trail
 * (docs/ARCHITECTURE.md §12 "AI/model/prompt audit"): AI provenance for {@code SYSTEM} decisions,
 * reviewer identity for {@code HUMAN} ones. A row's full decision list (newest first) already gives
 * "previous decision" for the currently-selected one - no separate field needed.
 */
public record MatchDecisionAudit(
        Long id,
        MatchDecisionType decisionType,
        DecidedBy decidedBy,
        Long chosenProductId,
        String chosenProductName,
        BigDecimal confidenceScore,
        String modelProvider,
        String modelName,
        String promptVersion,
        List<String> conflicts,
        String reason,
        Long reviewerUserId,
        String reviewerEmail,
        LocalDateTime decidedAt) {
}
