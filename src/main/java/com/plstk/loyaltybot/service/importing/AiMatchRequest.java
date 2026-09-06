package com.plstk.loyaltybot.service.importing;

import java.util.List;

/**
 * Input to {@link AiCatalogMatcher#match}. {@code candidates} is always the bounded, already
 * persisted {@code ImportRow.candidateSearchResult} list (at most {@code matching.max-candidates},
 * Prompt 04) - the AI never sees the full catalog, and every real {@code candidateId} it may choose
 * from is enumerated here explicitly (D-009). Deliberately carries only row/candidate attributes,
 * never supplier email metadata, buyer PII or secrets.
 */
public record AiMatchRequest(String rowId, NormalizedRowData row, List<ScoredCandidate> candidates) {
}
