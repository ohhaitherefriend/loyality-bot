package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.ImageStatus;
import com.plstk.loyaltybot.entity.commerce.ProductImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductImageNormalizationService {

    private final ProductImageVisualNormalizationService visualNormalizationService;

    @Transactional
    public ProductImage normalizeImage(String shopId, Long productId, Long imageId) {
        return visualNormalizationService.normalizeProductImage(shopId, productId, imageId);
    }
}
