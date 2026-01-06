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
    
    List<PurchaseCode> findByUser(User user);
    
    List<PurchaseCode> findByStatus(PurchaseCode.CodeStatus status);
    
    List<PurchaseCode> findByStatusAndExpiresAtBefore(PurchaseCode.CodeStatus status, LocalDateTime dateTime);
}