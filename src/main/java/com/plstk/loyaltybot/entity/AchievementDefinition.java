package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Определение ачивки (достижения).
 * Описывает условия получения и награду.
 */
@Entity
@Table(name = "achievement_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AchievementDefinition {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Название ачивки
     */
    @Column(nullable = false)
    private String title;
    
    /**
     * Описание (показывается клиенту)
     */
    private String description;
    
    /**
     * Эмодзи для отображения
     */
    @Column(nullable = false)
    @Builder.Default
    private String emoji = "🏆";
    
    /**
     * Активна ли ачивка
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    
    /**
     * Тип триггера (условие получения)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType triggerType;
    
    /**
     * Числовое значение для триггера
     * Например: N покупок, N дней, N рублей
     */
    private Integer triggerValueInt;
    
    /**
     * Период в днях для временных триггеров
     */
    private Integer triggerValuePeriodDays;
    
    /**
     * Дополнительные параметры триггера (JSON)
     * Например: время начала/конца для PURCHASE_TIME_WINDOW
     */
    @Column(columnDefinition = "TEXT")
    private String triggerParams;
    
    /**
     * Тип награды за ачивку
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RewardType rewardType = RewardType.NONE;
    
    /**
     * Значение награды (баллы, штампы, процент скидки)
     */
    private Integer rewardValue;
    
    /**
     * Cooldown в днях (нельзя получить повторно раньше)
     */
    private Integer cooldownDays;
    
    /**
     * Максимум выдач этой ачивки одному клиенту
     * null = без ограничений
     */
    private Integer maxAwardsPerCustomer;
    
    /**
     * Приоритет отображения (больше = выше)
     */
    @Builder.Default
    private Integer displayOrder = 0;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * Типы триггеров для ачивок
     */
    public enum TriggerType {
        /**
         * Первая покупка
         */
        FIRST_PURCHASE("Первая покупка"),
        
        /**
         * N покупок всего
         */
        N_PURCHASES_TOTAL("N покупок всего"),
        
        /**
         * N визитов всего (для fast checkout)
         */
        N_VISITS_TOTAL("N визитов всего"),
        
        /**
         * N покупок за период (triggerValuePeriodDays)
         */
        N_PURCHASES_IN_PERIOD("N покупок за период"),
        
        /**
         * Вернулся после N дней отсутствия
         */
        COME_BACK_AFTER_DAYS("Вернулся после N дней"),
        
        /**
         * Покупка в определённое время (утро/вечер)
         * triggerParams: {"startHour": 6, "endHour": 9}
         */
        PURCHASE_TIME_WINDOW("Покупка в определённое время"),
        
        /**
         * Потратил N рублей за период
         */
        SPEND_IN_PERIOD("Потратил N за период"),
        
        /**
         * Достиг статуса REGULAR
         */
        BECAME_REGULAR("Стал постоянным"),
        
        /**
         * Достиг статуса VIP
         */
        BECAME_VIP("Стал VIP"),
        
        /**
         * N штампов накоплено всего
         */
        N_STAMPS_TOTAL("N штампов всего"),
        
        /**
         * N наград получено
         */
        N_REWARDS_REDEEMED("N наград получено");
        
        private final String displayName;
        
        TriggerType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Типы наград за ачивки
     */
    public enum RewardType {
        /**
         * Без награды (только значок)
         */
        NONE("Без награды"),
        
        /**
         * Бонусные баллы
         */
        BONUS_POINTS("Бонусные баллы"),
        
        /**
         * Бонусные штампы
         */
        BONUS_STAMPS("Бонусные штампы"),
        
        /**
         * Скидка на следующую покупку (%)
         */
        DISCOUNT_PERCENT("Скидка %"),
        
        /**
         * Фиксированная скидка в рублях
         */
        DISCOUNT_FIXED("Скидка руб.");
        
        private final String displayName;
        
        RewardType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Возвращает отображение ачивки с эмодзи
     */
    public String getDisplayWithEmoji() {
        return emoji + " " + title;
    }
}



