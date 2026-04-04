package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "purchase_codes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseCode {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String code;
    
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CodeStatus status = CodeStatus.ACTIVE;
    
    /**
     * ID локации (для сетей с несколькими точками)
     */
    private String locationId;
    
    /**
     * Был ли код создан через QR deep-link
     */
    @Builder.Default
    private Boolean fromDeepLink = false;
    
    /**
     * Был ли код использован через fast checkout
     */
    @Builder.Default
    private Boolean fastCheckout = false;
    
    /**
     * Сумма покупки (заполняется после использования)
     */
    private Double purchaseAmount;
    
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    
    @ManyToOne
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
        return status == CodeStatus.ACTIVE && !isExpired();
    }
    
    public enum CodeStatus {
        ACTIVE,
        USED,
        EXPIRED
    }
}