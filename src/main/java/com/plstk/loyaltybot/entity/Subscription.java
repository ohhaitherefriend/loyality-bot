package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Подписка магазина на тарифный план.
 */
@Entity
@Table(name = "subscriptions", indexes = {
    @Index(name = "idx_subscription_shop_id", columnList = "shopId", unique = true),
    @Index(name = "idx_subscription_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * ID магазина
     */
    @Column(nullable = false, unique = true, length = 36)
    private String shopId;
    
    /**
     * Статус подписки
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.TRIALING;
    
    /**
     * Код текущего плана
     */
    @Column(nullable = false, length = 64)
    @Builder.Default
    private String planCode = Plan.FREE_TRIAL;
    
    /**
     * Провайдер оплаты
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaymentProvider provider = PaymentProvider.STUB;
    
    // ========== Trial период ==========
    
    /**
     * Начало trial периода
     */
    private LocalDateTime trialStartAt;
    
    /**
     * Окончание trial периода
     */
    private LocalDateTime trialEndAt;
    
    // ========== Платный период ==========
    
    /**
     * Начало текущего платного периода
     */
    private LocalDateTime currentPeriodStartAt;
    
    /**
     * Окончание текущего платного периода
     */
    private LocalDateTime currentPeriodEndAt;
    
    // ========== Метаданные ==========
    
    /**
     * Внешний ID подписки у провайдера (для будущей интеграции)
     */
    @Column(length = 255)
    private String externalSubscriptionId;
    
    /**
     * Уведомление об истечении было отправлено
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean expirationNotified = false;
    
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
     * Статусы подписки
     */
    public enum SubscriptionStatus {
        TRIALING,   // Пробный период
        ACTIVE,     // Активная подписка
        EXPIRED,    // Истекла
        CANCELLED,  // Отменена
        PAST_DUE    // Просрочена оплата (для будущего)
    }
    
    /**
     * Провайдеры оплаты
     */
    public enum PaymentProvider {
        STUB,           // Заглушка (без оплаты)
        CLOUDPAYMENTS,  // CloudPayments (будущее)
        YOOKASSA        // ЮKassa (будущее)
    }
    
    // ========== Helper methods ==========
    
    /**
     * Вычисляет количество дней до окончания подписки
     */
    public long getDaysLeft() {
        LocalDateTime endDate = getEffectiveEndDate();
        if (endDate == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(LocalDateTime.now(), endDate);
        return Math.max(0, days);
    }
    
    /**
     * Возвращает эффективную дату окончания (trial или платный период)
     */
    public LocalDateTime getEffectiveEndDate() {
        if (status == SubscriptionStatus.TRIALING && trialEndAt != null) {
            return trialEndAt;
        }
        if (currentPeriodEndAt != null) {
            return currentPeriodEndAt;
        }
        return trialEndAt;
    }
    
    /**
     * Проверяет, истекла ли подписка
     */
    public boolean isExpired() {
        LocalDateTime endDate = getEffectiveEndDate();
        return endDate != null && LocalDateTime.now().isAfter(endDate);
    }
    
    /**
     * Создаёт trial подписку для нового магазина
     */
    public static Subscription createTrial(String shopId, int trialDays) {
        LocalDateTime now = LocalDateTime.now();
        return Subscription.builder()
            .shopId(shopId)
            .status(SubscriptionStatus.TRIALING)
            .planCode(Plan.FREE_TRIAL)
            .provider(PaymentProvider.STUB)
            .trialStartAt(now)
            .trialEndAt(now.plusDays(trialDays))
            .build();
    }
}
