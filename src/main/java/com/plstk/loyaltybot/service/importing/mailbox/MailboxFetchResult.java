package com.plstk.loyaltybot.service.importing.mailbox;

import java.util.List;

/**
 * Holds an open, read-only IMAP session for the duration of one poll. Messages are ordered
 * ascending by UID. Callers must close this (try-with-resources) once all attachments have been
 * read; closing never mutates the mailbox (no flags set, nothing deleted/moved).
 */
public interface MailboxFetchResult extends AutoCloseable {

    /** UIDVALIDITY observed for this poll; compare against the stored cursor to detect resets. */
    long uidValidity();

    List<FetchedMessage> messages();

    @Override
    void close();
}
