package com.plstk.loyaltybot.service.importing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a bulk row-review action (docs/ARCHITECTURE.md §12 "bulk action только для
 * совместимых rows"): every row id is either a success or carries its own failure reason - one
 * incompatible row never aborts the rows that were fine.
 */
public record BulkReviewResult(List<Long> succeededRowIds, Map<Long, String> failures) {

    public static BulkReviewResult empty() {
        return new BulkReviewResult(List.of(), new LinkedHashMap<>());
    }
}
