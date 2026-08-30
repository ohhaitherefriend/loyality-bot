package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShopAccessService {

    private final ShopRepository shopRepository;
    private final ShopMemberRepository shopMemberRepository;

    public boolean hasAccess(AdminUser user, String shopId) {
        if (user == null || shopId == null || shopId.isBlank()) {
            return false;
        }
        if (shopRepository.findByShopId(shopId)
                .map(shop -> shop.getOwnerId().equals(user.getId()))
                .orElse(false)) {
            return true;
        }
        return shopMemberRepository.existsByUserIdAndShopId(user.getId(), shopId);
    }
}
