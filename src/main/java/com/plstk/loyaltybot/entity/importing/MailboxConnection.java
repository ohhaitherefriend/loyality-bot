package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Конфигурация почтового ящика для email-first ingestion. IMAP-клиент и polling job
 * добавляются в Prompt 02; здесь только shop-scoped хранение подключения и секрета.
 * Секрет хранится только в зашифрованном виде ({@code TokenEncryptionService} или
 * эквивалентный AES-GCM primitive) и никогда не логируется/не возвращается целиком в DTO.
 */
@Entity
@Table(name = "mailbox_connections", indexes = {
    @Index(name = "idx_mailbox_connections_shop_id", columnList = "shopId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_mailbox_connections_shop_label", columnNames = {"shopId", "label"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailboxConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String shopId;

    @Column(nullable = false, length = 255)
    private String label;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(nullable = false, length = 255)
    private String username;

    /** AES-GCM encrypted secret (app password or OAuth2 refresh token). Never plaintext. */
    @Column(columnDefinition = "TEXT")
    private String encryptedSecret;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private MailAuthMode authMode;

    /**
     * IMAP folder polled for this mailbox. One {@link MailboxCursor} tracks UIDVALIDITY/UID for
     * exactly this folder, so unlike sender/subject/filename filters (which live on
     * {@link SupplierSource} because several sources can share one mailbox), the folder is a
     * property of the physical mailbox connection itself.
     */
    @Column(nullable = false, length = 255)
    @Builder.Default
    private String folder = "INBOX";

    @Column(nullable = false)
    @Builder.Default
    private Boolean useTls = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    private LocalDateTime lastPollAt;

    private LocalDateTime lastPollSuccessAt;

    @Column(length = 1024)
    private String lastPollError;

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
