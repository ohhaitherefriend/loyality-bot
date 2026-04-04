package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Штамп-карта клиента.
 * Хранит прогресс накопления штампов и количество полученных наград.
 */
@Entity
@Table(name = "stamp_wallets", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StampWallet {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * Текущее количество штампов (от 0 до stampsRequiredForReward-1)
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer stampsCount = 0;
    
    /**
     * Общее количество заработанных наград
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer rewardsEarned = 0;
    
    /**
     * Количество доступных (непогашенных) наград
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer rewardsAvailable = 0;
    
    /**
     * Общее количество погашенных наград
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer rewardsRedeemed = 0;
    
    /**
     * Общее количество штампов за всё время (для статистики)
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer totalStampsEarned = 0;
    
    /**
     * Дата последнего начисления штампа
     */
    private LocalDateTime lastStampAt;
    
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
     * Добавляет штампы и проверяет, заработана ли награда
     * @param stamps количество штампов для добавления
     * @param stampsRequired количество штампов для награды (из настроек)
     * @return true, если была заработана новая награда
     */
    public boolean addStamps(int stamps, int stampsRequired) {
        if (stampsCount == null) stampsCount = 0;
        if (totalStampsEarned == null) totalStampsEarned = 0;
        if (rewardsEarned == null) rewardsEarned = 0;
        if (rewardsAvailable == null) rewardsAvailable = 0;
        
        totalStampsEarned += stamps;
        stampsCount += stamps;
        lastStampAt = LocalDateTime.now();
        
        boolean earnedReward = false;
        
        // Проверяем, набрал ли клиент достаточно штампов для награды
        while (stampsCount >= stampsRequired) {
            stampsCount -= stampsRequired;
            rewardsEarned++;
            rewardsAvailable++;
            earnedReward = true;
        }
        
        return earnedReward;
    }
    
    /**
     * Использует одну награду
     * @return true, если награда была использована
     */
    public boolean redeemReward() {
        if (rewardsAvailable == null || rewardsAvailable <= 0) {
            return false;
        }
        
        rewardsAvailable--;
        if (rewardsRedeemed == null) rewardsRedeemed = 0;
        rewardsRedeemed++;
        
        return true;
    }
    
    /**
     * Проверяет, есть ли доступные награды
     */
    public boolean hasAvailableRewards() {
        return rewardsAvailable != null && rewardsAvailable > 0;
    }
    
    /**
     * Возвращает прогресс до следующей награды (0.0 - 1.0)
     */
    public double getProgress(int stampsRequired) {
        if (stampsCount == null || stampsRequired <= 0) {
            return 0.0;
        }
        return (double) stampsCount / stampsRequired;
    }
    
    /**
     * Возвращает количество штампов до награды
     */
    public int getStampsUntilReward(int stampsRequired) {
        if (stampsCount == null) {
            return stampsRequired;
        }
        return stampsRequired - stampsCount;
    }
}



