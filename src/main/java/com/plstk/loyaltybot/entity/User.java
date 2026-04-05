package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_chat_id_shop_id", columnList = "chatId, shopId", unique = true),
    @Index(name = "idx_user_phone_shop_id", columnList = "phoneNumber, shopId"),
    @Index(name = "idx_user_shop_id", columnList = "shopId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Telegram Chat ID
     */
    @Column(nullable = false)
    private Long chatId;
    
    /**
     * ID магазина (для multi-tenancy).
     * Один пользователь может быть клиентом нескольких магазинов.
     */
    @Column(length = 36)
    private String shopId;
    
    @Column(nullable = false)
    private String phoneNumber;
    
    private String firstName;
    private String lastName;
    private String username;
    
    private LocalDateTime discountEarnedAt;  // Дата когда была активирована скидка (или последнего продления)
    private Integer discountLevel;  // Уровень скидки (5, 7 или 10 процентов)
    
    // ========== Customer Profile (новые поля) ==========
    
    /**
     * Дата первой покупки
     */
    private LocalDateTime firstPurchaseAt;
    
    /**
     * Дата последней покупки
     */
    private LocalDateTime lastPurchaseAt;
    
    /**
     * Общее количество покупок
     */
    @Builder.Default
    private Integer purchasesCount = 0;
    
    /**
     * Количество визитов (для штампов/fast checkout)
     */
    @Builder.Default
    private Integer visitsCount = 0;
    
    /**
     * Общая сумма покупок за всё время
     */
    @Builder.Default
    private Double totalSpend = 0.0;
    
    /**
     * Статус клиента (NEW, REGULAR, VIP, LOST)
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CustomerStatus customerStatus = CustomerStatus.NEW;
    
    /**
     * Дата последнего обновления статуса
     */
    private LocalDateTime statusUpdatedAt;
    
    /**
     * Дата последнего fast checkout (для cooldown)
     */
    private LocalDateTime lastFastCheckoutAt;
    
    /**
     * Количество fast checkout за сегодня
     */
    @Builder.Default
    private Integer fastCheckoutTodayCount = 0;
    
    /**
     * Дата сброса счётчика fast checkout
     */
    private LocalDateTime fastCheckoutCountResetAt;
    
    /**
     * Постоянная скидка (не сгорает). Кэшированное значение, обновляется при каждой покупке.
     */
    private Integer permanentDiscountPercent;
    
    // ========== Устаревшие поля ==========
    
    // Устаревшие поля (для обратной совместимости, будут удалены после миграции)
    @Deprecated
    @Builder.Default
    private Double monthlySpent = 0.0;
    @Deprecated
    private LocalDateTime lastMonthReset;
    
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private UserRole role = UserRole.USER;
    
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private UserState state = UserState.NEW;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        lastMonthReset = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public enum UserRole {
        USER, ADMIN
    }
    
    public enum UserState {
        NEW,
        AWAITING_PHONE,
        REGISTERED,
        AWAITING_ADMIN_CODE,
        AWAITING_PURCHASE_AMOUNT,  // Ожидание ввода суммы покупки
        AWAITING_FAST_OR_AMOUNT_CHOICE,  // Ожидание выбора: быстро или с суммой
        AWAITING_REDEEM_CODE,  // Ожидание ввода кода погашения награды (для админа)
        AWAITING_PROMOTION_DISCOUNT,  // Ожидание ввода процента скидки для акции
        AWAITING_PROMOTION_DURATION,  // Ожидание ввода срока действия акции
        AWAITING_PROMOTION_DESCRIPTION,  // Ожидание ввода описания акции
        AWAITING_CLIENT_NOTE  // Ожидание ввода заметки о клиенте (для персонала)
    }
    
    /**
     * Возвращает процент скидки (5%, 7% или 10%)
     * Скидка действительна только если не истёк срок действия
     */
    public double getDiscountPercent() {
        if (!isDiscountValid()) {
            return 0.0;
        }
        
        if (discountLevel == null) {
            return 0.0;
        }
        
        return discountLevel / 100.0;
    }
    
    /**
     * Возвращает процент скидки с учётом заданного срока действия.
     * @param validityDays 0 = бессрочная
     */
    public double getDiscountPercent(int validityDays) {
        if (!isDiscountValid(validityDays)) {
            return 0.0;
        }
        if (discountLevel == null) {
            return 0.0;
        }
        return discountLevel / 100.0;
    }
    
    /**
     * Возвращает наибольший процент скидки среди истекающей и постоянной.
     */
    public double getEffectiveDiscountPercent() {
        double expiring = getDiscountPercent();
        double permanent = permanentDiscountPercent != null ? permanentDiscountPercent / 100.0 : 0.0;
        return Math.max(expiring, permanent);
    }
    
    /**
     * Возвращает наибольший процент скидки с учётом срока действия из настроек.
     */
    public double getEffectiveDiscountPercent(int validityDays) {
        double expiring = getDiscountPercent(validityDays);
        double permanent = permanentDiscountPercent != null ? permanentDiscountPercent / 100.0 : 0.0;
        return Math.max(expiring, permanent);
    }
    
    /**
     * Проверяет, действительна ли скидка (дефолт: 30 дней)
     */
    public boolean isDiscountValid() {
        return isDiscountValid(30);
    }
    
    /**
     * Проверяет, действительна ли скидка.
     * @param validityDays 0 = бессрочная (никогда не истекает)
     */
    public boolean isDiscountValid(int validityDays) {
        if (discountEarnedAt == null || discountLevel == null) {
            return false;
        }
        if (validityDays <= 0) {
            return true;
        }
        return LocalDateTime.now().isBefore(discountEarnedAt.plusDays(validityDays));
    }
    
    /**
     * Возвращает дату истечения скидки (null = бессрочная)
     */
    public LocalDateTime getDiscountExpiresAt() {
        return getDiscountExpiresAt(30);
    }
    
    /**
     * @param validityDays 0 = бессрочная, возвращает null
     */
    public LocalDateTime getDiscountExpiresAt(int validityDays) {
        if (discountEarnedAt == null || validityDays <= 0) {
            return null;
        }
        return discountEarnedAt.plusDays(validityDays);
    }
    
    /**
     * @deprecated Используйте ShopSettingsService.calculateDiscountLevel() вместо этого метода.
     * Пороги скидок теперь настраиваются в ShopSettings.
     * 
     * Определяет уровень скидки на основе накопленной суммы (fallback значения)
     * 10% - при 30,000 руб или больше
     * 7% - при 25,000 руб или больше
     * 5% - при 20,000 руб или больше
     */
    @Deprecated
    public static Integer calculateDiscountLevel(double amount) {
        if (amount >= 30000) {
            return 10;
        } else if (amount >= 25000) {
            return 7;
        } else if (amount >= 20000) {
            return 5;
        } else {
            return null;
        }
    }
    
    /**
     * @deprecated Используйте ShopSettingsService.getRequiredAmountForDiscount() вместо этого метода.
     * 
     * Возвращает минимальную сумму для текущего уровня скидки (fallback значения)
     */
    @Deprecated
    public Double getRequiredAmountForCurrentDiscount() {
        if (discountLevel == null) {
            return 20000.0;
        }
        
        if (discountLevel >= 10) {
            return 30000.0;
        } else if (discountLevel >= 7) {
            return 25000.0;
        } else {
            return 20000.0;
        }
    }
    
    /**
     * Возвращает дату, с которой нужно считать транзакции для накопления
     */
    public LocalDateTime getAccumulationStartDate() {
        if (discountEarnedAt != null && isDiscountValid()) {
            // Если скидка активна - копим с момента активации
            return discountEarnedAt;
        } else if (discountEarnedAt != null) {
            // Если скидка истекла - копим с момента истечения
            return discountEarnedAt.plusDays(30);
        } else {
            // Если скидки никогда не было - копим с начала регистрации
            return createdAt;
        }
    }
    
    /**
     * Возвращает текстовое описание текущей накопительной скидки
     */
    public String getDiscountDescription() {
        double percent = getDiscountPercent();
        if (percent >= 0.10) {
            return "10% скидка (Премиум)";
        } else if (percent >= 0.07) {
            return "7% скидка (VIP)";
        } else if (percent >= 0.05) {
            return "5% скидка (Базовый)";
        } else {
            return "Нет скидки";
        }
    }
    
    /**
     * @deprecated Используйте ShopSettingsService.getDiscountProgressInfo() вместо этого метода.
     * 
     * Возвращает информацию о прогрессе накопления или продления скидки (fallback)
     * ВАЖНО: Требует передачи накопленной суммы из транзакций
     */
    @Deprecated
    public String getNextDiscountLevelInfo(double accumulatedFromTransactions) {
        // Если скидка действительна, показываем прогресс для продления
        if (isDiscountValid()) {
            LocalDateTime expiresAt = getDiscountExpiresAt();
            if (expiresAt != null) {
                long daysLeft = java.time.Duration.between(LocalDateTime.now(), expiresAt).toDays();
                Double requiredAmount = getRequiredAmountForCurrentDiscount();
                double remaining = requiredAmount - accumulatedFromTransactions;
                
                if (remaining <= 0) {
                    return String.format("Скидка активна еще %d дней. Совершите покупку для продления! 🎉", daysLeft);
                } else {
                    return String.format("До продления скидки: %.2f руб. (осталось %d дней)", remaining, daysLeft);
                }
            }
        }
        
        // Если скидки нет, показываем прогресс накопления
        if (accumulatedFromTransactions >= 30000) {
            return "Накоплено на максимальную скидку 10%! 🎉";
        } else if (accumulatedFromTransactions >= 25000) {
            return String.format("До скидки 10%%: %.2f руб.", 30000 - accumulatedFromTransactions);
        } else if (accumulatedFromTransactions >= 20000) {
            return String.format("До скидки 7%%: %.2f руб.", 25000 - accumulatedFromTransactions);
        } else {
            return String.format("До скидки 5%%: %.2f руб.", 20000 - accumulatedFromTransactions);
        }
    }
    
    // ========== Customer Profile методы ==========
    
    /**
     * Возвращает средний чек клиента
     */
    public Double getAverageCheck() {
        if (purchasesCount == null || purchasesCount == 0 || totalSpend == null) {
            return 0.0;
        }
        return totalSpend / purchasesCount;
    }
    
    /**
     * Возвращает количество дней с последней покупки
     */
    public Long getDaysSinceLastPurchase() {
        if (lastPurchaseAt == null) {
            return null;
        }
        return java.time.Duration.between(lastPurchaseAt, LocalDateTime.now()).toDays();
    }
    
    /**
     * Проверяет, можно ли выполнить fast checkout (cooldown не истёк)
     */
    public boolean canFastCheckout(int cooldownMinutes) {
        if (lastFastCheckoutAt == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(lastFastCheckoutAt.plusMinutes(cooldownMinutes));
    }
    
    /**
     * Проверяет, не превышен ли дневной лимит fast checkout
     */
    public boolean canFastCheckoutToday(int dailyLimit) {
        resetFastCheckoutCountIfNeeded();
        return fastCheckoutTodayCount == null || fastCheckoutTodayCount < dailyLimit;
    }
    
    /**
     * Сбрасывает счётчик fast checkout, если наступил новый день
     */
    public void resetFastCheckoutCountIfNeeded() {
        if (fastCheckoutCountResetAt == null || 
            !fastCheckoutCountResetAt.toLocalDate().equals(java.time.LocalDate.now())) {
            fastCheckoutTodayCount = 0;
            fastCheckoutCountResetAt = LocalDateTime.now();
        }
    }
    
    /**
     * Увеличивает счётчик fast checkout
     */
    public void incrementFastCheckoutCount() {
        resetFastCheckoutCountIfNeeded();
        if (fastCheckoutTodayCount == null) {
            fastCheckoutTodayCount = 0;
        }
        fastCheckoutTodayCount++;
        lastFastCheckoutAt = LocalDateTime.now();
    }
    
    /**
     * Обновляет статистику после покупки
     */
    public void recordPurchase(Double amount, boolean isFastCheckout) {
        LocalDateTime now = LocalDateTime.now();
        
        if (firstPurchaseAt == null) {
            firstPurchaseAt = now;
        }
        lastPurchaseAt = now;
        
        if (purchasesCount == null) purchasesCount = 0;
        purchasesCount++;
        
        if (isFastCheckout) {
            if (visitsCount == null) visitsCount = 0;
            visitsCount++;
        }
        
        if (amount != null && amount > 0) {
            if (totalSpend == null) totalSpend = 0.0;
            totalSpend += amount;
        }
    }
    
    /**
     * Возвращает информацию о статусе клиента
     */
    public String getStatusInfo() {
        if (customerStatus == null) {
            return CustomerStatus.NEW.getDisplayWithEmoji();
        }
        return customerStatus.getDisplayWithEmoji();
    }
}
