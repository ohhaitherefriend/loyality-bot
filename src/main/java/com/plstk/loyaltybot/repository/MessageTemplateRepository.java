package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.MessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {
    
    /**
     * Находит все активные шаблоны
     */
    List<MessageTemplate> findByIsActiveTrueOrderByNameAsc();
    
    /**
     * Находит шаблоны по категории
     */
    List<MessageTemplate> findByCategoryAndIsActiveTrueOrderByNameAsc(MessageTemplate.TemplateCategory category);
    
    /**
     * Находит шаблон по типу авто-триггера
     */
    Optional<MessageTemplate> findByAutoTriggerAndIsActiveTrue(MessageTemplate.AutoTriggerType autoTrigger);
    
    /**
     * Находит шаблон по названию
     */
    Optional<MessageTemplate> findByName(String name);
}



