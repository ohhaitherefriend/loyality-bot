package com.plstk.loyaltybot.service.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 10 (docs/DECISIONS.md ADR-014): {@link S3ImportFileStorage} must behave exactly like
 * {@link LocalImportFileStorage} from the calling code's point of view - content-addressed,
 * idempotent {@code store}, no-op {@code delete} of an already-missing key.
 */
@ExtendWith(MockitoExtension.class)
class S3ImportFileStorageTest {

    private static final String BUCKET = "loyalty-import-files";

    @Mock
    private S3Client s3Client;

    private S3ImportFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3ImportFileStorage(s3Client, BUCKET, "");
    }

    @Test
    void store_newObject_putsItAndReturnsContentAddressedKey(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("price.xlsx");
        Files.writeString(source, "fake-xlsx-bytes");
        String sha256 = "a".repeat(64);

        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());
        when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = storage.store("shop-1", sha256, "price.xlsx", source);

        assertEquals("shop-1/" + sha256.substring(0, 2) + "/" + sha256 + ".xlsx", key);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void store_existingObject_skipsPutButReturnsSameKey(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("price.xlsx");
        Files.writeString(source, "fake-xlsx-bytes");
        String sha256 = "b".repeat(64);

        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        String key = storage.store("shop-1", sha256, "price.xlsx", source);

        assertEquals("shop-1/" + sha256.substring(0, 2) + "/" + sha256 + ".xlsx", key);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void exists_headObjectSucceeds_returnsTrue() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertTrue(storage.exists("shop-1/aa/aaaa.xlsx"));
    }

    @Test
    void exists_noSuchKey_returnsFalse() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());
        assertFalse(storage.exists("shop-1/aa/aaaa.xlsx"));
    }

    @Test
    void exists_generic404_returnsFalse() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(404).message("not found").build());
        assertFalse(storage.exists("shop-1/aa/aaaa.xlsx"));
    }

    @Test
    void open_delegatesToGetObject() throws IOException {
        InputStream content = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(content));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        try (InputStream result = storage.open("shop-1/aa/aaaa.xlsx")) {
            assertEquals("hello", new String(result.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void delete_delegatesToDeleteObject() throws IOException {
        storage.delete("shop-1/aa/aaaa.xlsx");
        verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void buildKey_prependsConfiguredPrefix() throws Exception {
        S3ImportFileStorage prefixedStorage = new S3ImportFileStorage(s3Client, BUCKET, "env/staging");
        Path tempFile = Files.createTempFile("import", ".xlsx");
        try {
            String sha256 = "c".repeat(64);
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("not found").build());
            when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            String key = prefixedStorage.store("shop-1", sha256, "price.xlsx", tempFile);

            assertEquals("env/staging/shop-1/" + sha256.substring(0, 2) + "/" + sha256 + ".xlsx", key);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
