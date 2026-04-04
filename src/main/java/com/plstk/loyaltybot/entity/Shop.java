package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Магазин/бизнес в системе.
 * Связывает AdminUser (владельца) с BotInstance и настройками.
 */
@Entity
@Table(name = "shops", indexes = {
    @Index(name = "idx_shop_shop_id", columnList = "shopId", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Shop {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * UUID идентификатор магазина.
     * Совпадает с shopId в BotInstance и ShopSettings.
     */
    @Column(nullable = false, unique = true, length = 36)
    private String shopId;
    
    /**
     * Название магазина
     */
    @Column(nullable = false, length = 255)
    private String name;
    
    /**
     * Часовой пояс (например, Europe/Moscow)
     */
    @Column(length = 64)
    @Builder.Default
    private String timezone = "Europe/Moscow";
    
    /**
     * ID владельца (AdminUser)
     */
    @Column(nullable = false)
    private Long ownerId;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (shopId == null) {
            shopId = UUID.randomUUID().toString();
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
