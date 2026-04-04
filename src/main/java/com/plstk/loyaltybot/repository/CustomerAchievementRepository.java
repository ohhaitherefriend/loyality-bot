package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.AchievementDefinition;
import com.plstk.loyaltybot.entity.CustomerAchievement;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerAchievementRepository extends JpaRepository<CustomerAchievement, Long> {
    
    /**
     * Находит все ачивки пользователя
     */
    List<CustomerAchievement> findByUserOrderByAwardedAtDesc(User user);
    
    /**
     * Находит ачивки пользователя по chatId
     */
    @Query("SELECT ca FROM CustomerAchievement ca WHERE ca.user.chatId = :chatId ORDER BY ca.awardedAt DESC")
    List<CustomerAchievement> findByUserChatId(@Param("chatId") Long chatId);
    
    /**
     * Проверяет, есть ли у пользователя данная ачивка
     */
    boolean existsByUserAndAchievement(User user, AchievementDefinition achievement);
    
    /**
     * Считает количество получений данной ачивки пользователем
     */
    long countByUserAndAchievement(User user, AchievementDefinition achievement);
    
    /**
     * Находит последнее получение ачивки пользователем
     */
    Optional<CustomerAchievement> findFirstByUserAndAchievementOrderByAwardedAtDesc(
        User user, AchievementDefinition achievement);
    
    /**
     * Находит ачивки, полученные после определённой даты
     */
    List<CustomerAchievement> findByUserAndAwardedAtAfter(User user, LocalDateTime after);
    
    /**
     * Находит непросмотренные ачивки (для уведомлений)
     */
    List<CustomerAchievement> findByUserAndNotificationSentFalse(User user);
    
    /**
     * Считает общее количество ачивок пользователя
     */
    long countByUser(User user);
    
    /**
     * Считает ачивки, выданные за период (для отчётов)
     */
    @Query("SELECT COUNT(ca) FROM CustomerAchievement ca WHERE ca.awardedAt >= :since AND ca.awardedAt < :until")
    long countAwardedBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Считает ачивки, выданные за период для конкретного магазина
     */
    @Query("SELECT COUNT(ca) FROM CustomerAchievement ca WHERE ca.user.shopId = :shopId AND ca.awardedAt >= :since AND ca.awardedAt < :until")
    long countAwardedBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
}

