package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierOfferRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 07 {@code SET_MANUAL_HIDDEN} operator action: setting it hides a visible product
 * immediately (recompute runs synchronously), and clearing it lets an active-offer product become
 * visible again - {@link ProductVisibilityOverrideService} is the only writer of this field.
 */
@DataJpaTest
@Import(ProductVisibilityOverrideServiceTest.TestConfig.class)
class ProductVisibilityOverrideServiceTest {

    private static final String SHOP_A = "shop-a";

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SupplierOfferRepository supplierOfferRepository;
    @Autowired
    private ProductVisibilityOverrideService productVisibilityOverrideService;
    @Autowired
    private EntityManager entityManager;

    private Supplier supplier;
    private SupplierSource source;

    @BeforeEach
    void setUp() {
        supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier").build());
        source = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(supplier).label("main").build());
        flushClear();
    }

    @Test
    void settingManualHidden_hidesAnOtherwiseVisibleProduct() {
        Product product = saveProductWithActiveOffer(true);
        flushClear();

        Optional<Product> result = productVisibilityOverrideService.setManualHidden(SHOP_A, product.getId(), true);

        assertTrue(result.isPresent());
        assertTrue(result.get().getManualHidden());
        assertFalse(result.get().getVisible(), "manualHidden must immediately hide the product even with an active offer");
    }

    @Test
    void clearingManualHidden_letsAnActiveOfferProductBecomeVisibleAgain() {
        Product product = saveProductWithActiveOffer(true);
        productVisibilityOverrideService.setManualHidden(SHOP_A, product.getId(), true);
        flushClear();

        Optional<Product> result = productVisibilityOverrideService.setManualHidden(SHOP_A, product.getId(), false);

        assertTrue(result.isPresent());
        assertFalse(result.get().getManualHidden());
        assertTrue(result.get().getVisible(), "clearing manualHidden with an active offer must restore visibility");
    }

    @Test
    void unknownProductId_returnsEmpty() {
        assertTrue(productVisibilityOverrideService.setManualHidden(SHOP_A, 999_999L, true).isEmpty());
    }

    @Test
    void crossShopProduct_isNotVisibleToAnotherShop() {
        Product product = saveProductWithActiveOffer(true);
        flushClear();

        assertTrue(productVisibilityOverrideService.setManualHidden("shop-b", product.getId(), true).isEmpty());
    }

    // ===== helpers =====

    private Product saveProductWithActiveOffer(boolean visible) {
        Product product = productRepository.save(Product.builder()
                .shopId(SHOP_A).name("Product").currency("RUB").visible(visible).build());
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_A).supplier(supplier).supplierSource(source).product(product)
                .supplierPrice(new BigDecimal("100.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("130.00")).active(true).build());
        return product;
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        PricingService pricingService(com.plstk.loyaltybot.config.SupplierImportProperties properties) {
            return new PricingService(properties);
        }

        @Bean
        CatalogAvailabilityService catalogAvailabilityService(
                ProductRepository productRepository, SupplierOfferRepository supplierOfferRepository, PricingService pricingService) {
            return new CatalogAvailabilityService(productRepository, supplierOfferRepository, pricingService);
        }

        @Bean
        ProductVisibilityOverrideService productVisibilityOverrideService(
                ProductRepository productRepository, CatalogAvailabilityService catalogAvailabilityService) {
            return new ProductVisibilityOverrideService(productRepository, catalogAvailabilityService);
        }
    }
}
