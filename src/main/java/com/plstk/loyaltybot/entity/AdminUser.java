package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Пользователь Web UI админки.
 * Не путать с User (клиент программы лояльности в Telegram).
 */
@Entity
@Table(name = "admin_users", indexes = {
    @Index(name = "idx_admin_user_email", columnList = "email", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUser {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Email (используется как логин)
     */
    @Column(nullable = false, unique = true, length = 255)
    private String email;
    
    /**
     * BCrypt хеш пароля
     */
    @Column(nullable = false, length = 255)
    private String passwordHash;
    
    /**
     * Имя пользователя (опционально)
     */
    @Column(length = 255)
    private String name;
    
    /**
     * Активен ли аккаунт
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    
    /**
     * Email подтверждён (для будущего использования)
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean emailVerified = false;
    
    /**
     * Дата последнего входа
     */
    private LocalDateTime lastLoginAt;
    
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
}
