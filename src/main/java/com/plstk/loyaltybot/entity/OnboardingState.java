package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Состояние онбординга пользователя.
 * Отслеживает прогресс wizard'а при первичной настройке.
 */
@Entity
@Table(name = "onboarding_states", indexes = {
    @Index(name = "idx_onboarding_user_id", columnList = "userId"),
    @Index(name = "idx_onboarding_shop_id", columnList = "shopId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnboardingState {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * ID пользователя (AdminUser)
     */
    @Column(nullable = false)
    private Long userId;
    
    /**
     * ID магазина (создаётся на шаге SHOP_CREATED)
     */
    @Column(length = 36)
    private String shopId;
    
    /**
     * Текущий шаг онбординга
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OnboardingStep step = OnboardingStep.START;
    
    /**
     * JSON с данными для черновика (незавершённые формы)
     */
    @Column(columnDefinition = "TEXT")
    private String dataJson;
    
    /**
     * Онбординг завершён
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean completed = false;
    
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
     * Шаги онбординга
     */
    public enum OnboardingStep {
        START,           // Начало - ещё ничего не сделано
        SHOP_CREATED,    // Магазин создан
        BOT_CONNECTED,   // Бот подключен
        SETTINGS_DONE,   // Настройки применены
        COMPLETED        // Онбординг завершён
    }
    
    /**
     * Создаёт новый онбординг для пользователя
     */
    public static OnboardingState createForUser(Long userId) {
        return OnboardingState.builder()
            .userId(userId)
            .step(OnboardingStep.START)
            .completed(false)
            .build();
    }
}
