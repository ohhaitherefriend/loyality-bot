package com.plstk.loyaltybot.service.importing;

/**
 * Human review action on one {@link com.plstk.loyaltybot.entity.importing.ImportRow} in the
 * Prompt 07 exception queue (docs/ARCHITECTURE.md §12 "Actions: MATCH, NO_MATCH, CREATE_PRODUCT,
 * IGNORE, SET_MANUAL_HIDDEN, approve layout, resume batch"). {@code SET_MANUAL_HIDDEN}/
 * {@code approve layout}/{@code resume batch} are modeled as separate operations
 * ({@code ProductVisibilityOverrideService}, {@code ImportRuleVersionApprovalService},
 * {@code ImportBatchResumeService}) since they do not act on one {@code ImportRow}.
 */
public enum RowReviewAction {
    /** Operator confirms a specific product (usually one of the persisted fuzzy candidates). */
    MATCH,
    /** Operator confirms this row matches nothing in the catalog and must never become a new product. */
    NO_MATCH,
    /** Operator overrides an automation gate (e.g. missing brand) and allows a new product to be created. */
    CREATE_PRODUCT,
    /** Operator skips this row entirely (bad data, duplicate, out of scope) without any catalog effect. */
    IGNORE
}
