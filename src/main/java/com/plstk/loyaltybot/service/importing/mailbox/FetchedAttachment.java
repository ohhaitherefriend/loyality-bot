package com.plstk.loyaltybot.service.importing.mailbox;

import java.io.IOException;
import java.io.InputStream;

/**
 * One attachment part of a {@link FetchedMessage}. The stream is only valid while the owning
 * {@link MailboxFetchResult} is still open (the underlying IMAP folder/store is what actually
 * holds the bytes), exactly like plain {@code jakarta.mail} semantics.
 */
public interface FetchedAttachment {

    String filename();

    String contentType();

    InputStream openStream() throws IOException;
}
