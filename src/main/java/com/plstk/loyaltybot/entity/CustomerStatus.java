package com.plstk.loyaltybot.entity;

/**
 * Статус клиента в программе лояльности.
 * Определяется автоматически на основе активности покупок.
 */
public enum CustomerStatus {
    
    /**
     * Новый клиент (менее N покупок)
     */
    NEW("Новичок", "🆕"),
    
    /**
     * Постоянный клиент (от N до M покупок)
     */
    REGULAR("Постоянный", "⭐"),
    
    /**
     * VIP клиент (более M покупок или сумма покупок)
     */
    VIP("VIP", "👑"),
    
    /**
     * Потерянный клиент (не было покупок более X дней)
     */
    LOST("Пропал", "😢");
    
    private final String displayName;
    private final String emoji;
    
    CustomerStatus(String displayName, String emoji) {
        this.displayName = displayName;
        this.emoji = emoji;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getEmoji() {
        return emoji;
    }
    
    public String getDisplayWithEmoji() {
        return emoji + " " + displayName;
    }
}



