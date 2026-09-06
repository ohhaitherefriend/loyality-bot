package com.plstk.loyaltybot.entity.importing;

/**
 * Состояние import batch. Полный state machine описан в docs/ARCHITECTURE.md (§7).
 * Prompt 01 использует только {@link #RECEIVED} и {@link #STORED} через AttachmentIngestionService;
 * остальные значения зарезервированы для последующих prompts (parsing/matching/apply).
 */
public enum ImportBatchStatus {
    RECEIVED,
    STORED,
    PARSING,
    NORMALIZING,
    MATCHING,
    VALIDATING,
    AUTO_APPROVED,
    NEEDS_ATTENTION,
    APPROVED,
    APPLYING,
    APPLIED,
    QUARANTINED,
    FAILED
}
