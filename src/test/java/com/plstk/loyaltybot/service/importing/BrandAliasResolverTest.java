package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.BrandAlias;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrandAliasResolverTest {

    private static final String SHOP_ID = "shop-1";

    private final BrandAliasRepository repository = mock(BrandAliasRepository.class);
    private final BrandAliasResolver resolver = new BrandAliasResolver(repository, new BrandNormalizer());

    @BeforeEach
    void seedChanelGroup() {
        when(repository.findByShopIdOrderByCanonicalBrandAscAliasAsc(SHOP_ID)).thenReturn(List.of(
                BrandAlias.builder().id(1L).shopId(SHOP_ID).canonicalBrand("Chanel").alias("Chanel").normalizedAlias("chanel").build(),
                BrandAlias.builder().id(2L).shopId(SHOP_ID).canonicalBrand("Chanel").alias("Шанель").normalizedAlias("шанель").build(),
                BrandAlias.builder().id(3L).shopId(SHOP_ID).canonicalBrand("Chanel").alias("Channel").normalizedAlias("channel").build()));
    }

    @Test
    void chanel_shanel_channel_areMutualAliases() {
        assertTrue(resolver.areAliases(SHOP_ID, "Chanel", "Channel"));
        assertTrue(resolver.areAliases(SHOP_ID, "Chanel", "Шанель"));
        assertTrue(resolver.areAliases(SHOP_ID, "Channel", "Шанель"));
        assertTrue(resolver.areAliases(SHOP_ID, "CHANEL", "channel"), "comparison must be case-insensitive");
    }

    @Test
    void identicalBrand_isNotConsideredAnAlias() {
        assertFalse(resolver.areAliases(SHOP_ID, "Chanel", "Chanel"), "exact equality is a different check, not aliasing");
    }

    @Test
    void unrelatedBrands_areNotAliases() {
        assertFalse(resolver.areAliases(SHOP_ID, "Chanel", "Dior"));
        assertFalse(resolver.areAliases(SHOP_ID, null, "Chanel"));
    }

    @Test
    void differentShop_doesNotSeeAnotherShopsAliases() {
        when(repository.findByShopIdOrderByCanonicalBrandAscAliasAsc("shop-2")).thenReturn(List.of());
        assertFalse(resolver.areAliases("shop-2", "Chanel", "Шанель"));
    }

    @Test
    void transliterationFallback_catchesRegularCyrillicLatinPairsWithoutAnExplicitAliasRow() {
        when(repository.findByShopIdOrderByCanonicalBrandAscAliasAsc("shop-3")).thenReturn(List.of());
        assertTrue(resolver.areAliases("shop-3", "Dior", "Диор"),
                "letter-for-letter transliteration should be recognized even with zero configured aliases");
    }
}
