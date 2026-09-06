package com.plstk.loyaltybot.service.importing.mailbox;

/**
 * Thrown by {@link MailboxClient} on connection/protocol failures. Message text must never
 * contain the mailbox password/secret.
 */
public class MailboxClientException extends Exception {

    public MailboxClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public MailboxClientException(String message) {
        super(message);
    }
}
