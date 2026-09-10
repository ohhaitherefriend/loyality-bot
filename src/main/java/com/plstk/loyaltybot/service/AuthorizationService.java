package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.ShopMember;
import com.plstk.loyaltybot.entity.ShopMember.MemberRole;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for shop-scoped authorization (Stage 7).
 *
 * <p>Resolves the caller's effective {@link MemberRole} for a shop: the shop owner is always
 * treated as {@link MemberRole#OWNER}, regardless of whether a {@code shop_members} row exists
 * for them; otherwise the role comes from their {@code shop_members} row, if any.
 *
 * <p>Role privilege order (most to least privileged): {@code OWNER > ADMIN > STAFF}. {@link
 * #hasRole} treats a role as satisfying a minimum requirement when it is at least as privileged
 * as that minimum (e.g. an {@code OWNER} satisfies a {@code STAFF} requirement).
 *
 * <p>This replaces the former role-blind {@code ShopAccessService} (removed) and the ad-hoc
 * {@code hasAccessToShop} checks duplicated across {@code AdminApiController}, {@code
 * BillingController}, and {@code ReportsController}, which now delegate to {@link #hasAccess}.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final ShopRepository shopRepository;
    private final ShopMemberRepository shopMemberRepository;

    /**
     * Platform-wide administrator allowlist (previously duplicated in {@code AdminApiController}
     * as {@code systemAdminEmailsRaw}/{@code isSystemAdmin} - centralized here so every caller,
     * including {@code BillingController}'s billing-bypass endpoints (ADR-028), shares one
     * definition of "platform administrator" instead of each re-implementing the same parsing.
     * Empty by default, which fails closed - nobody can call these until an operator explicitly
     * lists their own email here.
     */
    @Value("${app.system-admin-emails:}")
    private String systemAdminEmailsRaw;

    /**
     * Resolves the caller's effective role for the given shop, or empty if they have no access
     * at all (not the owner and no {@code shop_members} row).
     */
    public Optional<MemberRole> resolveRole(AdminUser user, String shopId) {
        if (user == null || user.getId() == null || shopId == null || shopId.isBlank()) {
            return Optional.empty();
        }
        boolean isOwner = shopRepository.findByShopId(shopId)
                .map(shop -> shop.getOwnerId().equals(user.getId()))
                .orElse(false);
        if (isOwner) {
            return Optional.of(MemberRole.OWNER);
        }
        return shopMemberRepository.findByUserIdAndShopId(user.getId(), shopId)
                .map(ShopMember::getRole);
    }

    /** True if the user has any role at all on the shop (owner or any {@code shop_members} row). */
    public boolean hasAccess(AdminUser user, String shopId) {
        return resolveRole(user, shopId).isPresent();
    }

    /**
     * True if the user's effective role on the shop is at least as privileged as {@code minRole}.
     * A user with no role at all never satisfies this.
     */
    public boolean hasRole(AdminUser user, String shopId, MemberRole minRole) {
        return resolveRole(user, shopId).map(role -> isAtLeast(role, minRole)).orElse(false);
    }

    /**
     * True when {@code role} is at least as privileged as {@code minRole}. Privilege order is the
     * declaration order of {@link MemberRole}: {@code OWNER} (0) is most privileged, {@code STAFF}
     * (2) is least, so "at least" means a lower-or-equal ordinal.
     */
    public boolean isAtLeast(MemberRole role, MemberRole minRole) {
        return role.ordinal() <= minRole.ordinal();
    }

    /**
     * True if {@code user} is one of the operator-configured platform administrators - distinct
     * from any shop-scoped {@link MemberRole}, including a shop's own {@code OWNER}. A shop owner
     * is a platform customer, not a platform operator: billing-bypass actions (stub activation,
     * trial extension) and other platform-wide endpoints must never be satisfied merely by being
     * the owner of the shop they're acting on (see docs/DECISIONS.md ADR-028).
     */
    public boolean isSystemAdmin(AdminUser user) {
        if (user == null || user.getEmail() == null || systemAdminEmailsRaw == null) {
            return false;
        }
        Set<String> allowed = Arrays.stream(systemAdminEmailsRaw.split(","))
                .map(String::trim)
                .filter(email -> !email.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        return allowed.contains(user.getEmail().toLowerCase());
    }
}
