package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.entity.ShopMember;
import com.plstk.loyaltybot.entity.ShopMember.MemberRole;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Stage 7: {@link AuthorizationService} is the single source of truth for shop-scoped,
 * role-aware authorization. These tests cover role resolution (owner vs. member vs. no access)
 * and the "at least as privileged" semantics that {@link #hasRole} relies on.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock
    private ShopRepository shopRepository;
    @Mock
    private ShopMemberRepository shopMemberRepository;

    private AuthorizationService authorizationService;

    private static final AdminUser OWNER_USER = AdminUser.builder().id(1L).email("owner@example.com").build();
    private static final AdminUser ADMIN_USER = AdminUser.builder().id(2L).email("admin@example.com").build();
    private static final AdminUser STAFF_USER = AdminUser.builder().id(3L).email("staff@example.com").build();
    private static final AdminUser STRANGER_USER = AdminUser.builder().id(4L).email("stranger@example.com").build();
    private static final String SHOP_ID = "shop-1";

    @BeforeEach
    void setUp() {
        authorizationService = new AuthorizationService(shopRepository, shopMemberRepository);

        Shop shop = Shop.builder().id(100L).shopId(SHOP_ID).ownerId(OWNER_USER.getId()).build();
        lenient().when(shopRepository.findByShopId(SHOP_ID)).thenReturn(Optional.of(shop));
        lenient().when(shopMemberRepository.findByUserIdAndShopId(OWNER_USER.getId(), SHOP_ID)).thenReturn(Optional.empty());
        lenient().when(shopMemberRepository.findByUserIdAndShopId(ADMIN_USER.getId(), SHOP_ID))
                .thenReturn(Optional.of(ShopMember.builder().userId(ADMIN_USER.getId()).shopId(SHOP_ID).role(MemberRole.ADMIN).build()));
        lenient().when(shopMemberRepository.findByUserIdAndShopId(STAFF_USER.getId(), SHOP_ID))
                .thenReturn(Optional.of(ShopMember.builder().userId(STAFF_USER.getId()).shopId(SHOP_ID).role(MemberRole.STAFF).build()));
        lenient().when(shopMemberRepository.findByUserIdAndShopId(STRANGER_USER.getId(), SHOP_ID)).thenReturn(Optional.empty());
    }

    @Test
    void resolveRole_forShopOwnerWithoutMemberRow_returnsOwner() {
        assertTrue(authorizationService.resolveRole(OWNER_USER, SHOP_ID).filter(r -> r == MemberRole.OWNER).isPresent());
    }

    @Test
    void resolveRole_forAdminMember_returnsAdmin() {
        assertTrue(authorizationService.resolveRole(ADMIN_USER, SHOP_ID).filter(r -> r == MemberRole.ADMIN).isPresent());
    }

    @Test
    void resolveRole_forStaffMember_returnsStaff() {
        assertTrue(authorizationService.resolveRole(STAFF_USER, SHOP_ID).filter(r -> r == MemberRole.STAFF).isPresent());
    }

    @Test
    void resolveRole_forStranger_isEmpty() {
        assertFalse(authorizationService.resolveRole(STRANGER_USER, SHOP_ID).isPresent());
    }

    @Test
    void resolveRole_forNullUserOrBlankShopId_isEmpty() {
        assertFalse(authorizationService.resolveRole(null, SHOP_ID).isPresent());
        assertFalse(authorizationService.resolveRole(OWNER_USER, null).isPresent());
        assertFalse(authorizationService.resolveRole(OWNER_USER, "").isPresent());
    }

    @Test
    void hasAccess_trueForAnyResolvedRole_falseForStranger() {
        assertTrue(authorizationService.hasAccess(OWNER_USER, SHOP_ID));
        assertTrue(authorizationService.hasAccess(ADMIN_USER, SHOP_ID));
        assertTrue(authorizationService.hasAccess(STAFF_USER, SHOP_ID));
        assertFalse(authorizationService.hasAccess(STRANGER_USER, SHOP_ID));
    }

    @Test
    void hasRole_ownerSatisfiesEveryMinimum() {
        assertTrue(authorizationService.hasRole(OWNER_USER, SHOP_ID, MemberRole.OWNER));
        assertTrue(authorizationService.hasRole(OWNER_USER, SHOP_ID, MemberRole.ADMIN));
        assertTrue(authorizationService.hasRole(OWNER_USER, SHOP_ID, MemberRole.STAFF));
    }

    @Test
    void hasRole_adminSatisfiesAdminAndStaffButNotOwner() {
        assertFalse(authorizationService.hasRole(ADMIN_USER, SHOP_ID, MemberRole.OWNER));
        assertTrue(authorizationService.hasRole(ADMIN_USER, SHOP_ID, MemberRole.ADMIN));
        assertTrue(authorizationService.hasRole(ADMIN_USER, SHOP_ID, MemberRole.STAFF));
    }

    @Test
    void hasRole_staffSatisfiesOnlyStaff() {
        assertFalse(authorizationService.hasRole(STAFF_USER, SHOP_ID, MemberRole.OWNER));
        assertFalse(authorizationService.hasRole(STAFF_USER, SHOP_ID, MemberRole.ADMIN));
        assertTrue(authorizationService.hasRole(STAFF_USER, SHOP_ID, MemberRole.STAFF));
    }

    @Test
    void hasRole_strangerSatisfiesNothing() {
        assertFalse(authorizationService.hasRole(STRANGER_USER, SHOP_ID, MemberRole.STAFF));
    }

    @Test
    void isAtLeast_ordersRolesOwnerAdminStaff() {
        assertTrue(authorizationService.isAtLeast(MemberRole.OWNER, MemberRole.OWNER));
        assertTrue(authorizationService.isAtLeast(MemberRole.OWNER, MemberRole.STAFF));
        assertTrue(authorizationService.isAtLeast(MemberRole.ADMIN, MemberRole.STAFF));
        assertFalse(authorizationService.isAtLeast(MemberRole.ADMIN, MemberRole.OWNER));
        assertFalse(authorizationService.isAtLeast(MemberRole.STAFF, MemberRole.ADMIN));
    }
}
