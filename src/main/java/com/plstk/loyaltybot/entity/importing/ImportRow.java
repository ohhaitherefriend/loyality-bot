package com.plstk.loyaltybot.entity.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Одна строка исходного файла внутри batch: raw + normalized JSON, статус матчинга и
 * (опционально) выбранный canonical product. Заполняется парсером/нормализатором/matcher-ом
 * в Prompt 03-06; схема нужна уже в Prompt 01, чтобы не переписывать persistence позже.
 */
@Entity
@Table(name = "import_rows", indexes = {
    @Index(name = "idx_import_rows_shop_id", columnList = "shopId"),
    @Index(name = "idx_import_rows_batch_id", columnList = "import_batch_id"),
    /** Exception queue (findExceptionRows) and dashboard counts (countByShopIdAndStatusIn) both
     * filter by shopId + status; this composite index avoids a full shopId-index scan across a
     * shop's entire row history as batch volume grows. */
    @Index(name = "idx_import_rows_shop_id_status", columnList = "shopId, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_batch_id", nullable = false)
    private ImportBatch importBatch;

    @Column(length = 255)
    private String sourceSheet;

    private Integer sourceRowNumber;

    /** Raw JSON строки как её увидел parser, без интерпретации. */
    @Column(columnDefinition = "TEXT")
    private String rawData;

    /** Normalized JSON: brand/line/variant/volume+unit/concentration/shade/SKU/barcode/price/stock. */
    @Column(columnDefinition = "TEXT")
    private String normalizedData;

    /**
     * JSON array of top-N explainable {@code ScoredCandidate}s from the deterministic candidate
     * search (Prompt 04), persisted so the future AI matcher (Prompt 05) does not need to recompute
     * candidate search. Only populated when no deterministic (link/barcode/fingerprint) match was
     * found; {@code null} otherwise.
     */
    @Column(columnDefinition = "TEXT")
    private String candidateSearchResult;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    @Builder.Default
    private ImportRowStatus status = ImportRowStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_product_id")
    private Product matchedProduct;

    /**
     * Optimistic lock (Prompt 07 operations UI, docs/ARCHITECTURE.md §12 "Используй optimistic
     * locking"): a human row-decision action must include the version it read; a stale version
     * (e.g. two operators reviewing the same row, or the automatic pipeline having already moved
     * the row on) fails the write instead of silently overwriting a concurrent decision.
     */
    @Version
    private Long version;

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
