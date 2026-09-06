package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Запуск обработки одного {@link ImportFile}. {@code AttachmentIngestionService} идемпотентно
 * создаёт ровно один batch в статусе {@link ImportBatchStatus#STORED} на файл; повторная
 * обработка (Prompt 03+) — явная операция, использующая {@link #attemptNumber}, а не
 * перезапись существующего аудита.
 */
@Entity
@Table(name = "import_batches", indexes = {
    @Index(name = "idx_import_batches_shop_id", columnList = "shopId"),
    @Index(name = "idx_import_batches_shop_source_status", columnList = "shopId, supplier_source_id, status"),
    /** Dashboard/exceptions batch list (countByShopIdAndStatus(In), findByShopIdAndStatusIn...)
     * filters by shopId + status without a supplier_source_id predicate, so it can't efficiently
     * use idx_import_batches_shop_source_status (supplier_source_id sits between the two columns
     * it actually needs). */
    @Index(name = "idx_import_batches_shop_id_status", columnList = "shopId, status")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_import_batches_import_file", columnNames = {"import_file_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_source_id", nullable = false)
    private SupplierSource supplierSource;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    private ImportFile importFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_version_id")
    private ImportRuleVersion ruleVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ImportBatchStatus status = ImportBatchStatus.STORED;

    @Column(nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    private Integer totalRows;

    private Integer validRows;

    private Integer invalidRows;

    /**
     * Human-readable reason for the current terminal-ish status: quarantine guard failure reasons
     * (Prompt 06), {@code NEEDS_ATTENTION} explanation, or an unexpected {@code FAILED} exception
     * message from any pipeline stage. Not structured - see docs/STATE.md ADR-005 limitation #4 for
     * the same tradeoff already accepted for {@code MatchDecision.reason}.
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** Set once the Prompt 06 Apply stage reaches {@code APPLIED} for this batch. */
    private LocalDateTime appliedAt;

    // ===== Apply audit counters (Prompt 06, docs/ARCHITECTURE.md §14.11) - all null until APPLIED. =====

    /** New {@code SupplierOffer} rows created by this batch's apply. */
    private Integer offersAddedCount;

    /** Pre-existing {@code SupplierOffer} rows touched (price/stock/identifier refresh) by this apply. */
    private Integer offersUpdatedCount;

    /** Subset of {@link #offersUpdatedCount} whose {@code calculatedSitePrice} actually changed value. */
    private Integer offersPriceChangedCount;

    /** Subset of {@link #offersUpdatedCount} where nothing materially changed (price/stock/identifiers). */
    private Integer offersUnchangedCount;

    /** Products that became invisible on storefront because this apply deactivated their last active offer. */
    private Integer productsRemovedFromStorefrontCount;

    /** Products that became visible on storefront again because of a reactivated/new offer in this apply. */
    private Integer productsReactivatedCount;

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
