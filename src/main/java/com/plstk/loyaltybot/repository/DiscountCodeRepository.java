package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.DiscountCode;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {
    
    Optional<DiscountCode> findByCode(String code);
    
    List<DiscountCode> findByUser(User user);
    
    List<DiscountCode> findByStatus(DiscountCode.CodeStatus status);
}