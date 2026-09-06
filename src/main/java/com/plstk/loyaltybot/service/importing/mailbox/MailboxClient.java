package com.plstk.loyaltybot.service.importing.mailbox;

/**
 * Provider-neutral seam for reading a supplier price-list mailbox. {@link ImapMailboxClient} is
 * the first (and, for MVP, only) real implementation — app-password IMAP/IMAPS. OAuth2 mailboxes
 * are modeled in {@code MailAuthMode} but have no implementation yet; a future implementation can
 * be added behind this same interface without touching {@code MailboxPollingService}.
 *
 * Access is read-only: implementations must never mark messages read, delete or move them.
 */
public interface MailboxClient {

    MailboxConnectionTestResult testConnection(MailboxConnectionConfig config);

    /**
     * Fetches messages with UID greater than {@code cursorPosition.lastSeenUid()}, up to
     * {@code maxMessages} of them (oldest UID first) - a mailbox with a large backlog (extended
     * downtime, or re-enabled after being disabled) must never force one poll cycle to hold and
     * synchronously MIME-parse an unbounded number of messages in memory under a single claim/lease.
     * The caller is responsible for advancing its cursor only up to the last message actually
     * returned here; any remaining backlog is picked up by the next poll cycle, never lost. If the
     * server's current UIDVALIDITY differs from {@code cursorPosition.uidValidity()} (or the
     * cursor is {@link MailboxCursorPosition#initial()}), the caller is responsible for treating
     * this as a fresh start (UIDs are only comparable within the same UIDVALIDITY generation);
     * this method itself just reports the current UIDVALIDITY via {@link MailboxFetchResult}.
     */
    MailboxFetchResult fetchNewMessages(
            MailboxConnectionConfig config, MailboxCursorPosition cursorPosition, int maxMessages)
            throws MailboxClientException;
}
