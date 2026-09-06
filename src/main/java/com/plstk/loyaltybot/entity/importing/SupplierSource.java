package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Источник прайс-листов конкретного поставщика: snapshot policy, commission override,
 * public price strategy, rounding, shadow/auto-apply. Mailbox routing filters (sender/domain
 * allowlist, subject/filename regex) добавлены в Prompt 02: несколько {@code SupplierSource}
 * могут указывать на один {@link MailboxConnection} (общий ящик для нескольких поставщиков),
 * поэтому folder живёт на mailbox, а sender/subject/filename — здесь, на source.
 */
@Entity
@Table(name = "supplier_sources", indexes = {
    @Index(name = "idx_supplier_sources_shop_id", columnList = "shopId"),
    @Index(name = "idx_supplier_sources_shop_supplier", columnList = "shopId, supplier_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_supplier_sources_shop_supplier_label", columnNames = {"shopId", "supplier_id", "label"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(nullable = false, length = 255)
    private String label;

    /**
     * Mailbox, из которого этот source получает вложения. Nullable: source может пока не иметь
     * почтового адаптера (например, будущий manual-only source из Prompt 08).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mailbox_connection_id")
    private MailboxConnection mailboxConnection;

    /**
     * Allowlist отправителей: одна запись на строку — либо полный email
     * ({@code price@supplier.ru}), либо домен ({@code supplier.ru} или {@code @supplier.ru}).
     * Пусто/null означает "любой отправитель этого mailbox" (не рекомендуется в production).
     */
    @Column(columnDefinition = "TEXT")
    private String senderAllowlist;

    /** Опциональный regex по теме письма (Java regex). Null — тема не фильтруется. */
    @Column(length = 512)
    private String subjectPattern;

    /**
     * Опциональный дополнительный regex по имени файла вложения (Java regex), для routing между
     * несколькими source на одном mailbox. Допустимые расширения (.xlsx/.xls, не .xlsm) — это
     * отдельное, не настраиваемое ограничение уровня pipeline, а не этого поля.
     */
    @Column(length = 512)
    private String filenamePattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SnapshotMode snapshotMode = SnapshotMode.FULL;

    /**
     * Scope, внутри которого FULL snapshot является authoritative (например,
     * {@code SUPPLIER_ALL} либо конкретная категория/склад). Деактивация offers
     * никогда не выходит за пределы scope текущего файла.
     */
    @Column(nullable = false, length = 255)
    @Builder.Default
    private String snapshotScope = "SUPPLIER_ALL";

    @Column(precision = 7, scale = 2)
    private BigDecimal commissionPercentOverride;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private PublicPriceStrategy publicPriceStrategy = PublicPriceStrategy.LOWEST_ACTIVE_OFFER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private PriceRoundingPolicy roundingPolicy = PriceRoundingPolicy.WHOLE_UNIT_HALF_UP;

    /**
     * В shadow mode pipeline строит decisions/diff, но apply не выполняется.
     * Новый источник должен начинать в shadow mode, пока метрики не подтвердят качество.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean shadowMode = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoApply = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * Per-source override of {@code supplier-import.matching.ai-auto-approve-min-score} (Prompt 05).
     * Null means "use the global default". This is the deterministic {@code ScoredCandidate.totalScore}
     * floor an AI-selected candidate must clear to auto-approve - see {@code ImportBatchMatchingService}.
     * A later change to this value only affects future {@code MatchDecision}s; past decisions remain an
     * immutable audit record regardless (the practical meaning of "thresholds versioned per source" here).
     */
    @Column(precision = 5, scale = 4)
    private BigDecimal aiAutoApproveMinScoreOverride;

    /** Per-source override of {@code supplier-import.matching.ai-min-confidence} (Prompt 05). Null = global default. */
    @Column(precision = 5, scale = 4)
    private BigDecimal aiMinConfidenceOverride;

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
