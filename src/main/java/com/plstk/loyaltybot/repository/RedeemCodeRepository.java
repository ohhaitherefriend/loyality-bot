package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.RedeemCode;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RedeemCodeRepository extends JpaRepository<RedeemCode, Long> {
    
    /**
     * Находит код погашения по коду
     */
    Optional<RedeemCode> findByCode(String code);

    /**
     * Shop-scoped lookup used for cancel/confirm redemption (Stage 8 security hardening): {@code
     * code} is globally unique in the DB, but without this scope an admin/customer from one shop
     * could act on another shop's redeem code if they obtained the digits.
     */
    Optional<RedeemCode> findByCodeAndUser_ShopId(String code, String shopId);
    
    /**
     * Находит активные коды пользователя
     */
    List<RedeemCode> findByUserAndStatus(User user, RedeemCode.RedeemCodeStatus status);
    
    /**
     * Находит истекшие активные коды (для очистки)
     */
    List<RedeemCode> findByStatusAndExpiresAtBefore(
        RedeemCode.RedeemCodeStatus status, 
        LocalDateTime expiresAt
    );
    
    /**
     * Проверяет, есть ли у пользователя активный код погашения
     */
    boolean existsByUserAndStatus(User user, RedeemCode.RedeemCodeStatus status);
    
    /**
     * Считает погашенные награды за период для конкретного магазина
     */
    @Query("SELECT COUNT(r) FROM RedeemCode r WHERE r.user.shopId = :shopId AND r.status = 'USED' AND r.usedAt >= :since AND r.usedAt < :until")
    long countRedeemedBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
}



