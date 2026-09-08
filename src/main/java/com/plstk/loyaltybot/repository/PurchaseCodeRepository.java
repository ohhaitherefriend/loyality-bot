package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.PurchaseCode;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseCodeRepository extends JpaRepository<PurchaseCode, Long> {
    
    Optional<PurchaseCode> findByCode(String code);

    /**
     * Shop-scoped lookup used for admin redemption (Stage 8 security hardening): {@code code} is
     * globally unique in the DB, but without this scope an admin from one shop could redeem
     * another shop's customer code if they obtained the digits. Generation uniqueness checks may
     * keep using {@link #findByCode(String)} since they run before a shop is even assigned to the
     * new row.
     */
    Optional<PurchaseCode> findByCodeAndUser_ShopId(String code, String shopId);

    List<PurchaseCode> findByUser(User user);
    
    List<PurchaseCode> findByStatus(PurchaseCode.CodeStatus status);
    
    List<PurchaseCode> findByStatusAndExpiresAtBefore(PurchaseCode.CodeStatus status, LocalDateTime dateTime);
}