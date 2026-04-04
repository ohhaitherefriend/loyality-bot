package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.StampWallet;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StampWalletRepository extends JpaRepository<StampWallet, Long> {
    
    /**
     * Находит кошелёк штампов пользователя
     */
    Optional<StampWallet> findByUser(User user);
    
    /**
     * Находит кошелёк штампов по ID пользователя
     */
    @Query("SELECT sw FROM StampWallet sw WHERE sw.user.id = :userId")
    Optional<StampWallet> findByUserId(@Param("userId") Long userId);
    
    /**
     * Находит кошелёк штампов по chatId пользователя
     */
    @Query("SELECT sw FROM StampWallet sw WHERE sw.user.chatId = :chatId")
    Optional<StampWallet> findByUserChatId(@Param("chatId") Long chatId);
    
    /**
     * Находит всех пользователей с доступными наградами
     */
    List<StampWallet> findByRewardsAvailableGreaterThan(Integer minRewards);
    
    /**
     * Находит пользователей, которым осталось N штампов до награды
     */
    @Query("SELECT sw FROM StampWallet sw WHERE :stampsRequired - sw.stampsCount = :stampsUntilReward")
    List<StampWallet> findByStampsUntilReward(
        @Param("stampsRequired") Integer stampsRequired,
        @Param("stampsUntilReward") Integer stampsUntilReward
    );
}



