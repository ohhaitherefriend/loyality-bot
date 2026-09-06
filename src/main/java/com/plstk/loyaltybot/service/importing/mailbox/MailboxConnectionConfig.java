package com.plstk.loyaltybot.service.importing.mailbox;

/**
 * Decrypted connection parameters for one {@code MailboxClient} operation. Never persisted,
 * logged or returned from any endpoint; callers must build this just-in-time from the encrypted
 * secret and discard it immediately after use.
 */
public record MailboxConnectionConfig(
        String host,
        int port,
        String username,
        String password,
        boolean useTls,
        String folder
) {
}
