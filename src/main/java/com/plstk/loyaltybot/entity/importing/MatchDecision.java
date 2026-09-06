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
 * Audit одного matching-решения по {@link ImportRow}: кандидаты, выбранный product,
 * confidence, model/prompt version, конфликты и кто принял решение (SYSTEM/HUMAN).
 * Модель может выбрать только переданный candidate id либо NO_MATCH (D-009); это
 * поле не валидирует само по себе — валидация выполняется в matching-сервисе (Prompt 05).
 */
@Entity
@Table(name = "match_decisions", indexes = {
    @Index(name = "idx_match_decisions_shop_id", columnList = "shopId"),
    @Index(name = "idx_match_decisions_import_row_id", columnList = "import_row_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_row_id", nullable = false)
    private ImportRow importRow;

    /** JSON массив реальных candidate product id, переданных matcher-у. */
    @Column(columnDefinition = "TEXT")
    private String candidateProductIds;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chosen_product_id")
    private Product chosenProduct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MatchDecisionType decisionType;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    @Column(length = 64)
    private String modelProvider;

    @Column(length = 128)
    private String modelName;

    @Column(length = 64)
    private String promptVersion;

    /** JSON массив конфликтующих критических атрибутов (volume/shade/concentration/...). */
    @Column(columnDefinition = "TEXT")
    private String conflicts;

    @Column(length = 512)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private DecidedBy decidedBy = DecidedBy.SYSTEM;

    /**
     * Operator identity for {@link DecidedBy#HUMAN} decisions (Prompt 07 operations UI review
     * actions - MATCH/NO_MATCH/CREATE_PRODUCT/IGNORE). Always {@code null} for
     * {@link DecidedBy#SYSTEM} decisions; never used for authorization, only for audit display.
     */
    private Long reviewerUserId;

    @Column(length = 255)
    private String reviewerEmail;

    private LocalDateTime decidedAt;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        if (decidedAt == null) {
            decidedAt = now;
        }
    }
}
