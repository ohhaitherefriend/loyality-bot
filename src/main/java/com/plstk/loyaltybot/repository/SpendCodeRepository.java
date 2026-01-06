package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.SpendCode;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpendCodeRepository extends JpaRepository<SpendCode, Long> {
    
    Optional<SpendCode> findByCode(String code);
    
    List<SpendCode> findByUser(User user);
    
    List<SpendCode> findByStatus(SpendCode.CodeStatus status);
    
    List<SpendCode> findByStatusAndExpiresAtBefore(SpendCode.CodeStatus status, LocalDateTime dateTime);
}
