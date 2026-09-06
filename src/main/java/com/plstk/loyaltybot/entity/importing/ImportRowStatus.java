package com.plstk.loyaltybot.entity.importing;

/**
 * Состояние отдельной строки import batch. См. docs/ARCHITECTURE.md (§7).
 * Не используется кодом до Prompt 03/04 (парсинг/нормализация), но схема нужна уже сейчас.
 */
public enum ImportRowStatus {
    PENDING,
    EXACT_MATCH,
    LEARNED_MATCH,
    AI_MATCH,
    AUTO_APPROVED,
    NEEDS_REVIEW,
    NEW_PRODUCT,
    IGNORED,
    INVALID,
    APPROVED,
    APPLIED
}
