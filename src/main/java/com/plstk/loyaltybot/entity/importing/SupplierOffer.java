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
    @Index(name = "idx_supplier_offers_shop_source", columnList = "shopId, supplier_source_id"),
    @Index(name = "idx_supplier_offers_shop_supplier_scope", columnList = "shopId, supplier_id, snapshotScope")
}, uniqueConstraints = {
    // Stage 3 identity fix: a bare (shop, supplier, product) key made two independent
    // snapshotScopes of the same supplier collide onto ONE row, so whichever file was applied
    // last silently "stole" the offer and made the other scope's FULL reconciliation blind to it
    // (see ImportBatchApplyWriter/SupplierOfferRepository). The persisted `snapshotScope` below
    // widens identity to (shop, supplier, snapshotScope, product): several transport sources that
    // share one scope (e.g. a re-sent duplicate file) still upsert the same logical offer, but two
    // different scopes of the same supplier now always get distinct rows and never deactivate each
    // other's offers.
    @UniqueConstraint(name = "uk_supplier_offers_shop_supplier_scope_product",
            columnNames = {"shopId", "supplier_id", "snapshotScope", "product_id"})
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

    /**
     * Snapshot scope this offer belongs to, copied from {@code supplierSource.snapshotScope} at
     * write time (NOT a live join - see the class javadoc and the unique constraint above). Part of
     * the offer's identity: reassigning a {@link SupplierSource} to a different scope after offers
     * already exist must never retroactively change which offers a past/future FULL apply of the
     * old scope is allowed to see or deactivate.
     */
    @Column(nullable = false, length = 255)
    @Builder.Default
    private String snapshotScope = "SUPPLIER_ALL";

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
