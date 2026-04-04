package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    
    Optional<Subscription> findByShopId(String shopId);
    
    @Query("SELECT s FROM Subscription s WHERE s.status = 'TRIALING' AND s.trialEndAt < :now")
    List<Subscription> findExpiredTrials(LocalDateTime now);
    
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.currentPeriodEndAt < :now")
    List<Subscription> findExpiredActive(LocalDateTime now);
    
    List<Subscription> findByStatus(Subscription.SubscriptionStatus status);
}
