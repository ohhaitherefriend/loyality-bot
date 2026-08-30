package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.ImageStatus;
import com.plstk.loyaltybot.entity.commerce.ImageType;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.commerce.ProductImage;
import com.plstk.loyaltybot.repository.ProductImageRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductImageApprovalService {

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ImageStorageService imageStorageService;

    @Transactional
    public ProductImage approve(String shopId, Long productId, Long imageId) {
        Product product = requireProduct(shopId, productId);
        ProductImage image = requireImage(shopId, productId, imageId);

        if (!StringUtils.hasText(image.getNormalizedUrl()) && !StringUtils.hasText(image.getOriginalUrl())) {
            throw new IllegalArgumentException("Image has no file to approve");
        }

        demoteExistingMainImages(shopId, productId, imageId);

        image.setStatus(ImageStatus.APPROVED);
        image.setImageType(ImageType.MAIN);
        image.setApprovedByAdmin(true);
        image.setRejectReason(null);
        productImageRepository.save(image);

        String mainUrl = StringUtils.hasText(image.getNormalizedUrl())
                ? image.getNormalizedUrl()
                : image.getOriginalUrl();

        product.setMainImageUrl(imageStorageService.toAbsoluteUrl(mainUrl));
        product.setImageStatus(ImageStatus.APPROVED);
        product.setImageUpdatedAt(LocalDateTime.now());
        productRepository.save(product);

        return image;
    }

    @Transactional
    public ProductImage reject(String shopId, Long productId, Long imageId, String reason) {
        Product product = requireProduct(shopId, productId);
        ProductImage image = requireImage(shopId, productId, imageId);

        image.setStatus(ImageStatus.REJECTED);
        image.setApprovedByAdmin(false);
        image.setRejectReason(reason);
        productImageRepository.save(image);

        if (ImageType.MAIN.equals(image.getImageType()) || mainUrlMatches(product, image)) {
            product.setMainImageUrl(null);
            product.setImageStatus(ImageStatus.REJECTED);
            product.setImageUpdatedAt(LocalDateTime.now());
            productRepository.save(product);
        } else if (product.getImageStatus() != ImageStatus.APPROVED) {
            product.setImageStatus(ImageStatus.NEEDS_REVIEW);
            product.setImageUpdatedAt(LocalDateTime.now());
            productRepository.save(product);
        }

        return image;
    }

    private void demoteExistingMainImages(String shopId, Long productId, Long keepImageId) {
        List<ProductImage> images = productImageRepository.findByShopIdAndProductIdOrderByCreatedAtDesc(shopId, productId);
        for (ProductImage existing : images) {
            if (!existing.getId().equals(keepImageId) && ImageType.MAIN.equals(existing.getImageType())) {
                existing.setImageType(ImageType.CANDIDATE);
                if (ImageStatus.APPROVED.equals(existing.getStatus())) {
                    existing.setStatus(ImageStatus.NEEDS_REVIEW);
                }
                productImageRepository.save(existing);
            }
        }
    }

    private boolean mainUrlMatches(Product product, ProductImage image) {
        if (product.getMainImageUrl() == null) {
            return false;
        }
        String normalized = image.getNormalizedUrl();
        String original = image.getOriginalUrl();
        return product.getMainImageUrl().contains(normalized != null ? normalized : "")
                || product.getMainImageUrl().contains(original != null ? original : "");
    }

    private Product requireProduct(String shopId, Long productId) {
        return productRepository.findByShopIdAndId(shopId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
    }

    private ProductImage requireImage(String shopId, Long productId, Long imageId) {
        return productImageRepository.findByShopIdAndProductIdAndId(shopId, productId, imageId)
                .orElseThrow(() -> new IllegalArgumentException("Image not found"));
    }
}
