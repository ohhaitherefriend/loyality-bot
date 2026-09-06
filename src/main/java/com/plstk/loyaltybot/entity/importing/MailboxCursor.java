package com.plstk.loyaltybot.entity.importing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Курсор идемпотентного чтения почтового ящика: mailbox + UIDVALIDITY + последний
 * обработанный UID. Продвигается только после надёжного результата ingestion (Prompt 02).
 */
@Entity
@Table(name = "mailbox_cursors", uniqueConstraints = {
    @UniqueConstraint(name = "uk_mailbox_cursors_mailbox", columnNames = {"mailbox_connection_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailboxCursor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mailbox_connection_id", nullable = false)
    private MailboxConnection mailboxConnection;

    private Long uidValidity;

    private Long lastSeenUid;

    private LocalDateTime lastAdvancedAt;

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
