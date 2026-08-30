package com.plstk.loyaltybot.service.storefront;

import com.plstk.loyaltybot.entity.commerce.ImageStatus;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.service.commerce.ImageStorageService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StorefrontProductMapper {

    private static final int SHORT_NAME_MAX = 60;

    private final ImageStorageService imageStorageService;

    public StorefrontProductMapper(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    public StorefrontProductDto toDto(Product product) {
        String imageUrl = null;
        if (ImageStatus.APPROVED.equals(product.getImageStatus()) && StringUtils.hasText(product.getMainImageUrl())) {
            imageUrl = imageStorageService.toAbsoluteUrl(product.getMainImageUrl());
        }

        return new StorefrontProductDto(
                product.getId(),
                product.getBrand(),
                product.getName(),
                shortName(product.getName()),
                product.getDescription(),
                product.getSalePrice(),
                product.getOldPrice(),
                product.getCurrency(),
                product.getAvailabilityMode(),
                product.getStockQuantity(),
                imageUrl,
                product.getImageStatus(),
                product.getCategoryPath()
        );
    }

    private String shortName(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        if (name.length() <= SHORT_NAME_MAX) {
            return name;
        }
        return name.substring(0, SHORT_NAME_MAX - 1) + "…";
    }

    public record StorefrontProductDto(
            Long id,
            String brand,
            String name,
            String shortName,
            String description,
            java.math.BigDecimal salePrice,
            java.math.BigDecimal oldPrice,
            String currency,
            com.plstk.loyaltybot.entity.commerce.AvailabilityMode availabilityMode,
            Integer stockQuantity,
            String mainImageUrl,
            ImageStatus imageStatus,
            String categoryPath
    ) {}
}
