package com.plstk.loyaltybot.service.importing.mailbox;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeUtility;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks a MIME message tree and collects every part that carries a filename (attachment or
 * inline-with-filename), regardless of nesting depth. Only ever reads structure/metadata eagerly;
 * the actual bytes are read lazily via {@link FetchedAttachment#openStream()}.
 */
final class MimeAttachmentExtractor {

    private MimeAttachmentExtractor() {
    }

    static List<FetchedAttachment> extract(Part rootPart) throws MessagingException, IOException {
        List<FetchedAttachment> result = new ArrayList<>();
        collect(rootPart, result);
        return result;
    }

    private static void collect(Part part, List<FetchedAttachment> result) throws MessagingException, IOException {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            int count = multipart.getCount();
            for (int i = 0; i < count; i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, result);
            }
            return;
        }

        String filename = decodeFilename(part);
        if (filename != null && !filename.isBlank()) {
            result.add(new PartAttachment(filename, safeContentType(part), part));
        }
    }

    private static String decodeFilename(Part part) throws MessagingException {
        String rawFilename = part.getFileName();
        if (rawFilename == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(rawFilename);
        } catch (Exception e) {
            return rawFilename;
        }
    }

    private static String safeContentType(Part part) {
        try {
            String contentType = part.getContentType();
            return contentType == null ? "application/octet-stream" : contentType;
        } catch (MessagingException e) {
            return "application/octet-stream";
        }
    }

    private record PartAttachment(String filename, String contentType, Part part) implements FetchedAttachment {

        @Override
        public InputStream openStream() throws IOException {
            try {
                return part.getInputStream();
            } catch (MessagingException e) {
                throw new IOException("Failed to open attachment stream for " + filename, e);
            }
        }
    }
}
