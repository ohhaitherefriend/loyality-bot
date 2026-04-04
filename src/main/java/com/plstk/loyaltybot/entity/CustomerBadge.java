package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Выданный бейдж клиенту.
 * Хранит информацию о сроке действия и статусе.
 */
@Entity
@Table(name = "customer_badges")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBadge {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "badge_id", nullable = false)
    private ManualBadgeDefinition badge;
    
    /**
     * Админ, выдавший бейдж
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "awarded_by_id")
    private User awardedBy;
    
    /**
     * Дата выдачи
     */
    @Column(nullable = false)
    private LocalDateTime awardedAt;
    
    /**
     * Дата истечения (null = бессрочно)
     */
    private LocalDateTime expiresAt;
    
    /**
     * Статус бейджа
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private BadgeStatus status = BadgeStatus.ACTIVE;
    
    /**
     * Причина выдачи (опционально)
     */
    private String reason;
    
    /**
     * Дополнительные метаданные (JSON)
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
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
    
    /**
     * Статусы бейджа
     */
    public enum BadgeStatus {
        ACTIVE("Активен"),
        EXPIRED("Истёк"),
        REVOKED("Отозван");
        
        private final String displayName;
        
        BadgeStatus(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Проверяет, активен ли бейдж
     */
    public boolean isActive() {
        if (status != BadgeStatus.ACTIVE) {
            return false;
        }
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return false;
        }
        return true;
    }
    
    /**
     * Проверяет, истёк ли бейдж
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * Возвращает количество дней до истечения
     */
    public Long getDaysUntilExpiration() {
        if (expiresAt == null) {
            return null;
        }
        long days = java.time.Duration.between(LocalDateTime.now(), expiresAt).toDays();
        return Math.max(0, days);
    }
    
    /**
     * Возвращает форматированное отображение бейджа
     */
    public String getFormattedDisplay() {
        ManualBadgeDefinition def = badge;
        StringBuilder sb = new StringBuilder();
        
        sb.append(def.getEmoji()).append(" *").append(def.getTitle()).append("*");
        
        if (def.getPerkType() != ManualBadgeDefinition.PerkType.NONE) {
            sb.append("\n").append(def.getPerkDescription());
        }
        
        if (expiresAt != null && isActive()) {
            Long daysLeft = getDaysUntilExpiration();
            if (daysLeft != null && daysLeft <= 7) {
                sb.append("\n⏰ Истекает через ").append(daysLeft).append(" дн.");
            }
        }
        
        return sb.toString();
    }
}

