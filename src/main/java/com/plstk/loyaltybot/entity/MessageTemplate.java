package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Шаблон сообщения для отправки клиентам.
 * Поддерживает переменные: {name}, {status}, {stampsLeft}, {daysSinceLastVisit}
 */
@Entity
@Table(name = "message_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageTemplate {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Название шаблона (для админа)
     */
    @Column(nullable = false)
    private String name;
    
    /**
     * Текст сообщения с переменными
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    
    /**
     * Категория шаблона
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TemplateCategory category = TemplateCategory.GENERAL;
    
    /**
     * Тип авто-триггера (если шаблон для авто-отправки)
     */
    @Enumerated(EnumType.STRING)
    private AutoTriggerType autoTrigger;
    
    /**
     * Активен ли шаблон
     */
    @Builder.Default
    private Boolean isActive = true;
    
    /**
     * Системный шаблон (нельзя удалить)
     */
    @Builder.Default
    private Boolean isSystem = false;
    
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
     * Категории шаблонов
     */
    public enum TemplateCategory {
        GENERAL("Общие"),
        WELCOME("Приветствие"),
        PROMOTION("Акции"),
        REMINDER("Напоминания"),
        ACHIEVEMENT("Достижения"),
        STATUS_CHANGE("Изменение статуса");
        
        private final String displayName;
        
        TemplateCategory(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Типы авто-триггеров для шаблонов
     */
    public enum AutoTriggerType {
        FIRST_PURCHASE("Первая покупка"),
        ONE_STAMP_LEFT("Остался 1 штамп"),
        BECAME_REGULAR("Стал постоянным"),
        BECAME_VIP("Стал VIP"),
        VIP_BECAME_LOST("VIP пропал"),
        ACHIEVEMENT_EARNED("Получена ачивка"),
        REWARD_AVAILABLE("Доступна награда"),
        BIRTHDAY("День рождения"),
        INACTIVE_REMINDER("Напоминание неактивным");
        
        private final String displayName;
        
        AutoTriggerType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Доступные переменные для шаблонов
     */
    public static final String VAR_NAME = "{name}";
    public static final String VAR_STATUS = "{status}";
    public static final String VAR_STAMPS = "{stamps}";
    public static final String VAR_STAMPS_LEFT = "{stampsLeft}";
    public static final String VAR_REWARDS = "{rewards}";
    public static final String VAR_DAYS_SINCE_VISIT = "{daysSinceLastVisit}";
    public static final String VAR_PURCHASES_COUNT = "{purchasesCount}";
    public static final String VAR_TOTAL_SPEND = "{totalSpend}";
    public static final String VAR_DISCOUNT = "{discount}";
    public static final String VAR_ACHIEVEMENT = "{achievement}";
}



