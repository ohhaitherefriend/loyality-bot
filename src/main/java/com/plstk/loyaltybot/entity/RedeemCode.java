package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Код погашения награды.
 * Клиент генерирует код, который кассир вводит для подтверждения погашения.
 */
@Entity
@Table(name = "redeem_codes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemCode {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String code;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stamp_wallet_id", nullable = false)
    private StampWallet stampWallet;
    
    /**
     * Название награды (копия из настроек на момент создания)
     */
    @Column(nullable = false)
    private String rewardTitle;
    
    /**
     * Описание награды (копия из настроек)
     */
    private String rewardDescription;
    
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RedeemCodeStatus status = RedeemCodeStatus.ACTIVE;
    
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by_admin_id")
    private User usedByAdmin;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
    
    public boolean isActive() {
        return status == RedeemCodeStatus.ACTIVE && !isExpired();
    }
    
    public enum RedeemCodeStatus {
        ACTIVE,
        USED,
        EXPIRED,
        CANCELLED
    }
}



