package com.plstk.loyaltybot.entity.commerce;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_products_shop_id", columnList = "shopId"),
    @Index(name = "idx_products_shop_visible_active", columnList = "shopId, visible, active"),
    @Index(name = "idx_products_shop_brand", columnList = "shopId, brand"),
    @Index(name = "idx_products_shop_barcode", columnList = "shopId, barcode")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @Column(length = 255)
    private String supplierGuid;

    @Column(length = 255)
    private String sourceSheet;

    private Integer sourceRow;

    @Column(length = 255)
    private String brand;

    @Column(length = 255)
    private String supplierArticle;

    @Column(length = 255)
    private String barcode;

    @Column(nullable = false, length = 1024)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 1024)
    private String categoryPath;

    @Column(precision = 19, scale = 2)
    private BigDecimal supplierPrice;

    @Column(precision = 19, scale = 2)
    private BigDecimal salePrice;

    @Column(precision = 19, scale = 2)
    private BigDecimal oldPrice;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "RUB";

    private Integer stockQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private AvailabilityMode availabilityMode = AvailabilityMode.PREORDER;

    @Column(nullable = false)
    @Builder.Default
    private Boolean visible = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(length = 2048)
    private String mainImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ImageStatus imageStatus = ImageStatus.MISSING;

    private LocalDate priceListDate;

    private LocalDateTime lastImportedAt;

    private LocalDateTime imageUpdatedAt;

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
