package com.plstk.loyaltybot.service.importing.mailbox;

/**
 * @param message human-readable outcome. Must never contain the password/secret; implementations
 *                are responsible for redacting provider exception text before putting it here.
 */
public record MailboxConnectionTestResult(boolean success, String message) {

    public static MailboxConnectionTestResult ok(String message) {
        return new MailboxConnectionTestResult(true, message);
    }

    public static MailboxConnectionTestResult failure(String message) {
        return new MailboxConnectionTestResult(false, message);
    }
}
