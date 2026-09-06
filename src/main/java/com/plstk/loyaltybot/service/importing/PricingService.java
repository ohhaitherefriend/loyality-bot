package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.entity.importing.PriceRoundingPolicy;
import com.plstk.loyaltybot.entity.importing.PublicPriceStrategy;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Percentage-based public pricing (Prompt 06, D-007, docs/ARCHITECTURE.md §3/§14.5/§14.9):
 * {@code sitePrice = moneyRound(supplierPrice * (1 + commissionPercent / 100))}. All money math is
 * {@link BigDecimal} - no {@code double} is ever used for a supplier price, commission percent, or
 * site price (workspace rule).
 *
 * <p>Commission percent is resolved with a three-level override, most specific first:
 * {@code SupplierSource.commissionPercentOverride} &gt; {@code ShopSettings.defaultCommissionPercent}
 * &gt; the global {@code supplier-import.pricing.default-commission-percent} config default. A later
 * change to any of these only affects future {@link SupplierOffer}s computed with it -
 * {@code SupplierOffer.appliedCommissionPercent} is stored per-offer precisely so past pricing stays
 * an accurate historical record even if the policy changes later (same reasoning as
 * {@code SupplierSource.aiAutoApproveMinScoreOverride} in ADR-005).
 */
@Component
@RequiredArgsConstructor
public class PricingService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final SupplierImportProperties properties;

    public BigDecimal resolveCommissionPercent(SupplierSource source, ShopSettings shopSettings) {
        if (source.getCommissionPercentOverride() != null) {
            return source.getCommissionPercentOverride();
        }
        if (shopSettings != null && shopSettings.getDefaultCommissionPercent() != null) {
            return shopSettings.getDefaultCommissionPercent();
        }
        return BigDecimal.valueOf(properties.getPricing().getDefaultCommissionPercent());
    }

    /**
     * @param supplierPrice must be positive - callers (row validity from Prompt 03/04) already
     *     guarantee this; not re-validated here.
     */
    public BigDecimal calculateSitePrice(BigDecimal supplierPrice, BigDecimal commissionPercent, PriceRoundingPolicy policy) {
        BigDecimal multiplier = BigDecimal.ONE.add(commissionPercent.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
        BigDecimal raw = supplierPrice.multiply(multiplier);
        return moneyRound(raw, policy);
    }

    /**
     * Versioned rounding policy (docs/STATE.md ADR-001: {@code PriceRoundingPolicy} is stored per
     * {@code SupplierSource} so a later policy change never retroactively changes an already
     * persisted {@code SupplierOffer.calculatedSitePrice}).
     */
    public BigDecimal moneyRound(BigDecimal amount, PriceRoundingPolicy policy) {
        return switch (policy) {
            case WHOLE_UNIT_HALF_UP -> amount.setScale(0, RoundingMode.HALF_UP).setScale(2);
            case MINOR_UNIT_HALF_UP -> amount.setScale(2, RoundingMode.HALF_UP);
        };
    }

    /**
     * Default public price selection when a product has more than one active supplier offer
     * (D-008): the minimum {@code calculatedSitePrice} among active offers. {@code
     * PublicPriceStrategy} currently has only one value; the strategy parameter is accepted (rather
     * than hardcoding {@code LOWEST_ACTIVE_OFFER} here) so a future second strategy does not require
     * changing every caller's signature.
     *
     * @return {@code null} if {@code activeOffers} is empty - callers must treat that as "no public
     *     price" (product should not be visible), never as zero.
     */
    public BigDecimal selectPublicPrice(List<SupplierOffer> activeOffers, PublicPriceStrategy strategy) {
        if (activeOffers == null || activeOffers.isEmpty()) {
            return null;
        }
        return switch (strategy) {
            case LOWEST_ACTIVE_OFFER -> activeOffers.stream()
                    .map(SupplierOffer::getCalculatedSitePrice)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        };
    }
}
