package com.plstk.loyaltybot.service.importing.mailbox;

/**
 * @param uidValidity last known IMAP UIDVALIDITY for the folder, or {@code null} if this mailbox
 *                     has never been polled successfully yet.
 * @param lastSeenUid  highest UID already processed; only messages with a strictly greater UID
 *                      are fetched.
 */
public record MailboxCursorPosition(Long uidValidity, long lastSeenUid) {

    public static MailboxCursorPosition initial() {
        return new MailboxCursorPosition(null, 0L);
    }
}
