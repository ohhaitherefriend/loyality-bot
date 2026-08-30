package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.config.CommerceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageStorageService {

    private final CommerceProperties commerceProperties;

    @Value("${server.base-url:}")
    private String serverBaseUrl;

    public Path productDirectory(String shopId, Long productId) throws IOException {
        Path dir = Path.of(commerceProperties.getImageStorageBasePath(), shopId, String.valueOf(productId));
        Files.createDirectories(dir);
        return dir;
    }

    public StoredImage saveOriginal(String shopId, Long productId, Long imageId, InputStream inputStream, String extension)
            throws IOException {
        String safeExtension = normalizeExtension(extension);
        String filename = "original-" + imageId + "." + safeExtension;
        Path target = productDirectory(shopId, productId).resolve(filename);
        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        String publicPath = buildPublicPath(shopId, productId, filename);
        log.debug("Saved original image to {}", target);
        return new StoredImage(target, publicPath);
    }

    public StoredImage saveNormalized(String shopId, Long productId, Long imageId, InputStream inputStream)
            throws IOException {
        String filename = "normalized-" + imageId + ".jpg";
        Path target = productDirectory(shopId, productId).resolve(filename);
        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        String publicPath = buildPublicPath(shopId, productId, filename);
        log.debug("Saved normalized image to {}", target);
        return new StoredImage(target, publicPath);
    }

    public Path resolveLocalPath(String shopId, Long productId, String filename) {
        return Path.of(commerceProperties.getImageStorageBasePath(), shopId, String.valueOf(productId), filename);
    }

    public String buildPublicPath(String shopId, Long productId, String filename) {
        String prefix = commerceProperties.getImagePublicUrlPrefix();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/" + shopId + "/" + productId + "/" + filename;
    }

    public String toAbsoluteUrl(String publicPath) {
        if (!StringUtils.hasText(publicPath)) {
            return publicPath;
        }
        if (publicPath.startsWith("http://") || publicPath.startsWith("https://")) {
            return publicPath;
        }
        if (!StringUtils.hasText(serverBaseUrl)) {
            return publicPath;
        }
        String base = serverBaseUrl.endsWith("/") ? serverBaseUrl.substring(0, serverBaseUrl.length() - 1) : serverBaseUrl;
        return base + (publicPath.startsWith("/") ? publicPath : "/" + publicPath);
    }

    public Optional<byte[]> readBytesFromPublicUrl(String publicOrAbsoluteUrl) {
        return resolveLocalPathFromPublicUrl(publicOrAbsoluteUrl)
                .flatMap(path -> {
                    try {
                        if (!Files.exists(path)) {
                            return Optional.empty();
                        }
                        return Optional.of(Files.readAllBytes(path));
                    } catch (IOException e) {
                        log.warn("Failed to read image file {}: {}", path, e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    public Optional<Path> resolveLocalPathFromPublicUrl(String publicOrAbsoluteUrl) {
        if (!StringUtils.hasText(publicOrAbsoluteUrl)) {
            return Optional.empty();
        }

        String path = publicOrAbsoluteUrl;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int filesIdx = path.indexOf("/files/");
            if (filesIdx < 0) {
                return Optional.empty();
            }
            path = path.substring(filesIdx);
        }

        String prefix = commerceProperties.getImagePublicUrlPrefix();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        if (!path.startsWith(prefix + "/")) {
            return Optional.empty();
        }

        String relative = path.substring(prefix.length() + 1);
        String[] parts = relative.split("/");
        if (parts.length != 3) {
            return Optional.empty();
        }

        return Optional.of(resolveLocalPath(parts[0], Long.parseLong(parts[1]), parts[2]));
    }

    private String normalizeExtension(String extension) {
        if (!StringUtils.hasText(extension)) {
            return "jpg";
        }
        String ext = extension.toLowerCase().replace(".", "");
        return switch (ext) {
            case "jpeg", "jpg", "png", "webp", "gif" -> ext.equals("jpeg") ? "jpg" : ext;
            default -> "jpg";
        };
    }

    public record StoredImage(Path localPath, String publicPath) {}
}
