package com.plstk.loyaltybot.service.importing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandAliasResolverTest {

    private final BrandAliasResolver resolver = new BrandAliasResolver();

    @Test
    void chanel_shanel_channel_areMutualAliases() {
        assertTrue(resolver.areAliases("Chanel", "Channel"));
        assertTrue(resolver.areAliases("Chanel", "Шанель"));
        assertTrue(resolver.areAliases("Channel", "Шанель"));
        assertTrue(resolver.areAliases("CHANEL", "channel"), "comparison must be case-insensitive");
    }

    @Test
    void identicalBrand_isNotConsideredAnAlias() {
        assertFalse(resolver.areAliases("Chanel", "Chanel"), "exact equality is a different check, not aliasing");
    }

    @Test
    void unrelatedBrands_areNotAliases() {
        assertFalse(resolver.areAliases("Chanel", "Dior"));
        assertFalse(resolver.areAliases(null, "Chanel"));
    }
}
