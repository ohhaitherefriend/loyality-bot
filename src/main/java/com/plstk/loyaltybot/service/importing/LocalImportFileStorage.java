package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local filesystem реализация {@link ImportFileStorage} для dev/test и как fallback до
 * подключения S3-compatible storage в production. Запись атомарна (temp file + move в тот же
 * каталог). На POSIX {@code ATOMIC_MOVE} может молча перезаписать уже существующий объект
 * (не выбрасывает {@code FileAlreadyExistsException} - это гарантирует {@code rename(2)}, а не
 * JDK); это безопасно только потому, что {@code storageKey} content-addressed (см. {@link #store}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalImportFileStorage implements ImportFileStorage {

    private final SupplierImportProperties properties;

    @Override
    public String store(String shopId, String sha256, String originalFilename, Path source) throws IOException {
        String key = buildKey(shopId, sha256, originalFilename);
        Path target = resolve(key);
        if (Files.exists(target)) {
            log.debug("Import file already stored at {}, skipping write (immutable)", key);
            return key;
        }

        Files.createDirectories(target.getParent());
        Path tempInPlace = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        Files.copy(source, tempInPlace, StandardCopyOption.REPLACE_EXISTING);
        try {
            // ATOMIC_MOVE is atomic within the same directory/filesystem, but on POSIX it's
            // implemented via rename(2), which silently OVERWRITES an existing target rather than
            // failing - the FileAlreadyExistsException catch below is effectively unreachable on
            // Linux/macOS (it could only fire on a filesystem/JDK combination that maps ATOMIC_MOVE
            // to a create-exclusive-style primitive instead, e.g. some non-POSIX setups). Safety
            // here does NOT come from that exception path: it comes from `key` being
            // content-addressed (buildKey embeds sha256), so a concurrent "overwrite" of the same
            // key is always byte-for-byte identical content, hence harmless either way.
            Files.move(tempInPlace, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            Files.deleteIfExists(tempInPlace);
        } finally {
            Files.deleteIfExists(tempInPlace);
        }
        return key;
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        return Files.newInputStream(resolve(storageKey));
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(resolve(storageKey));
    }

    private Path resolve(String storageKey) {
        return Path.of(properties.getStorage().getBasePath()).resolve(storageKey).normalize();
    }

    private String buildKey(String shopId, String sha256, String originalFilename) {
        String extension = extractExtension(originalFilename);
        String safeShopId = shopId.replaceAll("[^a-zA-Z0-9-]", "_");
        String prefix = sha256.substring(0, 2);
        return safeShopId + "/" + prefix + "/" + sha256 + extension;
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
