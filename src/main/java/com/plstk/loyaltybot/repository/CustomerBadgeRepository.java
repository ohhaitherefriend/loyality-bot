package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.CustomerBadge;
import com.plstk.loyaltybot.entity.ManualBadgeDefinition;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CustomerBadgeRepository extends JpaRepository<CustomerBadge, Long> {
    
    /**
     * Находит все бейджи пользователя
     */
    List<CustomerBadge> findByUserOrderByAwardedAtDesc(User user);
    
    /**
     * Находит активные бейджи пользователя
     */
    @Query("SELECT cb FROM CustomerBadge cb WHERE cb.user = :user " +
           "AND cb.status = 'ACTIVE' " +
           "AND (cb.expiresAt IS NULL OR cb.expiresAt > :now) " +
           "ORDER BY cb.awardedAt DESC")
    List<CustomerBadge> findActiveByUser(@Param("user") User user, @Param("now") LocalDateTime now);
    
    /**
     * Находит активные бейджи пользователя по chatId
     */
    @Query("SELECT cb FROM CustomerBadge cb WHERE cb.user.chatId = :chatId " +
           "AND cb.status = 'ACTIVE' " +
           "AND (cb.expiresAt IS NULL OR cb.expiresAt > :now) " +
           "ORDER BY cb.awardedAt DESC")
    List<CustomerBadge> findActiveByUserChatId(@Param("chatId") Long chatId, @Param("now") LocalDateTime now);
    
    /**
     * Считает активные бейджи пользователя данного типа
     */
    @Query("SELECT COUNT(cb) FROM CustomerBadge cb WHERE cb.user = :user " +
           "AND cb.badge = :badge AND cb.status = 'ACTIVE' " +
           "AND (cb.expiresAt IS NULL OR cb.expiresAt > :now)")
    long countActiveByUserAndBadge(
        @Param("user") User user, 
        @Param("badge") ManualBadgeDefinition badge,
        @Param("now") LocalDateTime now
    );
    
    /**
     * Считает всего активных бейджей у пользователя
     */
    @Query("SELECT COUNT(cb) FROM CustomerBadge cb WHERE cb.user = :user " +
           "AND cb.status = 'ACTIVE' " +
           "AND (cb.expiresAt IS NULL OR cb.expiresAt > :now)")
    long countActiveByUser(@Param("user") User user, @Param("now") LocalDateTime now);
    
    /**
     * Считает выдачи бейджа за период (для лимита месячных выдач)
     */
    @Query("SELECT COUNT(cb) FROM CustomerBadge cb WHERE cb.badge = :badge " +
           "AND cb.awardedAt >= :since")
    long countAwardsSince(@Param("badge") ManualBadgeDefinition badge, @Param("since") LocalDateTime since);
    
    /**
     * Находит истёкшие активные бейджи (для обновления статуса)
     */
    @Query("SELECT cb FROM CustomerBadge cb WHERE cb.status = 'ACTIVE' " +
           "AND cb.expiresAt IS NOT NULL AND cb.expiresAt < :now")
    List<CustomerBadge> findExpiredActiveBadges(@Param("now") LocalDateTime now);
    
    /**
     * Находит бейджи, истекающие скоро (для уведомлений)
     */
    @Query("SELECT cb FROM CustomerBadge cb WHERE cb.status = 'ACTIVE' " +
           "AND cb.expiresAt IS NOT NULL " +
           "AND cb.expiresAt > :now AND cb.expiresAt < :soon")
    List<CustomerBadge> findExpiringSoon(
        @Param("now") LocalDateTime now, 
        @Param("soon") LocalDateTime soon
    );
    
    /**
     * Проверяет, есть ли у пользователя активный бейдж с определённым perk
     */
    @Query("SELECT COUNT(cb) > 0 FROM CustomerBadge cb " +
           "JOIN cb.badge b " +
           "WHERE cb.user = :user AND cb.status = 'ACTIVE' " +
           "AND (cb.expiresAt IS NULL OR cb.expiresAt > :now) " +
           "AND b.perkType = :perkType")
    boolean hasActivePerkType(
        @Param("user") User user, 
        @Param("perkType") ManualBadgeDefinition.PerkType perkType,
        @Param("now") LocalDateTime now
    );
    
    /**
     * Находит активные бейджи с определённым perk
     */
    @Query("SELECT cb FROM CustomerBadge cb " +
           "JOIN cb.badge b " +
           "WHERE cb.user = :user AND cb.status = 'ACTIVE' " +
           "AND (cb.expiresAt IS NULL OR cb.expiresAt > :now) " +
           "AND b.perkType = :perkType")
    List<CustomerBadge> findActiveByUserAndPerkType(
        @Param("user") User user,
        @Param("perkType") ManualBadgeDefinition.PerkType perkType,
        @Param("now") LocalDateTime now
    );
}



