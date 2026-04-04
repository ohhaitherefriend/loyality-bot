package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShopRepository extends JpaRepository<Shop, Long> {
    
    Optional<Shop> findByShopId(String shopId);
    
    List<Shop> findByOwnerId(Long ownerId);
    
    @Query("SELECT s FROM Shop s JOIN ShopMember m ON s.shopId = m.shopId WHERE m.userId = :userId")
    List<Shop> findAllByMemberUserId(Long userId);
    
    boolean existsByShopId(String shopId);
}
