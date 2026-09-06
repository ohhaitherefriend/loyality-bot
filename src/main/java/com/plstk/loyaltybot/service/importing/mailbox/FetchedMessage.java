package com.plstk.loyaltybot.service.importing.mailbox;

import java.util.List;

/**
 * @param uid         IMAP UID of this message within the polled folder.
 * @param fromAddress raw sender address (e.g. {@code price@supplier.ru}), lower-cased.
 */
public record FetchedMessage(
        long uid,
        String fromAddress,
        String subject,
        List<FetchedAttachment> attachments
) {
}
