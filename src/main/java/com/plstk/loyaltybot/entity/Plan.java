package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Тарифный план подписки.
 */
@Entity
@Table(name = "plans", indexes = {
    @Index(name = "idx_plan_code", columnList = "code", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Уникальный код плана (e.g., FREE_TRIAL, BASIC_MONTHLY, PRO_YEARLY)
     */
    @Column(nullable = false, unique = true, length = 64)
    private String code;
    
    /**
     * Отображаемое название
     */
    @Column(nullable = false, length = 255)
    private String name;
    
    /**
     * Описание плана
     */
    @Column(length = 1024)
    private String description;
    
    /**
     * Цена в минимальных единицах валюты (копейки/центы)
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer priceAmount = 0;
    
    /**
     * Валюта (RUB, USD, EUR)
     */
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "RUB";
    
    /**
     * Период подписки в днях (30 = месяц, 365 = год)
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer periodDays = 30;
    
    /**
     * Это заглушка (не требует оплаты)
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isStub = true;
    
    /**
     * Активен ли план для новых подписок
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    /**
     * Предопределённые коды планов
     */
    public static final String FREE_TRIAL = "FREE_TRIAL";
    public static final String BASIC_MONTHLY = "BASIC_MONTHLY";
    public static final String PRO_MONTHLY = "PRO_MONTHLY";
    public static final String BASIC_YEARLY = "BASIC_YEARLY";
}
