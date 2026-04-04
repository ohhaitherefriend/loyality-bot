package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.ShopMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShopMemberRepository extends JpaRepository<ShopMember, Long> {
    
    List<ShopMember> findByUserId(Long userId);
    
    List<ShopMember> findByShopId(String shopId);
    
    Optional<ShopMember> findByUserIdAndShopId(Long userId, String shopId);
    
    boolean existsByUserIdAndShopId(Long userId, String shopId);
}
