package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Связь между AdminUser и Shop с указанием роли.
 * Позволяет одному пользователю иметь доступ к нескольким магазинам.
 */
@Entity
@Table(name = "shop_members", indexes = {
    @Index(name = "idx_shop_member_user_id", columnList = "userId"),
    @Index(name = "idx_shop_member_shop_id", columnList = "shopId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_shop_member_user_shop", columnNames = {"userId", "shopId"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopMember {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * ID пользователя (AdminUser)
     */
    @Column(nullable = false)
    private Long userId;
    
    /**
     * ID магазина (Shop.shopId)
     */
    @Column(nullable = false, length = 36)
    private String shopId;
    
    /**
     * Роль пользователя в магазине
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MemberRole role = MemberRole.STAFF;
    
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    /**
     * Роли пользователей в магазине
     */
    public enum MemberRole {
        OWNER,  // Владелец - полный доступ
        ADMIN,  // Администратор - управление настройками
        STAFF   // Персонал - только просмотр отчётов
    }
}
