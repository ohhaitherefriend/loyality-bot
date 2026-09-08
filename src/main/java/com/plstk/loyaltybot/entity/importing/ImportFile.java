package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Immutable metadata исходного вложения. Байты хранятся отдельно через
 * {@code ImportFileStorage} по {@link #storageKey}; сама запись и файл никогда не
 * изменяются после создания. Дедупликация — по {@code (shopId, supplierSource, sha256)}.
 */
@Entity
@Table(name = "import_files", indexes = {
    @Index(name = "idx_import_files_shop_id", columnList = "shopId"),
    @Index(name = "idx_import_files_shop_source", columnList = "shopId, supplier_source_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_import_files_shop_source_sha256", columnNames = {"shopId", "supplier_source_id", "sha256"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_source_id", nullable = false)
    private SupplierSource supplierSource;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private Long sizeBytes;

    @Column(nullable = false, length = 255)
    private String mediaType;

    @Column(nullable = false, length = 512)
    private String originalFilename;

    /** Provider-neutral immutable storage key/path returned by ImportFileStorage. */
    @Column(nullable = false, length = 1024)
    private String storageKey;

    /**
     * Идентичность источника вложения: для email — mailbox+UIDVALIDITY+UID+attachment index
     * (JSON), для manual upload (Prompt 08) — идентификатор запроса/пользователя.
     */
    @Column(columnDefinition = "TEXT")
    private String sourceIdentity;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    /**
     * Set by {@code ImportRetentionJob} (Stage 10, docs/DECISIONS.md ADR-015) once the underlying
     * {@code ImportFileStorage} blob has been deleted for a terminal batch past the configured
     * retention window. This metadata row is never deleted - only the blob - so null here just
     * means the blob still exists (or retention is disabled/hasn't reached this file yet).
     */
    private LocalDateTime storageDeletedAt;

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
    }
}
