package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    Optional<User> findByChatId(Long chatId);
    
    Optional<User> findByPhoneNumber(String phoneNumber);
    
    List<User> findAllByPhoneNumber(String phoneNumber);
    
    List<User> findByRole(User.UserRole role);
    
    List<User> findByState(User.UserState state);
}