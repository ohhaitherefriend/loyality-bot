package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Определение ручного бейджа (значка).
 * Выдаётся администратором вручную и даёт определённые perks (бонусы).
 * 
 * Ограничения:
 * - max 1-2 активных бейджа на клиента
 * - max N выдач в месяц (лимит на магазин)
 * 
 * Perks НЕ меняют цену товара, а влияют на:
 * - бонусы
 * - штампы  
 * - статус
 */
@Entity
@Table(name = "manual_badge_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualBadgeDefinition {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Название бейджа
     */
    @Column(nullable = false)
    private String title;
    
    /**
     * Описание бейджа (показывается клиенту)
     */
    private String description;
    
    /**
     * Эмодзи для отображения
     */
    @Column(nullable = false)
    @Builder.Default
    private String emoji = "🎖";
    
    /**
     * Тип бонуса (perk)
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PerkType perkType = PerkType.NONE;
    
    /**
     * Значение бонуса
     * - для BONUS_STAMPS_MULTIPLIER: множитель (например, 2 = x2 штампов)
     * - для BONUS_STAMPS_FLAT: количество доп. штампов
     * - для BONUS_POINTS_MULTIPLIER: множитель баллов
     * - для PRIORITY_STATUS: не используется
     */
    private Integer perkValue;
    
    /**
     * Срок действия бейджа в днях (после выдачи)
     * null = бессрочно
     */
    private Integer validDays;
    
    /**
     * Активен ли бейдж (можно ли выдавать)
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    
    /**
     * Максимум выдач этого бейджа в месяц (на весь магазин)
     * null = без ограничений
     */
    private Integer maxAwardsPerMonth;
    
    /**
     * Максимум активных бейджей этого типа у одного клиента
     */
    @Builder.Default
    private Integer maxPerCustomer = 1;
    
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
     * Типы бонусов (perks) для бейджей
     * НЕ влияют на цену товара!
     */
    public enum PerkType {
        /**
         * Без бонуса (только значок)
         */
        NONE("Без бонуса", "Только визуальный значок"),
        
        /**
         * Множитель штампов (x2, x3 и т.д.)
         */
        BONUS_STAMPS_MULTIPLIER("Множитель штампов", "Получаете больше штампов за покупку"),
        
        /**
         * Фиксированные доп. штампы к каждой покупке
         */
        BONUS_STAMPS_FLAT("Доп. штампы", "Получаете дополнительные штампы"),
        
        /**
         * Множитель баллов
         */
        BONUS_POINTS_MULTIPLIER("Множитель баллов", "Получаете больше баллов"),
        
        /**
         * Фиксированные доп. баллы
         */
        BONUS_POINTS_FLAT("Доп. баллы", "Получаете дополнительные баллы"),
        
        /**
         * Приоритетное обслуживание (визуальная метка для персонала)
         */
        PRIORITY_STATUS("Приоритет", "Приоритетное обслуживание"),
        
        /**
         * Защита от потери статуса (LOST)
         */
        STATUS_PROTECTION("Защита статуса", "Ваш статус не понизится");
        
        private final String displayName;
        private final String description;
        
        PerkType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Возвращает отображение с эмодзи
     */
    public String getDisplayWithEmoji() {
        return emoji + " " + title;
    }
    
    /**
     * Возвращает описание бонуса
     */
    public String getPerkDescription() {
        if (perkType == PerkType.NONE) {
            return "Почётный значок";
        }
        
        String desc = perkType.getDescription();
        if (perkValue != null && perkValue > 0) {
            switch (perkType) {
                case BONUS_STAMPS_MULTIPLIER, BONUS_POINTS_MULTIPLIER -> 
                    desc = "x" + perkValue + " " + perkType.getDisplayName().toLowerCase();
                case BONUS_STAMPS_FLAT, BONUS_POINTS_FLAT -> 
                    desc = "+" + perkValue + " " + perkType.getDisplayName().toLowerCase();
                default -> {}
            }
        }
        
        if (validDays != null) {
            desc += " (на " + validDays + " дней)";
        }
        
        return desc;
    }
}



