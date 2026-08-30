package com.plstk.loyaltybot.service.commerce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.CommerceProperties;
import com.plstk.loyaltybot.entity.commerce.ImageStatus;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.commerce.ProductImage;
import com.plstk.loyaltybot.repository.ProductImageRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductImageVisualNormalizationService {

    private final CommerceProperties commerceProperties;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ImageStorageService imageStorageService;
    private final BackgroundRemovalProvider backgroundRemovalProvider;
    private final ObjectMapper objectMapper;

    @Transactional
    public ProductImage normalizeProductImage(String shopId, Long productId, Long imageId) {
        Product product = requireProduct(shopId, productId);
        ProductImage image = requireImage(shopId, productId, imageId);

        if (!StringUtils.hasText(image.getOriginalUrl())) {
            throw new IllegalArgumentException("Image has no original file");
        }

        Path originalPath = resolveOriginalPath(shopId, productId, image);
        if (!Files.exists(originalPath)) {
            throw new IllegalArgumentException("Original image file not found");
        }

        try {
            byte[] originalBytes = Files.readAllBytes(originalPath);
            NormalizationOutcome outcome = normalizeBytes(product, originalBytes, originalPath.getFileName().toString(), "image/jpeg");
            ImageStorageService.StoredImage stored = imageStorageService.saveNormalized(
                    shopId, productId, imageId, new ByteArrayInputStream(outcome.normalizedBytes()));

            image.setNormalizedUrl(stored.publicPath());
            image.setStatus(ImageStatus.NEEDS_REVIEW);
            image.setNormalizationProvider(outcome.provider());
            image.setBackgroundRemoved(outcome.backgroundRemoved());
            image.setScaleNormalized(true);
            image.setAngleNormalized(false);
            image.setAiNormalized(false);

            if (product.getImageStatus() != ImageStatus.APPROVED) {
                product.setImageStatus(ImageStatus.NEEDS_REVIEW);
                product.setImageUpdatedAt(java.time.LocalDateTime.now());
                productRepository.save(product);
            }

            productImageRepository.save(image);
            return image;
        } catch (IOException e) {
            image.setStatus(ImageStatus.FAILED);
            productImageRepository.save(image);
            throw new IllegalStateException("Failed to normalize image: " + e.getMessage(), e);
        }
    }

    public NormalizationOutcome normalizeBytes(Product product, byte[] originalBytes, String filename, String contentType)
            throws IOException {
        int canvasSize = commerceProperties.getImageNormalization().getOutputSize();
        CategoryScaleRules rules = CategoryScaleRules.forProduct(product);

        BackgroundRemovalResult removal = backgroundRemovalProvider.removeBackground(originalBytes, filename, contentType);
        BufferedImage working;
        String provider;
        boolean backgroundRemoved = false;

        if (removal.isSuccess() && removal.getTransparentPng() != null && removal.getTransparentPng().length > 0) {
            working = ImageIO.read(new ByteArrayInputStream(removal.getTransparentPng()));
            provider = removal.getProvider();
            backgroundRemoved = true;
        } else {
            working = ImageIO.read(new ByteArrayInputStream(originalBytes));
            provider = "fallback";
            if (backgroundRemovalProvider.isConfigured() && !"disabled".equals(backgroundRemovalProvider.providerName())) {
                log.warn("Background removal failed for product {}, using fallback normalization", product.getId());
            }
        }

        if (working == null) {
            throw new IOException("Unsupported or unreadable image format");
        }

        Rectangle bounds = ImageAlphaUtils.findNonTransparentBoundingBox(working);
        BufferedImage cropped = ImageAlphaUtils.crop(working, bounds);
        BufferedImage scaled = scaleToCanvas(cropped, canvasSize, rules, working.getTransparency() != Transparency.OPAQUE);
        BufferedImage canvas = placeOnWhiteCanvas(scaled, canvasSize, working.getTransparency() != Transparency.OPAQUE);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(canvas, "jpg", outputStream);

        return NormalizationOutcome.builder()
                .normalizedBytes(outputStream.toByteArray())
                .provider(provider)
                .backgroundRemoved(backgroundRemoved)
                .build();
    }

    public void applyQualityMetadata(ProductImage image, QualityAssessment assessment, CandidateRankResult rankResult) {
        try {
            image.setVisualQualityScore(assessment.getVisualQualityScore());
            image.setQualityDecision(assessment.getDecision());
            image.setQualityWarnings(objectMapper.writeValueAsString(assessment.getWarnings()));
            if (rankResult != null) {
                image.setRankerReason(rankResult.reason());
                image.setRankerWarnings(objectMapper.writeValueAsString(rankResult.warnings()));
            }
            if ("MANUAL_REVIEW_REQUIRED".equals(assessment.getDecision())
                    || "REJECT_AND_TRY_NEXT".equals(assessment.getDecision())) {
                image.setManualReviewReason(assessment.getDecision());
            }
        } catch (Exception e) {
            log.warn("Failed to serialize quality metadata for image {}", image.getId());
        }
    }

    public List<String> readWarnings(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private BufferedImage scaleToCanvas(BufferedImage source, int canvasSize, CategoryScaleRules rules, boolean hasAlpha) {
        int maxWidth = (int) Math.round(canvasSize * rules.getMaxWidthRatio());
        int maxHeight = (int) Math.round(canvasSize * rules.getMaxHeightRatio());
        double scale = Math.min((double) maxWidth / source.getWidth(), (double) maxHeight / source.getHeight());
        if (scale > 1.0) {
            scale = 1.0;
        }
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));

        int type = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!hasAlpha) {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
        }
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return scaled;
    }

    private BufferedImage placeOnWhiteCanvas(BufferedImage image, int canvasSize, boolean hasAlpha) {
        BufferedImage canvas = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, canvasSize, canvasSize);

        int x = (canvasSize - image.getWidth()) / 2;
        int y = (canvasSize - image.getHeight()) / 2;

        if (hasAlpha) {
            drawShadow(graphics, image, x, y);
        }
        graphics.drawImage(image, x, y, null);
        graphics.dispose();
        return canvas;
    }

    private void drawShadow(Graphics2D graphics, BufferedImage image, int x, int y) {
        graphics.setColor(new Color(0, 0, 0, 30));
        graphics.fillOval(x + 20, y + image.getHeight() - 10, Math.max(20, image.getWidth() - 40), 18);
    }

    private Path resolveOriginalPath(String shopId, Long productId, ProductImage image) {
        String originalUrl = image.getOriginalUrl();
        String filename = originalUrl.substring(originalUrl.lastIndexOf('/') + 1);
        return imageStorageService.resolveLocalPath(shopId, productId, filename);
    }

    private Product requireProduct(String shopId, Long productId) {
        return productRepository.findByShopIdAndId(shopId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
    }

    private ProductImage requireImage(String shopId, Long productId, Long imageId) {
        return productImageRepository.findByShopIdAndProductIdAndId(shopId, productId, imageId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found"));
    }

    @Builder
    public record NormalizationOutcome(
            byte[] normalizedBytes,
            String provider,
            boolean backgroundRemoved
    ) {}
}
