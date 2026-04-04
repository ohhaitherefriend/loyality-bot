package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {
    
    Optional<Plan> findByCode(String code);
    
    List<Plan> findByIsActiveTrue();
    
    boolean existsByCode(String code);
}
