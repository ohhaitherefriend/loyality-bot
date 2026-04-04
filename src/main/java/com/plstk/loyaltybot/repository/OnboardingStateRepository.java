package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.OnboardingState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OnboardingStateRepository extends JpaRepository<OnboardingState, Long> {
    
    Optional<OnboardingState> findByUserId(Long userId);
    
    Optional<OnboardingState> findByShopId(String shopId);
    
    @Query("SELECT o FROM OnboardingState o WHERE o.userId = :userId AND o.completed = false")
    Optional<OnboardingState> findActiveByUserId(Long userId);
    
    boolean existsByUserIdAndCompletedFalse(Long userId);
}
