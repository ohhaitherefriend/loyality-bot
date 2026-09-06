package com.plstk.loyaltybot.entity.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Цена/остаток/доступность конкретного поставщика для конкретного canonical product.
 * Один товар может иметь несколько offers (D-003/D-008). Товар остаётся на storefront, пока
 * активен хотя бы один offer (D-005/D-006); физическое удаление Product не выполняется.
 */
@Entity
@Table(name = "supplier_offers", indexes = {
    @Index(name = "idx_supplier_offers_shop_id", columnList = "shopId"),
    @Index(name = "idx_supplier_offers_shop_product_active", columnList = "shopId, product_id, active"),
    @Index(name = "idx_supplier_offers_shop_source", columnList = "shopId, supplier_source_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_supplier_offers_shop_supplier_product", columnNames = {"shopId", "supplier_id", "product_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_source_id", nullable = false)
    private SupplierSource supplierSource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(length = 255)
    private String externalSku;

    @Column(length = 255)
    private String barcode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal supplierPrice;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal appliedCommissionPercent;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal calculatedSitePrice;

    private Integer stockQuantity;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Id последнего batch, где offer был встречен. Используется для FULL-snapshot
     * reconciliation вместо доверия одному только timestamp.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_seen_batch_id")
    private ImportBatch lastSeenBatch;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private LocalDateTime deactivatedAt;

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
