package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    
    private final UserRepository userRepository;
    private final TransactionService transactionService;
    private final ShopSettingsService shopSettingsService;
    
    // ========== Single-tenant методы (для обратной совместимости) ==========
    
    public Optional<User> findByChatId(Long chatId) {
        return userRepository.findByChatId(chatId);
    }
    
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
    
    public Optional<User> findByPhoneNumber(String phoneNumber) {
        try {
            return userRepository.findByPhoneNumber(phoneNumber);
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
            // If there are duplicates, return the first one and log a warning
            log.warn("Multiple users found with phone number: {}. Returning the first one.", phoneNumber);
            List<User> users = userRepository.findAllByPhoneNumber(phoneNumber);
            return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
        }
    }
    
    public List<User> findAllByPhoneNumber(String phoneNumber) {
        return userRepository.findAllByPhoneNumber(phoneNumber);
    }
    
    // ========== Multi-tenant методы ==========
    
    /**
     * Найти пользователя по chatId и shopId (multi-tenant)
     */
    public Optional<User> findByChatIdAndShopId(Long chatId, String shopId) {
        if (shopId == null) {
            return findByChatId(chatId); // Fallback to single-tenant
        }
        return userRepository.findByChatIdAndShopId(chatId, shopId);
    }
    
    /**
     * Найти пользователя по телефону и shopId (multi-tenant)
     */
    public Optional<User> findByPhoneNumberAndShopId(String phoneNumber, String shopId) {
        if (shopId == null) {
            return findByPhoneNumber(phoneNumber);
        }
        return userRepository.findByPhoneNumberAndShopId(phoneNumber, shopId);
    }
    
    /**
     * Получить всех пользователей магазина
     */
    public List<User> findByShopId(String shopId) {
        return userRepository.findByShopId(shopId);
    }
    
    /**
     * Получить зарегистрированных пользователей магазина
     */
    public List<User> findRegisteredByShopId(String shopId) {
        return userRepository.findRegisteredByShopId(shopId);
    }
    
    /**
     * Сохранить пользователя
     */
    @Transactional
    public User save(User user) {
        return userRepository.save(user);
    }
    
    @Transactional
    public User createUser(Long chatId, String phoneNumber, String firstName, String lastName, String username) {
        User user = User.builder()
                .chatId(chatId)
                .phoneNumber(phoneNumber)
                .firstName(firstName)
                .lastName(lastName)
                .username(username)
                .role(User.UserRole.USER)
                .state(User.UserState.REGISTERED)
                .build();
        
        User savedUser = userRepository.save(user);
        log.info("Created new user: chatId={}, phone={}", chatId, phoneNumber);
        return savedUser;
    }
    
    @Transactional
    public User updateUserState(User user, User.UserState state) {
        user.setState(state);
        return userRepository.save(user);
    }
    
    @Transactional
    public User setUserRole(User user, User.UserRole role) {
        user.setRole(role);
        User savedUser = userRepository.save(user);
        log.info("Changed role for user {}: {} -> {}", 
            user.getChatId(), user.getRole(), role);
        return savedUser;
    }
    
    /**
     * Проверяет транзакции пользователя и активирует/продлевает скидку если нужно.
     * Логика:
     * 1. Считаем сумму покупок с начала периода накопления
     * 2. Если накоплено >= порога - активируем или продлеваем скидку
     */
    @Transactional
    public User checkAndUpdateDiscount(User user) {
        // Если накопительные скидки отключены - ничего не делаем
        if (!shopSettingsService.isDiscountTiersEnabled()) {
            return user;
        }
        
        // Получаем дату с которой считаем накопления
        LocalDateTime startDate = user.getAccumulationStartDate();
        
        // Считаем сумму покупок с этой даты
        double accumulated = transactionService.getAccumulatedAmountSince(user, startDate);
        
        // Определяем уровень скидки на основе накопленной суммы (из настроек)
        Integer newDiscountLevel = shopSettingsService.calculateDiscountLevel(accumulated);
        
        boolean wasDiscountActive = user.isDiscountValid();
        Integer oldDiscountLevel = user.getDiscountLevel();
        
        // Проверяем, нужно ли активировать или продлить скидку
        if (newDiscountLevel != null) {
            if (!wasDiscountActive) {
                // Скидки не было - активируем новую
                user.setDiscountLevel(newDiscountLevel);
                user.setDiscountEarnedAt(LocalDateTime.now());
                
                log.info("User {} earned new discount! Level: {}%, accumulated: {}", 
                    user.getChatId(), newDiscountLevel, accumulated);
                
                return userRepository.save(user);
            } else if (newDiscountLevel >= oldDiscountLevel) {
                // Скидка была - продлеваем или повышаем уровень
                user.setDiscountLevel(newDiscountLevel);
                user.setDiscountEarnedAt(LocalDateTime.now()); // Продлеваем на 30 дней
                
                log.info("User {} extended discount! Level: {}%, accumulated: {}", 
                    user.getChatId(), newDiscountLevel, accumulated);
                
                return userRepository.save(user);
            }
        }
        
        return user;
    }
    
    /**
     * Возвращает накопленную сумму пользователя с начала периода накопления
     */
    public double getAccumulatedAmount(User user) {
        LocalDateTime startDate = user.getAccumulationStartDate();
        return transactionService.getAccumulatedAmountSince(user, startDate);
    }
    
    /**
     * Обрабатывает покупку - возвращает текущий процент скидки.
     * Транзакция должна быть создана ДО вызова этого метода!
     */
    @Transactional
    public double processPurchase(User user) {
        double discountPercent = user.getEffectiveDiscountPercent();
        
        checkAndUpdateDiscount(user);
        checkAndUpdatePermanentDiscount(user);
        
        log.info("Processed purchase for user {}, applied discount: {}%", 
            user.getChatId(), discountPercent * 100);
        
        return discountPercent;
    }
    
    /**
     * Проверяет общую сумму покупок пользователя и обновляет постоянную скидку если нужно.
     * Постоянная скидка никогда не сгорает и только растёт.
     */
    @Transactional
    public User checkAndUpdatePermanentDiscount(User user) {
        String shopId = user.getShopId();
        
        if (shopId == null || !shopSettingsService.isPermanentDiscountEnabled(shopId)) {
            return user;
        }
        
        double totalSpend = user.getTotalSpend() != null ? user.getTotalSpend() : 0.0;
        Integer newLevel = shopSettingsService.calculatePermanentDiscountLevel(shopId, totalSpend);
        Integer currentLevel = user.getPermanentDiscountPercent();
        
        if (newLevel != null && (currentLevel == null || newLevel > currentLevel)) {
            user.setPermanentDiscountPercent(newLevel);
            log.info("User {} earned permanent discount {}% (totalSpend={})", 
                user.getChatId(), newLevel, totalSpend);
            return userRepository.save(user);
        }
        
        return user;
    }
    
    public List<User> findAllRegisteredUsers() {
        return userRepository.findByState(User.UserState.REGISTERED);
    }
    
    public List<User> findAllAdmins() {
        return userRepository.findByRole(User.UserRole.ADMIN);
    }
    
    public List<User> findByRole(User.UserRole role) {
        return userRepository.findByRole(role);
    }
    
    /**
     * Получает статистику по всем пользователям
     */
    public UserStats getUserStats() {
        List<User> allUsers = userRepository.findAll();
        
        long totalUsers = 0;
        long registeredUsers = 0;
        long admins = 0;
        long noDiscountUsers = 0;
        long discount5Users = 0;
        long discount7Users = 0;
        long discount10Users = 0;
        
        for (User user : allUsers) {
            totalUsers++;
            
            if (user.getRole() == User.UserRole.ADMIN) {
                admins++;
            }
            
            if (user.getState() == User.UserState.REGISTERED) {
                registeredUsers++;
                
                double discountPercent = user.getDiscountPercent();
                if (discountPercent >= 0.10) {
                    discount10Users++;
                } else if (discountPercent >= 0.07) {
                    discount7Users++;
                } else if (discountPercent >= 0.05) {
                    discount5Users++;
                } else {
                    noDiscountUsers++;
                }
            }
        }
        
        // Считаем общую сумму покупок за текущий месяц
        double totalMonthlyAmount = transactionService.getTotalAmountForCurrentMonth();
        
        return new UserStats(
            totalUsers,
            registeredUsers,
            admins,
            noDiscountUsers,
            discount5Users,
            discount7Users,
            discount10Users,
            totalMonthlyAmount
        );
    }
    
    /**
     * Класс для хранения статистики пользователей
     */
    public static class UserStats {
        public final long totalUsers;
        public final long registeredUsers;
        public final long admins;
        public final long noDiscountUsers;
        public final long discount5Users;
        public final long discount7Users;
        public final long discount10Users;
        public final double totalMonthlyAmount;  // Общая сумма покупок за текущий месяц
        
        public UserStats(long totalUsers, long registeredUsers, long admins,
                        long noDiscountUsers, long discount5Users, 
                        long discount7Users, long discount10Users,
                        double totalMonthlyAmount) {
            this.totalUsers = totalUsers;
            this.registeredUsers = registeredUsers;
            this.admins = admins;
            this.noDiscountUsers = noDiscountUsers;
            this.discount5Users = discount5Users;
            this.discount7Users = discount7Users;
            this.discount10Users = discount10Users;
            this.totalMonthlyAmount = totalMonthlyAmount;
        }
    }
}
