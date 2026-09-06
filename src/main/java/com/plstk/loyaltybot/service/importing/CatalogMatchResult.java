package com.plstk.loyaltybot.service.importing;

import java.math.BigDecimal;
import java.util.List;

/**
 * Parsed and validated outcome of one {@link AiCatalogMatcher#match} call, produced by
 * {@link CatalogMatchResponseValidator}. {@code valid=false} covers every case where the raw AI
 * content cannot be trusted at all (malformed JSON, schema violation, row_id mismatch, or an
 * invented {@code candidate_id} outside the candidates actually offered) - the caller must treat
 * this identically to a transport failure (row falls back to {@code NEEDS_REVIEW}), never crash and
 * never guess. {@code candidateProductId} is non-null only when {@code valid=true &amp;&amp;
 * matched=true}, and is guaranteed (by this class's own construction) to be one of the ids the
 * caller passed in - membership is re-checked here, not trusted from the AI response, per D-009.
 */
public record CatalogMatchResult(
        boolean valid,
        boolean matched,
        Long candidateProductId,
        BigDecimal confidence,
        List<String> matchedAttributes,
        List<String> aiReportedConflicts,
        String reason,
        List<String> validationErrors) {

    public static CatalogMatchResult invalid(List<String> errors) {
        return new CatalogMatchResult(false, false, null, null, List.of(), List.of(), null, errors);
    }

    public static CatalogMatchResult match(
            Long candidateProductId, BigDecimal confidence, List<String> matchedAttributes,
            List<String> aiReportedConflicts, String reason) {
        return new CatalogMatchResult(
                true, true, candidateProductId, confidence, matchedAttributes, aiReportedConflicts, reason, List.of());
    }

    public static CatalogMatchResult noMatch(
            BigDecimal confidence, List<String> matchedAttributes, List<String> aiReportedConflicts, String reason) {
        return new CatalogMatchResult(
                true, false, null, confidence, matchedAttributes, aiReportedConflicts, reason, List.of());
    }
}
