package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportRowStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Full exception-queue detail for one {@link com.plstk.loyaltybot.entity.importing.ImportRow}
 * (docs/ARCHITECTURE.md §12 "raw/normalized row, candidates, score, conflicts, AI/model/prompt
 * audit"). {@code candidates}' own {@code score}/{@code conflicts} live inside each
 * {@link ScoredCandidate}; {@code decisions} carries the AI/model/prompt/reviewer audit trail.
 */
public record RowDetailResponse(
        Long rowId,
        Long version,
        Long batchId,
        String sourceSheet,
        Integer sourceRowNumber,
        ImportRowStatus status,
        Map<String, String> rawData,
        NormalizedRowData normalizedData,
        List<ScoredCandidate> candidates,
        Long matchedProductId,
        String matchedProductName,
        List<MatchDecisionAudit> decisions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
