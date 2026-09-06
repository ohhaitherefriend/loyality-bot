package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Thin manual-upload adapter over the same {@link AttachmentIngestionService} used by mailbox
 * polling. It only validates the HTTP file boundary and records channel metadata; parsing,
 * matching, validation, pricing, reconciliation and apply remain in the existing background
 * pipeline.
 */
@Service
@RequiredArgsConstructor
public class ManualImportUploadService {

    private static final byte[] XLSX_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] XLS_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };
    private static final Set<String> XLSX_MEDIA_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream",
            "application/zip");
    private static final Set<String> XLS_MEDIA_TYPES = Set.of(
            "application/vnd.ms-excel",
            "application/octet-stream",
            "application/x-ole-storage");

    private final AttachmentIngestionService attachmentIngestionService;
    private final SupplierImportProperties properties;
    private final ObjectMapper objectMapper;

    public IngestionResult upload(
            String shopId,
            Long supplierSourceId,
            MultipartFile file,
            String requestId,
            Long uploadedByUserId) throws IOException {

        String filename = validateMetadata(file);
        String extension = extensionOf(filename);

        try (InputStream raw = file.getInputStream();
             BufferedInputStream content = new BufferedInputStream(raw)) {
            validateSignature(content, extension);
            AttachmentMetadata metadata = new AttachmentMetadata(
                    filename,
                    normalizedMediaType(file.getContentType()),
                    buildSourceIdentity(requestId, uploadedByUserId));
            return attachmentIngestionService.ingest(shopId, supplierSourceId, metadata, content);
        } catch (AttachmentIngestionService.AttachmentTooLargeException e) {
            throw new ManualUploadTooLargeException(e.getMessage());
        }
    }

    private String validateMetadata(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ManualUploadValidationException("Select a non-empty Excel file");
        }

        long maxBytes = properties.getStorage().getMaxFileSizeBytes();
        if (file.getSize() > maxBytes) {
            throw new ManualUploadTooLargeException(
                    "File exceeds max size of " + maxBytes + " bytes");
        }

        String filename = safeFilename(file.getOriginalFilename());
        String extension = extensionOf(filename);
        if (!extension.equals("xlsx") && !extension.equals("xls")) {
            throw new ManualUploadValidationException("Only .xlsx and .xls files are supported");
        }

        String mediaType = normalizedMediaType(file.getContentType());
        Set<String> allowed = extension.equals("xlsx") ? XLSX_MEDIA_TYPES : XLS_MEDIA_TYPES;
        if (!allowed.contains(mediaType)) {
            throw new ManualUploadValidationException(
                    "Content type does not match an Excel " + extension + " file");
        }
        return filename;
    }

    private void validateSignature(BufferedInputStream content, String extension) throws IOException {
        byte[] expected = extension.equals("xlsx") ? XLSX_SIGNATURE : XLS_SIGNATURE;
        content.mark(expected.length);
        byte[] actual = content.readNBytes(expected.length);
        content.reset();
        if (actual.length != expected.length) {
            throw new ManualUploadValidationException("Excel file is truncated");
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                throw new ManualUploadValidationException(
                        "File signature does not match the ." + extension + " extension");
            }
        }
    }

    private String buildSourceIdentity(String requestId, Long uploadedByUserId) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("channel", "MANUAL_UPLOAD");
        identity.put("requestId", requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId.substring(0, Math.min(requestId.length(), 255)));
        if (uploadedByUserId != null) {
            identity.put("uploadedByUserId", uploadedByUserId);
        }
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize manual upload identity", e);
        }
    }

    private static String safeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ManualUploadValidationException("Excel filename is required");
        }
        String normalized = originalFilename.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (filename.isEmpty()) {
            throw new ManualUploadValidationException("Excel filename is required");
        }
        return filename;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizedMediaType(String mediaType) {
        return mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).trim();
    }

    public static class ManualUploadValidationException extends RuntimeException {
        public ManualUploadValidationException(String message) {
            super(message);
        }
    }

    public static class ManualUploadTooLargeException extends RuntimeException {
        public ManualUploadTooLargeException(String message) {
            super(message);
        }
    }
}
