package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.entity.importing.PriceRoundingPolicy;
import com.plstk.loyaltybot.entity.importing.PublicPriceStrategy;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit coverage of {@link PricingService} (Prompt 06, D-007/D-008) - no Spring context
 * needed since it has a single simple dependency ({@link SupplierImportProperties}).
 */
class PricingServiceTest {

    private final SupplierImportProperties properties = new SupplierImportProperties();
    private final PricingService pricingService = new PricingService(properties);

    @Test
    void docExample_supplierPrice1000_commission30Percent_wholeUnit_equals1300() {
        BigDecimal sitePrice = pricingService.calculateSitePrice(
                new BigDecimal("1000"), new BigDecimal("30"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP);
        assertEquals(new BigDecimal("1300.00"), sitePrice);
    }

    @Test
    void moneyRound_wholeUnitHalfUp_roundsToNearestIntegerCurrencyUnit() {
        assertEquals(new BigDecimal("101.00"),
                pricingService.moneyRound(new BigDecimal("100.50"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP));
        assertEquals(new BigDecimal("100.00"),
                pricingService.moneyRound(new BigDecimal("100.49"), PriceRoundingPolicy.WHOLE_UNIT_HALF_UP));
    }

    @Test
    void moneyRound_minorUnitHalfUp_roundsToCents() {
        assertEquals(new BigDecimal("100.46"),
                pricingService.moneyRound(new BigDecimal("100.455"), PriceRoundingPolicy.MINOR_UNIT_HALF_UP));
    }

    @Test
    void resolveCommissionPercent_sourceOverride_winsOverShopAndGlobalDefault() {
        SupplierSource source = SupplierSource.builder()
                .commissionPercentOverride(new BigDecimal("15.00")).build();
        ShopSettings settings = ShopSettings.builder().defaultCommissionPercent(new BigDecimal("20.00")).build();

        assertEquals(new BigDecimal("15.00"), pricingService.resolveCommissionPercent(source, settings));
    }

    @Test
    void resolveCommissionPercent_shopDefault_winsOverGlobalDefault_whenNoSourceOverride() {
        SupplierSource source = SupplierSource.builder().build();
        ShopSettings settings = ShopSettings.builder().defaultCommissionPercent(new BigDecimal("20.00")).build();

        assertEquals(new BigDecimal("20.00"), pricingService.resolveCommissionPercent(source, settings));
    }

    @Test
    void resolveCommissionPercent_fallsBackToGlobalConfigDefault_whenNothingConfigured() {
        SupplierSource source = SupplierSource.builder().build();

        assertEquals(BigDecimal.valueOf(properties.getPricing().getDefaultCommissionPercent()),
                pricingService.resolveCommissionPercent(source, null));
    }

    @Test
    void selectPublicPrice_lowestActiveOffer_picksMinimum() {
        SupplierOffer cheap = SupplierOffer.builder().calculatedSitePrice(new BigDecimal("900.00")).build();
        SupplierOffer expensive = SupplierOffer.builder().calculatedSitePrice(new BigDecimal("1200.00")).build();

        BigDecimal selected = pricingService.selectPublicPrice(List.of(expensive, cheap), PublicPriceStrategy.LOWEST_ACTIVE_OFFER);
        assertEquals(new BigDecimal("900.00"), selected);
    }

    @Test
    void selectPublicPrice_emptyOffers_returnsNull() {
        assertNull(pricingService.selectPublicPrice(List.of(), PublicPriceStrategy.LOWEST_ACTIVE_OFFER));
    }
}
