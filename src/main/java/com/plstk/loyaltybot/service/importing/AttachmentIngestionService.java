package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Единый ingestion seam для всех источников вложений (email в Prompt 02, manual upload в
 * Prompt 08). Принимает stream + metadata, считает SHA-256, сохраняет immutable file и
 * идемпотентно создаёт ровно один {@link ImportBatch} в статусе {@code STORED}.
 *
 * Намеренно НЕ содержит HTTP endpoint, mailbox-клиент или парсинг — только сам seam.
 *
 * Идемпотентность: повторный вызов с байтово идентичным содержимым для того же
 * {@code (shopId, supplierSourceId)} не создаёт новых записей и возвращает существующий
 * результат с {@code alreadyExisted=true}. Ошибка storage не должна оставлять частично
 * созданный {@code STORED} batch: файл сохраняется до начала транзакции БД, а сама
 * транзакция создаёт ImportFile+ImportBatch атомарно вместе или ни одной из записей.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentIngestionService {

    private final SupplierSourceRepository supplierSourceRepository;
    private final ImportFileStorage importFileStorage;
    private final SupplierImportProperties properties;
    private final ImportFileBatchWriter importFileBatchWriter;

    public IngestionResult ingest(
            String shopId,
            Long supplierSourceId,
            AttachmentMetadata metadata,
            InputStream content) throws IOException {

        SupplierSource supplierSource = supplierSourceRepository.findByShopIdAndId(shopId, supplierSourceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "SupplierSource " + supplierSourceId + " not found for shop " + shopId));

        HashedTempFile hashed = writeToTempFileWithHash(content);
        try {
            // Storage write happens outside the DB transaction: a storage failure must never
            // leave a STORED batch behind, and a successful immutable write is safe to retry
            // (store() is itself idempotent on the content-derived key).
            String storageKey = importFileStorage.store(
                    shopId, hashed.sha256Hex(), metadata.originalFilename(), hashed.path());
            return importFileBatchWriter.getOrCreate(shopId, supplierSource, metadata, hashed, storageKey);
        } finally {
            Files.deleteIfExists(hashed.path());
        }
    }

    private HashedTempFile writeToTempFileWithHash(InputStream content) throws IOException {
        Path tempFile = Files.createTempFile("import-attachment-", ".tmp");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }

        long maxBytes = properties.getStorage().getMaxFileSizeBytes();
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        try (var out = Files.newOutputStream(tempFile)) {
            int read;
            while ((read = content.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maxBytes) {
                    Files.deleteIfExists(tempFile);
                    throw new AttachmentTooLargeException(
                            "Attachment exceeds max size of " + maxBytes + " bytes");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (IOException | AttachmentTooLargeException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        String sha256Hex = bytesToHex(digest.digest());
        return new HashedTempFile(tempFile, sha256Hex, totalBytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class AttachmentTooLargeException extends RuntimeException {
        public AttachmentTooLargeException(String message) {
            super(message);
        }
    }
}
