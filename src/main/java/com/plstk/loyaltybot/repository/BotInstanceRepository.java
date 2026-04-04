package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.BotInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotInstanceRepository extends JpaRepository<BotInstance, Long> {
    
    /**
     * Найти бот по shopId
     */
    Optional<BotInstance> findByShopId(String shopId);
    
    /**
     * Найти бот по username
     */
    Optional<BotInstance> findByBotUsername(String botUsername);
    
    /**
     * Найти бот по Telegram Bot ID
     */
    Optional<BotInstance> findByTelegramBotId(Long telegramBotId);
    
    /**
     * Найти бот по ID и секрету webhook (для валидации)
     */
    Optional<BotInstance> findByIdAndWebhookSecret(Long id, String webhookSecret);
    
    /**
     * Найти все активные боты
     */
    List<BotInstance> findByIsActiveTrueAndStatus(BotInstance.BotStatus status);
    
    /**
     * Найти все активные боты
     */
    @Query("SELECT b FROM BotInstance b WHERE b.isActive = true AND b.status = 'ACTIVE'")
    List<BotInstance> findAllActive();

    /**
     * Найти активные боты по списку shopId
     */
    @Query("SELECT b FROM BotInstance b WHERE b.isActive = true AND b.status = 'ACTIVE' AND b.shopId IN :shopIds")
    List<BotInstance> findActiveByShopIdIn(List<String> shopIds);
    
    /**
     * Проверить существует ли бот с таким токеном
     */
    boolean existsByTelegramBotId(Long telegramBotId);
    
    /**
     * Найти боты владельца
     */
    List<BotInstance> findByOwnerChatId(Long ownerChatId);
    
    /**
     * Найти активные боты по email владельца
     */
    @Query("SELECT b FROM BotInstance b WHERE b.isActive = true AND b.status = 'ACTIVE' AND b.ownerEmail = :email")
    List<BotInstance> findActiveByOwnerEmail(String email);
    
    /**
     * Подсчитать активные боты
     */
    @Query("SELECT COUNT(b) FROM BotInstance b WHERE b.isActive = true AND b.status = 'ACTIVE'")
    long countActiveBots();
}

