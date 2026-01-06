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
}