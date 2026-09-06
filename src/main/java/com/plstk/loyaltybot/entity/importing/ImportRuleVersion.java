package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Immutable versioned правило парсинга/маппинга колонок конкретного {@link SupplierSource}.
 * Старый batch всегда ссылается на версию, по которой был разобран; запись никогда не
 * редактируется после создания — новая версия создаётся отдельной строкой.
 * {@code ruleDefinition} хранит JSON (column mapping, skip rules, defaults, transformations);
 * см. docs/ARCHITECTURE.md §8 для формата.
 */
@Entity
@Table(name = "import_rule_versions", indexes = {
    @Index(name = "idx_import_rule_versions_shop_id", columnList = "shopId"),
    @Index(name = "idx_import_rule_versions_source_status", columnList = "supplier_source_id, status")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_import_rule_versions_source_version", columnNames = {"supplier_source_id", "version"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_source_id", nullable = false)
    private SupplierSource supplierSource;

    @Column(nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private RuleVersionStatus status = RuleVersionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private RuleVersionSource source = RuleVersionSource.MANUAL;

    /** Immutable JSON rule definition (column mapping / skip rules / defaults / transformations). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String ruleDefinition;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
