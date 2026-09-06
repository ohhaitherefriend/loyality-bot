package com.plstk.loyaltybot.service.importing;

/**
 * Метаданные вложения, известные до чтения потока. {@code sourceIdentity} для email
 * (Prompt 02) — JSON с mailbox+UIDVALIDITY+UID+attachment index; для manual upload
 * (Prompt 08) — идентификатор запроса. Ingestion service не интерпретирует это поле.
 */
public record AttachmentMetadata(
        String originalFilename,
        String mediaType,
        String sourceIdentity
) {
}
