package com.plstk.loyaltybot.entity.importing;

/**
 * Стратегия выбора публичной цены товара при нескольких активных supplier offers.
 */
public enum PublicPriceStrategy {
    /** Показывать минимальный calculatedSitePrice среди активных offers (default, D-008). */
    LOWEST_ACTIVE_OFFER
}
