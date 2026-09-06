package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.PublicPriceStrategy;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Recomputes one {@link Product}'s storefront visibility/public price from its active
 * {@link SupplierOffer}s (Prompt 06, D-005/D-006/D-008, docs/ARCHITECTURE.md §14.8-9):
 *
 * <ul>
 *   <li>one or more active offers and {@code manualHidden=false} -&gt; product visible, public
 *       price = the minimum {@code calculatedSitePrice} among active offers (D-008);</li>
 *   <li>zero active offers -&gt; product invisible on storefront, but never physically deleted
 *       (D-005) - order history and {@code SupplierProductLink}s are untouched;</li>
 *   <li>{@code manualHidden=true} always wins over any number of active offers - an explicit
 *       operator override that automatic sync never clears (docs/ARCHITECTURE.md §14.8 "manual_hidden
 *       всегда имеет приоритет").</li>
 * </ul>
 *
 * <p>Deliberately scoped to a single {@code productId}, called explicitly by the apply stage for
 * every product actually touched by one batch (new/updated/deactivated offer) - it never scans the
 * whole catalog, so it can never affect a product that has no {@code SupplierOffer} at all (i.e. a
 * legacy product created by {@code ProductImportService}, which keeps using its existing manual
 * {@code visible}/{@code salePrice} admin controls untouched, per the workspace rule "existing admin
 * panel... must continue working").
 */
@Component
@RequiredArgsConstructor
public class CatalogAvailabilityService {

    private final ProductRepository productRepository;
    private final SupplierOfferRepository supplierOfferRepository;
    private final PricingService pricingService;

    /** @return the product's visibility state after recompute (for the caller's own before/after diff). */
    @Transactional
    public boolean recompute(String shopId, Long productId) {
        Product product = productRepository.findByShopIdAndId(shopId, productId)
                .orElseThrow(() -> new IllegalStateException("Product " + productId + " not found for shop " + shopId));

        List<SupplierOffer> activeOffers = supplierOfferRepository.findByShopIdAndProductIdAndActiveTrue(shopId, productId);
        boolean manualHidden = Boolean.TRUE.equals(product.getManualHidden());

        if (activeOffers.isEmpty()) {
            product.setVisible(false);
            // Clear the now-stale public price along with visibility - otherwise a product that
            // loses its last active offer keeps showing its last-known salePrice in any code path
            // that reads salePrice without also re-checking visible/active (e.g. a future admin
            // export or report), even though it can no longer be sold at that price.
            product.setSalePrice(null);
            productRepository.save(product);
            return false;
        }

        BigDecimal publicPrice = pricingService.selectPublicPrice(activeOffers, PublicPriceStrategy.LOWEST_ACTIVE_OFFER);
        product.setSalePrice(publicPrice);
        boolean visible = !manualHidden;
        product.setVisible(visible);
        productRepository.save(product);
        return visible;
    }
}
