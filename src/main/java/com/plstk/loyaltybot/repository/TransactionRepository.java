package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.Transaction;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    
    List<Transaction> findByUserOrderByCreatedAtDesc(User user);
    
    List<Transaction> findTop10ByUserOrderByCreatedAtDesc(User user);
    
    /**
     * Считает сумму покупок пользователя с указанной даты
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0.0) FROM Transaction t WHERE t.user = :user AND t.createdAt >= :since AND t.amount IS NOT NULL")
    Double sumAmountByUserAndCreatedAtAfter(@Param("user") User user, @Param("since") LocalDateTime since);
    
    /**
     * Считает общую сумму покупок за период (для статистики)
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0.0) FROM Transaction t WHERE t.createdAt >= :since AND t.createdAt < :until AND t.amount IS NOT NULL")
    Double sumAmountBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    // ========== Статистика для отчётов ==========
    
    /**
     * Количество транзакций за период
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.createdAt >= :since AND t.createdAt < :until")
    long countBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Средний чек за период
     */
    @Query("SELECT COALESCE(AVG(t.amount), 0.0) FROM Transaction t WHERE t.createdAt >= :since AND t.createdAt < :until AND t.amount IS NOT NULL AND t.amount > 0")
    Double averageAmountBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Количество транзакций пользователя за период
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.user = :user AND t.createdAt >= :since AND t.createdAt < :until")
    long countByUserBetween(@Param("user") User user, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Находит транзакции за период
     */
    @Query("SELECT t FROM Transaction t WHERE t.createdAt >= :since AND t.createdAt < :until ORDER BY t.createdAt DESC")
    List<Transaction> findBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Количество fast checkout транзакций за период
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.createdAt >= :since AND t.createdAt < :until AND t.purchaseCode IS NOT NULL AND t.purchaseCode.fastCheckout = true")
    long countFastCheckoutBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    // ========== Multi-tenant методы для отчётов ==========
    
    /**
     * Количество транзакций за период для конкретного магазина
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.user.shopId = :shopId AND t.createdAt >= :since AND t.createdAt < :until")
    long countBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Сумма транзакций за период для конкретного магазина
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0.0) FROM Transaction t WHERE t.user.shopId = :shopId AND t.createdAt >= :since AND t.createdAt < :until AND t.amount IS NOT NULL")
    Double sumAmountBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Средний чек за период для конкретного магазина
     */
    @Query("SELECT COALESCE(AVG(t.amount), 0.0) FROM Transaction t WHERE t.user.shopId = :shopId AND t.createdAt >= :since AND t.createdAt < :until AND t.amount IS NOT NULL AND t.amount > 0")
    Double averageAmountBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Количество fast checkout транзакций за период для конкретного магазина
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.user.shopId = :shopId AND t.createdAt >= :since AND t.createdAt < :until AND t.purchaseCode IS NOT NULL AND t.purchaseCode.fastCheckout = true")
    long countFastCheckoutBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
}