package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.BrandAlias;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrandAliasAdminServiceTest {

    private static final String SHOP_ID = "shop-1";

    // BrandAliasResolver is a real (not mocked) instance: Mockito's inline mock maker cannot
    // subclass concrete classes on this JDK/Byte Buddy combination (same constraint as
    // SupplierImportAdminControllerTest) - only the repository interface it depends on is mocked.
    private final BrandAliasRepository repository = mock(BrandAliasRepository.class);
    private final BrandAliasResolver resolver = new BrandAliasResolver(repository, new BrandNormalizer());
    private final BrandAliasAdminService service = new BrandAliasAdminService(repository, new BrandNormalizer(), resolver);

    @Test
    void create_savesNormalizedAlias() {
        when(repository.findByShopIdAndNormalizedAlias(SHOP_ID, "шанель")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BrandAlias created = service.create(SHOP_ID, "Chanel", "Шанель", "owner@example.com");

        assertEquals(SHOP_ID, created.getShopId());
        assertEquals("Chanel", created.getCanonicalBrand());
        assertEquals("Шанель", created.getAlias());
        assertEquals("шанель", created.getNormalizedAlias());
        assertEquals("owner@example.com", created.getCreatedBy());
    }

    @Test
    void create_invalidatesResolverCache_soANewlyAddedAliasIsSeenOnTheNextLookup() {
        when(repository.findByShopIdAndNormalizedAlias(SHOP_ID, "channel")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByShopIdOrderByCanonicalBrandAscAliasAsc(SHOP_ID)).thenReturn(List.of());

        // Warms the resolver's per-shop cache with an empty index (repository stub above).
        assertTrue(resolver.expand(SHOP_ID, "Chanel").size() <= 2, "sanity: no group known yet");

        service.create(SHOP_ID, "Chanel", "Channel", "owner@example.com");

        // Stub now reflects the new row, but only a cache invalidation makes the resolver re-query.
        when(repository.findByShopIdOrderByCanonicalBrandAscAliasAsc(SHOP_ID)).thenReturn(List.of(
                BrandAlias.builder().shopId(SHOP_ID).canonicalBrand("Chanel").alias("Channel").normalizedAlias("channel").build()));

        assertTrue(resolver.areAliases(SHOP_ID, "Chanel", "Channel"),
                "the newly created alias must be visible immediately, not only after a batch restart");
        verify(repository, times(2)).findByShopIdOrderByCanonicalBrandAscAliasAsc(SHOP_ID);
    }

    @Test
    void create_rejectsDuplicateAliasWithinShop() {
        when(repository.findByShopIdAndNormalizedAlias(SHOP_ID, "chanel"))
                .thenReturn(Optional.of(BrandAlias.builder().id(1L).shopId(SHOP_ID).build()));

        BrandAliasValidationException ex = assertThrows(BrandAliasValidationException.class,
                () -> service.create(SHOP_ID, "Chanel", "Chanel", "owner@example.com"));
        assertEquals("already exists for this shop", ex.getFieldErrors().get("alias"));
    }

    @Test
    void create_rejectsBlankFields() {
        assertThrows(BrandAliasValidationException.class, () -> service.create(SHOP_ID, "", "Alias", "u"));
        assertThrows(BrandAliasValidationException.class, () -> service.create(SHOP_ID, "Brand", "", "u"));
    }

    @Test
    void delete_forOtherShop_throwsNotFound() {
        when(repository.findByShopIdAndId("other-shop", 5L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.delete("other-shop", 5L));
    }

    @Test
    void list_returnsShopScopedAliases() {
        when(repository.findByShopIdOrderByCanonicalBrandAscAliasAsc(SHOP_ID)).thenReturn(List.of(
                BrandAlias.builder().id(1L).shopId(SHOP_ID).canonicalBrand("Chanel").alias("Chanel").normalizedAlias("chanel").build()));

        List<BrandAlias> result = service.list(SHOP_ID);

        assertEquals(1, result.size());
    }
}
