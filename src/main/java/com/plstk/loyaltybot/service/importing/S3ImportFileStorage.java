package com.plstk.loyaltybot.service.importing;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * S3-compatible реализация {@link ImportFileStorage} (Stage 10, docs/DECISIONS.md ADR-014).
 * Required for any multi-replica production deployment: {@link LocalImportFileStorage}'s local
 * disk path is invisible across replicas, so a file an email-polling replica stores could never be
 * opened by the replica that later runs the parsing job for the same batch.
 *
 * <p>Content-addressed keys ({@code buildKey}) make {@code store} naturally idempotent, same as
 * the local implementation - a concurrent "overwrite" of the same key is always byte-for-byte
 * identical content, so S3's own object versioning/overwrite semantics are irrelevant here.</p>
 */
@Slf4j
public class S3ImportFileStorage implements ImportFileStorage {

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;

    public S3ImportFileStorage(S3Client s3Client, String bucket, String keyPrefix) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public String store(String shopId, String sha256, String originalFilename, Path source) throws IOException {
        String key = buildKey(shopId, sha256, originalFilename);
        if (objectExists(key)) {
            log.debug("Import file already stored at s3://{}/{}, skipping write (immutable)", bucket, key);
            return key;
        }
        long sizeBytes = Files.size(source);
        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromFile(source));
        } catch (SdkException e) {
            throw new IOException("Failed to write S3 object " + key, e);
        }
        log.info("Stored import file at s3://{}/{} ({} bytes)", bucket, key, sizeBytes);
        return key;
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (SdkException e) {
            throw new IOException("Failed to read S3 object " + storageKey, e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return objectExists(storageKey);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (SdkException e) {
            throw new IOException("Failed to delete S3 object " + storageKey, e);
        }
    }

    private boolean objectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private String buildKey(String shopId, String sha256, String originalFilename) {
        String extension = extractExtension(originalFilename);
        String safeShopId = shopId.replaceAll("[^a-zA-Z0-9-]", "_");
        String prefix = sha256.substring(0, 2);
        String base = safeShopId + "/" + prefix + "/" + sha256 + extension;
        return keyPrefix.isBlank() ? base : (keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/") + base;
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        String ext = originalFilename.substring(dot).toLowerCase();
        if (!ext.matches("\\.[a-z0-9]{1,10}")) {
            return "";
        }
        return ext;
    }
}
