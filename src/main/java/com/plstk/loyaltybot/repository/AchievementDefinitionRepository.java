package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.AchievementDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AchievementDefinitionRepository extends JpaRepository<AchievementDefinition, Long> {
    
    /**
     * Находит все активные ачивки
     */
    List<AchievementDefinition> findByIsActiveTrueOrderByDisplayOrderDesc();
    
    /**
     * Находит ачивки по типу триггера
     */
    List<AchievementDefinition> findByTriggerTypeAndIsActiveTrue(AchievementDefinition.TriggerType triggerType);
    
    /**
     * Находит все ачивки для отображения
     */
    List<AchievementDefinition> findAllByOrderByDisplayOrderDesc();
}



