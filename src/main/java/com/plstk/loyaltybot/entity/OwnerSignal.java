package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Сигнал/подсказка для владельца магазина.
 * Генерируется автоматически на основе аналитики.
 */
@Entity
@Table(name = "owner_signals")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerSignal {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Тип сигнала
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignalType signalType;
    
    /**
     * Важность сигнала
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Severity severity = Severity.INFO;
    
    /**
     * Связанный клиент (если применимо)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private User customer;
    
    /**
     * Заголовок сигнала
     */
    @Column(nullable = false)
    private String title;
    
    /**
     * Описание/детали сигнала
     */
    @Column(columnDefinition = "TEXT")
    private String description;
    
    /**
     * Дополнительные данные (JSON)
     */
    @Column(columnDefinition = "TEXT")
    private String payload;
    
    /**
     * Рекомендуемое действие
     */
    private String suggestedAction;
    
    /**
     * Дата создания
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    /**
     * Просмотрен ли сигнал
     */
    @Builder.Default
    private Boolean isSeen = false;
    
    /**
     * Дата просмотра
     */
    private LocalDateTime seenAt;
    
    /**
     * Скрыт ли сигнал (dismissed)
     */
    @Builder.Default
    private Boolean isDismissed = false;
    
    /**
     * Дата скрытия
     */
    private LocalDateTime dismissedAt;
    
    /**
     * Срок актуальности (после этой даты сигнал неактуален)
     */
    private LocalDateTime expiresAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
    
    /**
     * Типы сигналов
     */
    public enum SignalType {
        /**
         * Потерянные клиенты (не были давно)
         */
        LOST_CUSTOMERS("Потерянные клиенты", "😢"),
        
        /**
         * VIP клиент стал неактивным
         */
        VIP_INACTIVE("Неактивный VIP", "👑"),
        
        /**
         * Клиент близок к статусу REGULAR
         */
        ALMOST_REGULAR("Почти постоянный", "⭐"),
        
        /**
         * Клиент близок к статусу VIP
         */
        ALMOST_VIP("Почти VIP", "👑"),
        
        /**
         * Падение трафика (меньше клиентов чем обычно)
         */
        DROPPING_TRAFFIC("Падение трафика", "📉"),
        
        /**
         * Рост трафика (больше клиентов чем обычно)
         */
        TRAFFIC_SPIKE("Рост трафика", "📈"),
        
        /**
         * Клиент вернулся после долгого отсутствия
         */
        CUSTOMER_RETURNED("Клиент вернулся", "🎉"),
        
        /**
         * Новый VIP клиент
         */
        NEW_VIP("Новый VIP", "👑"),
        
        /**
         * Много непогашенных наград
         */
        UNCLAIMED_REWARDS("Непогашенные награды", "🎁"),
        
        /**
         * Еженедельный отчёт
         */
        WEEKLY_SUMMARY("Недельный отчёт", "📊");
        
        private final String displayName;
        private final String emoji;
        
        SignalType(String displayName, String emoji) {
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
    
    /**
     * Важность сигнала
     */
    public enum Severity {
        INFO("ℹ️", "Информация"),
        WARNING("⚠️", "Внимание"),
        IMPORTANT("❗", "Важно"),
        CRITICAL("🚨", "Критично");
        
        private final String emoji;
        private final String displayName;
        
        Severity(String emoji, String displayName) {
            this.emoji = emoji;
            this.displayName = displayName;
        }
        
        public String getEmoji() {
            return emoji;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Проверяет, актуален ли сигнал
     */
    public boolean isActive() {
        if (isDismissed != null && isDismissed) {
            return false;
        }
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return false;
        }
        return true;
    }
    
    /**
     * Возвращает форматированное отображение сигнала
     */
    public String getFormattedDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity.getEmoji()).append(" ");
        sb.append(signalType.getEmoji()).append(" ");
        sb.append("*").append(title).append("*\n");
        if (description != null) {
            sb.append(description).append("\n");
        }
        if (suggestedAction != null) {
            sb.append("💡 ").append(suggestedAction);
        }
        return sb.toString();
    }
}

