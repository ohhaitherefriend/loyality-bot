package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.CustomerStatus;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    // ========== Single-tenant (для обратной совместимости) ==========
    
    Optional<User> findByChatId(Long chatId);
    
    Optional<User> findByPhoneNumber(String phoneNumber);
    
    List<User> findAllByPhoneNumber(String phoneNumber);
    
    List<User> findByRole(User.UserRole role);
    
    List<User> findByState(User.UserState state);
    
    // ========== Multi-tenant методы ==========
    
    /**
     * Найти пользователя по chatId и shopId
     */
    Optional<User> findByChatIdAndShopId(Long chatId, String shopId);
    
    /**
     * Найти пользователя по телефону и shopId
     */
    Optional<User> findByPhoneNumberAndShopId(String phoneNumber, String shopId);
    
    /**
     * Все пользователи магазина
     */
    List<User> findByShopId(String shopId);
    
    /**
     * Пользователи магазина по роли
     */
    List<User> findByShopIdAndRole(String shopId, User.UserRole role);
    
    /**
     * Количество пользователей магазина
     */
    long countByShopId(String shopId);
    
    /**
     * Зарегистрированные пользователи магазина
     */
    @Query("SELECT u FROM User u WHERE u.shopId = :shopId AND u.state = 'REGISTERED'")
    List<User> findRegisteredByShopId(@Param("shopId") String shopId);
    
    /**
     * Количество зарегистрированных пользователей магазина
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.shopId = :shopId AND u.state = 'REGISTERED'")
    long countRegisteredByShopId(@Param("shopId") String shopId);
    
    // ========== Статистика для отчётов ==========
    
    /**
     * Новые клиенты (первая покупка в периоде)
     */
    @Query("SELECT u FROM User u WHERE u.firstPurchaseAt >= :since AND u.firstPurchaseAt < :until")
    List<User> findNewCustomersBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Количество новых клиентов за период
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.firstPurchaseAt >= :since AND u.firstPurchaseAt < :until")
    long countNewCustomersBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Вернувшиеся клиенты (покупка в периоде, но не первая)
     */
    @Query("SELECT u FROM User u WHERE u.lastPurchaseAt >= :since AND u.lastPurchaseAt < :until " +
           "AND u.firstPurchaseAt < :since")
    List<User> findReturningCustomersBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Количество вернувшихся клиентов
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.lastPurchaseAt >= :since AND u.lastPurchaseAt < :until " +
           "AND u.firstPurchaseAt < :since")
    long countReturningCustomersBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Потерянные клиенты (стали LOST за период)
     */
    @Query("SELECT u FROM User u WHERE u.customerStatus = :status AND u.statusUpdatedAt >= :since AND u.statusUpdatedAt < :until")
    List<User> findByStatusChangedBetween(@Param("status") CustomerStatus status, 
                                          @Param("since") LocalDateTime since, 
                                          @Param("until") LocalDateTime until);
    
    /**
     * Клиенты по статусу
     */
    List<User> findByCustomerStatus(CustomerStatus status);
    
    /**
     * Количество клиентов по статусу
     */
    long countByCustomerStatus(CustomerStatus status);
    
    /**
     * Топ клиентов по сумме покупок за всё время
     */
    @Query("SELECT u FROM User u WHERE u.totalSpend IS NOT NULL ORDER BY u.totalSpend DESC")
    List<User> findTopByTotalSpend();
    
    /**
     * Топ клиентов по количеству покупок
     */
    @Query("SELECT u FROM User u WHERE u.purchasesCount IS NOT NULL ORDER BY u.purchasesCount DESC")
    List<User> findTopByPurchasesCount();
    
    // ========== Multi-tenant методы для отчётов ==========
    
    /**
     * Зарегистрированные пользователи за период для конкретного магазина (по дате создания)
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.shopId = :shopId AND u.createdAt >= :since AND u.createdAt < :until")
    long countRegisteredBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Новые клиенты за период для конкретного магазина (по первой покупке)
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.shopId = :shopId AND u.firstPurchaseAt >= :since AND u.firstPurchaseAt < :until")
    long countNewCustomersBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Вернувшиеся клиенты за период для конкретного магазина
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.shopId = :shopId AND u.lastPurchaseAt >= :since AND u.lastPurchaseAt < :until AND u.firstPurchaseAt < :since")
    long countReturningCustomersBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Потерянные клиенты за период для конкретного магазина
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.shopId = :shopId AND u.customerStatus = 'LOST' AND u.statusUpdatedAt >= :since AND u.statusUpdatedAt < :until")
    long countLostCustomersBetweenByShopId(@Param("shopId") String shopId, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
    
    /**
     * Количество клиентов по статусу для конкретного магазина
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.shopId = :shopId AND u.customerStatus = :status")
    long countByShopIdAndCustomerStatus(@Param("shopId") String shopId, @Param("status") CustomerStatus status);
    
    /**
     * Топ клиентов по сумме покупок для конкретного магазина
     */
    @Query("SELECT u FROM User u WHERE u.shopId = :shopId AND u.totalSpend IS NOT NULL AND u.totalSpend > 0 ORDER BY u.totalSpend DESC")
    List<User> findTopByTotalSpendByShopId(@Param("shopId") String shopId);
}