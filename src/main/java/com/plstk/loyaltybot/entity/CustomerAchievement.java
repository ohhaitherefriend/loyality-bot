package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Полученная ачивка клиента.
 * Хранит факт получения ачивки и метаданные.
 */
@Entity
@Table(name = "customer_achievements")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAchievement {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "achievement_id", nullable = false)
    private AchievementDefinition achievement;
    
    /**
     * Дата и время получения ачивки
     */
    @Column(nullable = false)
    private LocalDateTime awardedAt;
    
    /**
     * Дополнительные метаданные (JSON)
     * Например: {"purchaseId": 123, "amount": 5000}
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    /**
     * Была ли применена награда (если есть)
     */
    @Builder.Default
    private Boolean rewardApplied = false;
    
    /**
     * Дата применения награды
     */
    private LocalDateTime rewardAppliedAt;
    
    /**
     * Было ли уведомление отправлено клиенту
     */
    @Builder.Default
    private Boolean notificationSent = false;
    
    @PrePersist
    protected void onCreate() {
        if (awardedAt == null) {
            awardedAt = LocalDateTime.now();
        }
    }
}

