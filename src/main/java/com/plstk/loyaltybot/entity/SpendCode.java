package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "spend_codes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpendCode {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String code;
    
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @Column(nullable = false)
    private Integer pointsToSpend;
    
    @Enumerated(EnumType.STRING)
    private CodeStatus status = CodeStatus.ACTIVE;
    
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
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    public enum CodeStatus {
        ACTIVE,
        USED,
        EXPIRED
    }
}
