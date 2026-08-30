package com.plstk.loyaltybot.entity.commerce;

import com.plstk.loyaltybot.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_orders", indexes = {
    @Index(name = "idx_customer_orders_shop_created", columnList = "shopId, createdAt"),
    @Index(name = "idx_customer_orders_shop_status", columnList = "shopId, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private OrderStatus status = OrderStatus.DRAFT;

    @Column(precision = 19, scale = 2)
    private BigDecimal itemsTotal;

    @Column(precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal bonusSpent = BigDecimal.ZERO;

    @Column(precision = 19, scale = 2)
    private BigDecimal totalToPay;

    @Column(precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal bonusAccrued = BigDecimal.ZERO;

    @Column(length = 64)
    private String customerPhone;

    @Column(length = 255)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private DeliveryType deliveryType;

    @Column(columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(columnDefinition = "TEXT")
    private String customerComment;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    @Builder.Default
    private OrderSource source = OrderSource.BOT;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    private LocalDateTime cancelledAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
