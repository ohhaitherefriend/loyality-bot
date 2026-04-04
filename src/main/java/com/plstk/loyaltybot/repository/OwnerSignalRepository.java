package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.OwnerSignal;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OwnerSignalRepository extends JpaRepository<OwnerSignal, Long> {
    
    /**
     * Находит все активные (непросмотренные и нескрытые) сигналы
     */
    @Query("SELECT s FROM OwnerSignal s WHERE s.isDismissed = false " +
           "AND (s.expiresAt IS NULL OR s.expiresAt > :now) " +
           "ORDER BY s.severity DESC, s.createdAt DESC")
    List<OwnerSignal> findActiveSignals(@Param("now") LocalDateTime now);
    
    /**
     * Находит непрочитанные сигналы
     */
    @Query("SELECT s FROM OwnerSignal s WHERE s.isSeen = false AND s.isDismissed = false " +
           "AND (s.expiresAt IS NULL OR s.expiresAt > :now) " +
           "ORDER BY s.severity DESC, s.createdAt DESC")
    List<OwnerSignal> findUnseenSignals(@Param("now") LocalDateTime now);
    
    /**
     * Считает непрочитанные сигналы
     */
    @Query("SELECT COUNT(s) FROM OwnerSignal s WHERE s.isSeen = false AND s.isDismissed = false " +
           "AND (s.expiresAt IS NULL OR s.expiresAt > :now)")
    long countUnseenSignals(@Param("now") LocalDateTime now);
    
    /**
     * Находит сигналы по типу
     */
    List<OwnerSignal> findBySignalTypeAndIsDismissedFalseOrderByCreatedAtDesc(OwnerSignal.SignalType type);
    
    /**
     * Находит сигналы, связанные с клиентом
     */
    List<OwnerSignal> findByCustomerAndIsDismissedFalseOrderByCreatedAtDesc(User customer);
    
    /**
     * Проверяет, есть ли недавний сигнал такого же типа для клиента
     * (чтобы не создавать дубликаты)
     */
    @Query("SELECT COUNT(s) > 0 FROM OwnerSignal s WHERE s.signalType = :type " +
           "AND s.customer = :customer AND s.createdAt > :since AND s.isDismissed = false")
    boolean existsRecentSignalForCustomer(
        @Param("type") OwnerSignal.SignalType type,
        @Param("customer") User customer,
        @Param("since") LocalDateTime since
    );
    
    /**
     * Находит истёкшие сигналы для очистки
     */
    List<OwnerSignal> findByExpiresAtBeforeAndIsDismissedFalse(LocalDateTime expiresAt);
    
    /**
     * Находит сигналы за период (для аналитики)
     */
    List<OwnerSignal> findByCreatedAtBetweenOrderByCreatedAtDesc(
        LocalDateTime from, LocalDateTime to);
}



