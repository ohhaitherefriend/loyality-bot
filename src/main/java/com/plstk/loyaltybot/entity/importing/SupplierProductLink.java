package com.plstk.loyaltybot.entity.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Запомненная связь supplier SKU/barcode/fingerprint -> canonical {@code Product}.
 * После подтверждения (автоматического или ручного) следующий import того же поставщика
 * не требует AI matching. {@link #product} может быть {@code null}, пока строка не сопоставлена.
 */
@Entity
@Table(name = "supplier_product_links", indexes = {
    @Index(name = "idx_supplier_product_links_shop_id", columnList = "shopId"),
    @Index(name = "idx_supplier_product_links_shop_supplier", columnList = "shopId, supplier_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_supplier_product_links_sku", columnNames = {"shopId", "supplier_id", "externalSku"}),
    @UniqueConstraint(name = "uk_supplier_product_links_barcode", columnNames = {"shopId", "supplier_id", "barcode"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierProductLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(length = 255)
    private String externalSku;

    @Column(length = 255)
    private String barcode;

    /** Normalized full-name fingerprint используемый как fallback точный сигнал. */
    @Column(length = 512)
    private String fingerprint;

    /**
     * ADR-030: которая версия {@code RowAttributeNormalizer.NORMALIZATION_VERSION} произвела
     * ТЕКУЩЕЕ значение {@link #fingerprint}. Смена алгоритма fingerprint (например, чтобы бренд
     * учитывал настроенные алиасы) меняет саму строку для одного и того же товара - без этой
     * версии старая связь либо продолжила бы молча сравниваться со свежими fingerprint по
     * буквальному совпадению (что почти всегда ложно после смены формата), либо потребовала бы
     * рискованного in-place backfill без возможности отличить "уже пересчитано" от "ещё нет".
     * {@code SupplierLinkFingerprintMigrationService} пересчитывает {@link #fingerprint} и эту
     * версию для всех связей со старой версией, используя ТЕКУЩИЙ каталог и алиасы магазина -
     * старые связи не остаются молча нерабочими, а безопасно обновляются.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer normalizationVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private LinkConfirmationSource confirmedSource = LinkConfirmationSource.MANUAL;

    private LocalDateTime confirmedAt;

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
