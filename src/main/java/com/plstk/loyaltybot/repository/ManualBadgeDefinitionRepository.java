package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.ManualBadgeDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ManualBadgeDefinitionRepository extends JpaRepository<ManualBadgeDefinition, Long> {
    
    /**
     * Находит все активные бейджи
     */
    List<ManualBadgeDefinition> findByIsActiveTrueOrderByDisplayOrderDesc();
    
    /**
     * Находит бейдж по названию
     */
    java.util.Optional<ManualBadgeDefinition> findByTitle(String title);
}



