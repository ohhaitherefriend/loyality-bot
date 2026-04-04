package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.MessageLog;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageLogRepository extends JpaRepository<MessageLog, Long> {
    
    /**
     * Находит последние сообщения пользователю
     */
    List<MessageLog> findTop10ByUserOrderBySentAtDesc(User user);
    
    /**
     * Считает сообщения пользователю за сегодня (для rate-limit)
     */
    @Query("SELECT COUNT(m) FROM MessageLog m WHERE m.user = :user AND m.sentAt >= :since")
    long countMessagesSince(@Param("user") User user, @Param("since") LocalDateTime since);
    
    /**
     * Считает авто-сообщения пользователю за сегодня
     */
    @Query("SELECT COUNT(m) FROM MessageLog m WHERE m.user = :user " +
           "AND m.messageType = 'AUTO_TRIGGER' AND m.sentAt >= :since")
    long countAutoMessagesSince(@Param("user") User user, @Param("since") LocalDateTime since);
    
    /**
     * Находит сообщения по типу за период
     */
    List<MessageLog> findByMessageTypeAndSentAtBetweenOrderBySentAtDesc(
        MessageLog.MessageType type, LocalDateTime from, LocalDateTime to);
    
    /**
     * Находит неудачные отправки
     */
    List<MessageLog> findByStatusOrderBySentAtDesc(MessageLog.DeliveryStatus status);
    
    /**
     * Статистика отправок за период
     */
    @Query("SELECT m.messageType, COUNT(m) FROM MessageLog m " +
           "WHERE m.sentAt >= :since GROUP BY m.messageType")
    List<Object[]> getMessageStatsSince(@Param("since") LocalDateTime since);
}



