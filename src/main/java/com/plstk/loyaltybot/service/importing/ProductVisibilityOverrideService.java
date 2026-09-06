package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Prompt 07 {@code SET_MANUAL_HIDDEN} operator action (docs/ARCHITECTURE.md §12/§14.8, D-006):
 * the only writer of {@link Product#getManualHidden()} in the whole codebase - automatic sync
 * ({@code CatalogAvailabilityService}, {@code ImportBatchApplyWriter}) only ever reads it, never
 * clears it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductVisibilityOverrideService {

    private final ProductRepository productRepository;
    private final CatalogAvailabilityService catalogAvailabilityService;

    /** @return empty if the product does not exist for this shop. */
    @Transactional
    public Optional<Product> setManualHidden(String shopId, Long productId, boolean hidden) {
        Optional<Product> found = productRepository.findByShopIdAndId(shopId, productId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Product product = found.get();
        product.setManualHidden(hidden);
        productRepository.save(product);
        // Recompute immediately: flipping manualHidden can change storefront visibility right now,
        // without waiting for the next supplier offer event to touch this product.
        catalogAvailabilityService.recompute(shopId, productId);
        Product refreshed = productRepository.findByShopIdAndId(shopId, productId).orElseThrow();
        log.info("Product {} (shop {}) manualHidden set to {} by operator", productId, shopId, hidden);
        return Optional.of(refreshed);
    }
}
