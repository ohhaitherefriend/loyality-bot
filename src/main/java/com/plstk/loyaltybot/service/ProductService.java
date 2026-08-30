package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.commerce.AvailabilityMode;
import com.plstk.loyaltybot.entity.commerce.ImageStatus;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    public Page<Product> listProducts(
            String shopId,
            int page,
            int size,
            String query,
            String brand,
            Boolean visible,
            Boolean active,
            Boolean missingImages) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return productRepository.searchProducts(
                shopId,
                query,
                brand,
                visible,
                active,
                missingImages,
                ImageStatus.MISSING,
                pageable);
    }

    public Product getProduct(String shopId, Long productId) {
        return productRepository.findByShopIdAndId(shopId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
    }

    @Transactional
    public Product updateProduct(String shopId, Long productId, ProductUpdate update) {
        Product product = getProduct(shopId, productId);

        if (update.salePrice() != null) {
            product.setSalePrice(update.salePrice());
        }
        if (update.oldPrice() != null) {
            product.setOldPrice(update.oldPrice());
        }
        if (update.visible() != null) {
            product.setVisible(update.visible());
        }
        if (update.active() != null) {
            product.setActive(update.active());
        }
        if (update.stockQuantity() != null) {
            product.setStockQuantity(update.stockQuantity());
        }
        if (update.availabilityMode() != null) {
            product.setAvailabilityMode(update.availabilityMode());
        }
        if (update.description() != null) {
            product.setDescription(update.description());
        }

        return productRepository.save(product);
    }

    @Transactional
    public BulkUpdateResult bulkUpdate(String shopId, ProductBulkRequest request) {
        if (request.productIds() == null || request.productIds().isEmpty()) {
            throw new IllegalArgumentException("productIds is required");
        }

        List<Product> products = productRepository.findByShopIdAndIdIn(shopId, request.productIds());
        if (products.size() != request.productIds().size()) {
            throw new IllegalArgumentException("Some products were not found in this shop");
        }

        int updated = 0;
        for (Product product : products) {
            boolean changed = false;

            if ("setVisible".equals(request.action()) && request.visible() != null) {
                product.setVisible(request.visible());
                changed = true;
            } else if ("setActive".equals(request.action()) && request.active() != null) {
                product.setActive(request.active());
                changed = true;
            } else if ("applyMarkup".equals(request.action()) && request.markupPercent() != null) {
                if (product.getSupplierPrice() != null) {
                    BigDecimal multiplier = BigDecimal.ONE.add(
                            request.markupPercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                    product.setSalePrice(product.getSupplierPrice().multiply(multiplier)
                            .setScale(0, RoundingMode.HALF_UP));
                    changed = true;
                }
            } else if ("setAvailabilityMode".equals(request.action()) && request.availabilityMode() != null) {
                product.setAvailabilityMode(request.availabilityMode());
                changed = true;
            }

            if (changed) {
                updated++;
            }
        }

        productRepository.saveAll(products);
        return new BulkUpdateResult(updated);
    }

    public List<String> listBrands(String shopId) {
        return productRepository.findDistinctBrandsByShopId(shopId);
    }

    public record ProductUpdate(
            BigDecimal salePrice,
            BigDecimal oldPrice,
            Boolean visible,
            Boolean active,
            Integer stockQuantity,
            AvailabilityMode availabilityMode,
            String description
    ) {}

    public record ProductBulkRequest(
            List<Long> productIds,
            String action,
            Boolean visible,
            Boolean active,
            BigDecimal markupPercent,
            AvailabilityMode availabilityMode
    ) {}

    public record BulkUpdateResult(int updatedCount) {}
}
