package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.*;
import com.plstk.loyaltybot.repository.ProductImageRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductImageSearchService {

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductImageSearchProvider searchProvider;
    private final ImageStorageService imageStorageService;
    private final RestTemplate restTemplate;

    public List<ImageCandidate> searchCandidates(String shopId, Long productId) {
        Product product = requireProduct(shopId, productId);
        return searchProvider.search(product);
    }

    @Transactional
    public ProductImage downloadCandidate(String shopId, Long productId, DownloadCandidateRequest request) {
        Product product = requireProduct(shopId, productId);
        if (!StringUtils.hasText(request.imageUrl())) {
            throw new IllegalArgumentException("imageUrl is required");
        }

        byte[] bytes = downloadBytes(request.imageUrl());
        String extension = guessExtension(request.imageUrl(), bytes);

        ProductImage image = ProductImage.builder()
                .shopId(shopId)
                .product(product)
                .imageType(ImageType.CANDIDATE)
                .status(ImageStatus.NEEDS_REVIEW)
                .sourceType(request.sourceType() != null ? request.sourceType() : ImageSourceType.MARKETPLACE_CANDIDATE)
                .sourceUrl(request.imageUrl())
                .sourcePageUrl(request.pageUrl())
                .sourceDomain(request.sourceDomain())
                .confidence(request.confidence())
                .matchedBy(request.matchedBy())
                .approvedByAdmin(false)
                .aiNormalized(false)
                .build();
        image = productImageRepository.save(image);

        try {
            ImageStorageService.StoredImage stored = imageStorageService.saveOriginal(
                    shopId, productId, image.getId(), new ByteArrayInputStream(bytes), extension);
            image.setOriginalUrl(stored.publicPath());
            image.setStatus(ImageStatus.NEEDS_REVIEW);
            updateProductImageStatus(product, ImageStatus.NEEDS_REVIEW);
            return productImageRepository.save(image);
        } catch (IOException e) {
            image.setStatus(ImageStatus.FAILED);
            productImageRepository.save(image);
            throw new IllegalStateException("Failed to save downloaded image: " + e.getMessage(), e);
        }
    }

    @Transactional
    public ProductImage uploadImage(String shopId, Long productId, MultipartFile file) {
        Product product = requireProduct(shopId, productId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }

        ProductImage image = ProductImage.builder()
                .shopId(shopId)
                .product(product)
                .imageType(ImageType.CANDIDATE)
                .status(ImageStatus.DOWNLOADED)
                .sourceType(ImageSourceType.MANUAL_UPLOAD)
                .sourceUrl(file.getOriginalFilename())
                .approvedByAdmin(false)
                .aiNormalized(false)
                .build();
        image = productImageRepository.save(image);

        try {
            String extension = extensionFromFilename(file.getOriginalFilename());
            ImageStorageService.StoredImage stored = imageStorageService.saveOriginal(
                    shopId, productId, image.getId(), file.getInputStream(), extension);
            image.setOriginalUrl(stored.publicPath());
            image.setStatus(ImageStatus.NEEDS_REVIEW);
            updateProductImageStatus(product, ImageStatus.NEEDS_REVIEW);
            return productImageRepository.save(image);
        } catch (IOException e) {
            image.setStatus(ImageStatus.FAILED);
            productImageRepository.save(image);
            throw new IllegalStateException("Failed to save uploaded image: " + e.getMessage(), e);
        }
    }

    @Transactional
    public ProductImage importFromUrl(String shopId, Long productId, String url) {
        return downloadCandidate(shopId, productId, new DownloadCandidateRequest(
                url, url, extractDomain(url), ImageSourceType.MANUAL_URL, null, null));
    }

    public List<ProductImage> listImages(String shopId, Long productId) {
        requireProduct(shopId, productId);
        return productImageRepository.findByShopIdAndProductIdOrderByCreatedAtDesc(shopId, productId);
    }

    public Map<Long, String> getPreviewImageUrls(String shopId, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<ProductImage> images = productImageRepository
                .findByShopIdAndProduct_IdInAndNormalizedUrlIsNotNullOrderByCreatedAtDesc(shopId, productIds);
        Map<Long, String> previews = new HashMap<>();
        for (ProductImage image : images) {
            Long productId = image.getProduct().getId();
            if (!previews.containsKey(productId)) {
                previews.put(productId, imageStorageService.toAbsoluteUrl(image.getNormalizedUrl()));
            }
        }
        return previews;
    }

    public String getPreviewImageUrl(String shopId, Long productId) {
        return getPreviewImageUrls(shopId, List.of(productId)).get(productId);
    }

    private byte[] downloadBytes(String url) {
        try {
            ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().length == 0) {
                throw new IllegalArgumentException("Failed to download image from URL");
            }
            return response.getBody();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to download image: " + e.getMessage(), e);
        }
    }

    private String guessExtension(String url, byte[] bytes) {
        String fromUrl = extensionFromFilename(url);
        if (StringUtils.hasText(fromUrl)) {
            return fromUrl;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "jpg";
        }
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50) {
            return "png";
        }
        return "jpg";
    }

    private String extensionFromFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "jpg";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "jpg";
        }
        return filename.substring(dot + 1);
    }

    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private Product requireProduct(String shopId, Long productId) {
        return productRepository.findByShopIdAndId(shopId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
    }

    private void updateProductImageStatus(Product product, ImageStatus status) {
        if (product.getImageStatus() == ImageStatus.APPROVED) {
            return;
        }
        product.setImageStatus(status);
        product.setImageUpdatedAt(java.time.LocalDateTime.now());
        productRepository.save(product);
    }

    public record DownloadCandidateRequest(
            String imageUrl,
            String pageUrl,
            String sourceDomain,
            ImageSourceType sourceType,
            java.math.BigDecimal confidence,
            String matchedBy
    ) {}
}
