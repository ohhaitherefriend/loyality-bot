package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private Long chatId;
    
    @Column(unique = true, nullable = false)
    private String phoneNumber;
    
    private String firstName;
    private String lastName;
    private String username;
    
    private LocalDateTime discountEarnedAt;  // Дата когда была активирована скидка (или последнего продления)
    private Integer discountLevel;  // Уровень скидки (5, 7 или 10 процентов)
    
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
        AWAITING_PROMOTION_DISCOUNT,  // Ожидание ввода процента скидки для акции
        AWAITING_PROMOTION_DURATION,  // Ожидание ввода срока действия акции
        AWAITING_PROMOTION_DESCRIPTION  // Ожидание ввода описания акции
    }
    
    /**
     * Возвращает процент скидки (5%, 7% или 10%)
     * Скидка действительна только если не истек срок действия (30 дней)
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
     * Проверяет, действительна ли скидка (не прошло ли 30 дней с момента активации)
     */
    public boolean isDiscountValid() {
        if (discountEarnedAt == null || discountLevel == null) {
            return false;
        }
        
        // Проверяем, не прошло ли 30 дней с момента активации
        return LocalDateTime.now().isBefore(discountEarnedAt.plusDays(30));
    }
    
    /**
     * Возвращает дату истечения скидки
     */
    public LocalDateTime getDiscountExpiresAt() {
        if (discountEarnedAt == null) {
            return null;
        }
        
        return discountEarnedAt.plusDays(30);
    }
    
    /**
     * Определяет уровень скидки на основе накопленной суммы
     * 10% - при 30,000 руб или больше
     * 7% - при 25,000 руб или больше
     * 5% - при 20,000 руб или больше
     */
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
     * Возвращает минимальную сумму для текущего уровня скидки
     */
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
     * Возвращает информацию о прогрессе накопления или продления скидки
     * ВАЖНО: Требует передачи накопленной суммы из транзакций
     */
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
}
