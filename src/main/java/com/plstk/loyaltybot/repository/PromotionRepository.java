package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    
    /**
     * Находит все активные акции, срок которых не истек
     */
    List<Promotion> findByIsActiveTrueAndExpiresAtAfter(LocalDateTime now);
    
    /**
     * Находит все акции (активные и неактивные)
     */
    List<Promotion> findAllByOrderByCreatedAtDesc();
}


