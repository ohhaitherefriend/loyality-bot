package com.plstk.loyaltybot.entity.importing;

/**
 * Versioned политика округления при расчёте {@code calculatedSitePrice}.
 * Используется вместе с {@link java.math.BigDecimal}, значения-double не допускаются.
 */
public enum PriceRoundingPolicy {
    /** Округление HALF_UP до целой единицы валюты (соответствует текущему ProductImportService). */
    WHOLE_UNIT_HALF_UP,
    /** Округление HALF_UP до минимальной денежной единицы (копейки/центы). */
    MINOR_UNIT_HALF_UP
}
