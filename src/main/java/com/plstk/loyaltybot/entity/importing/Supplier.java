package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Поставщик внутри магазина. Canonical {@code Product} остаётся общим каталогом;
 * закупочные данные конкретного поставщика живут в {@link SupplierOffer}.
 */
@Entity
@Table(name = "suppliers", indexes = {
    @Index(name = "idx_suppliers_shop_id", columnList = "shopId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_suppliers_shop_name", columnNames = {"shopId", "name"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String code;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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
