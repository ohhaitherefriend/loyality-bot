package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.CustomerStatus;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Сервис для управления профилем клиента и статусами.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerProfileService {
    
    private final UserRepository userRepository;
    private final ShopSettingsService shopSettingsService;
    
    /**
     * Результат обновления профиля
     */
    public record ProfileUpdateResult(
        User user,
        CustomerStatus oldStatus,
        CustomerStatus newStatus,
        boolean statusChanged,
        boolean isFirstPurchase
    ) {}
    
    /**
     * Обновляет профиль клиента после покупки
     * @param user клиент
     * @param amount сумма покупки (может быть null для fast checkout)
     * @param isFastCheckout была ли это быстрая покупка
     * @return результат обновления
     */
    @Transactional
    public ProfileUpdateResult recordPurchase(User user, Double amount, boolean isFastCheckout) {
        CustomerStatus oldStatus = user.getCustomerStatus();
        if (oldStatus == null) {
            oldStatus = CustomerStatus.NEW;
        }
        
        boolean isFirstPurchase = user.getFirstPurchaseAt() == null;
        
        // Обновляем статистику
        user.recordPurchase(amount, isFastCheckout);
        
        // Пересчитываем статус
        CustomerStatus newStatus = calculateStatus(user);
        boolean statusChanged = oldStatus != newStatus;
        
        if (statusChanged) {
            user.setCustomerStatus(newStatus);
            user.setStatusUpdatedAt(LocalDateTime.now());
            log.info("User {} status changed: {} -> {}", 
                user.getChatId(), oldStatus, newStatus);
        }
        
        User savedUser = userRepository.save(user);
        
        return new ProfileUpdateResult(
            savedUser,
            oldStatus,
            newStatus,
            statusChanged,
            isFirstPurchase
        );
    }
    
    /**
     * Вычисляет статус клиента на основе его активности
     */
    public CustomerStatus calculateStatus(User user) {
        int lostDays = shopSettingsService.getLostDaysSinceLastPurchase();
        int regularThreshold = shopSettingsService.getRegularThresholdPurchases();
        int vipThreshold = shopSettingsService.getVipThresholdPurchases();
        Double vipSpendThreshold = shopSettingsService.getVipThresholdTotalSpend();
        
        // Проверка на LOST
        Long daysSinceLastPurchase = user.getDaysSinceLastPurchase();
        if (daysSinceLastPurchase != null && daysSinceLastPurchase >= lostDays) {
            return CustomerStatus.LOST;
        }
        
        int purchases = user.getPurchasesCount() != null ? user.getPurchasesCount() : 0;
        double totalSpend = user.getTotalSpend() != null ? user.getTotalSpend() : 0.0;
        
        // Проверка на VIP
        boolean isVipByPurchases = purchases >= vipThreshold;
        boolean isVipBySpend = vipSpendThreshold != null && totalSpend >= vipSpendThreshold;
        
        if (isVipByPurchases || isVipBySpend) {
            return CustomerStatus.VIP;
        }
        
        // Проверка на REGULAR
        if (purchases >= regularThreshold) {
            return CustomerStatus.REGULAR;
        }
        
        // По умолчанию NEW
        return CustomerStatus.NEW;
    }
    
    /**
     * Принудительно пересчитывает статус пользователя
     */
    @Transactional
    public CustomerStatus recalculateStatus(User user) {
        CustomerStatus oldStatus = user.getCustomerStatus();
        CustomerStatus newStatus = calculateStatus(user);
        
        if (oldStatus != newStatus) {
            user.setCustomerStatus(newStatus);
            user.setStatusUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
            log.info("Recalculated user {} status: {} -> {}", 
                user.getChatId(), oldStatus, newStatus);
        }
        
        return newStatus;
    }
    
    /**
     * Возвращает информацию о прогрессе до следующего статуса
     */
    public String getStatusProgressInfo(User user) {
        CustomerStatus status = user.getCustomerStatus();
        if (status == null) status = CustomerStatus.NEW;
        
        int purchases = user.getPurchasesCount() != null ? user.getPurchasesCount() : 0;
        int regularThreshold = shopSettingsService.getRegularThresholdPurchases();
        int vipThreshold = shopSettingsService.getVipThresholdPurchases();
        
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Ваш статус:* ").append(status.getDisplayWithEmoji()).append("\n");
        sb.append("🛒 Всего покупок: ").append(purchases).append("\n\n");
        
        switch (status) {
            case NEW -> {
                int untilRegular = regularThreshold - purchases;
                sb.append("⭐ До статуса *REGULAR*: ").append(untilRegular).append(" ");
                sb.append(getPurchaseWord(untilRegular)).append("\n");
                sb.append("👑 До статуса *VIP*: ").append(vipThreshold - purchases).append(" ");
                sb.append(getPurchaseWord(vipThreshold - purchases));
            }
            case REGULAR -> {
                int untilVip = vipThreshold - purchases;
                sb.append("👑 До статуса *VIP*: ").append(untilVip).append(" ");
                sb.append(getPurchaseWord(untilVip));
            }
            case VIP -> {
                sb.append("🎉 Вы — наш VIP клиент!\n");
                sb.append("Спасибо за вашу лояльность!");
            }
            case LOST -> {
                sb.append("😢 Мы скучаем по вам!\n");
                sb.append("Сделайте покупку, чтобы вернуться в программу лояльности.");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Возвращает полную информацию о профиле клиента
     */
    public String getFullProfileInfo(User user) {
        StringBuilder sb = new StringBuilder();
        sb.append("👤 *Ваш профиль*\n\n");
        
        // Основная информация
        sb.append("📱 ").append(user.getFirstName());
        if (user.getLastName() != null) {
            sb.append(" ").append(user.getLastName());
        }
        sb.append("\n");
        
        // Статус
        CustomerStatus status = user.getCustomerStatus();
        if (status == null) status = CustomerStatus.NEW;
        sb.append("📊 Статус: ").append(status.getDisplayWithEmoji()).append("\n\n");
        
        // Статистика
        int purchases = user.getPurchasesCount() != null ? user.getPurchasesCount() : 0;
        double totalSpend = user.getTotalSpend() != null ? user.getTotalSpend() : 0.0;
        Double avgCheck = user.getAverageCheck();
        
        sb.append("📈 *Статистика:*\n");
        sb.append("• Покупок: ").append(purchases).append("\n");
        
        if (totalSpend > 0) {
            sb.append("• Общая сумма: ").append(String.format("%.2f", totalSpend)).append(" руб.\n");
            sb.append("• Средний чек: ").append(String.format("%.2f", avgCheck)).append(" руб.\n");
        }
        
        if (user.getFirstPurchaseAt() != null) {
            sb.append("• Клиент с: ").append(user.getFirstPurchaseAt().toLocalDate()).append("\n");
        }
        
        if (user.getLastPurchaseAt() != null) {
            Long daysSince = user.getDaysSinceLastPurchase();
            sb.append("• Последняя покупка: ");
            if (daysSince == 0) {
                sb.append("сегодня");
            } else if (daysSince == 1) {
                sb.append("вчера");
            } else {
                sb.append(daysSince).append(" дней назад");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Склонение слова "покупка"
     */
    private String getPurchaseWord(int count) {
        int abs = Math.abs(count) % 100;
        int lastDigit = abs % 10;
        
        if (abs >= 11 && abs <= 19) {
            return "покупок";
        }
        
        return switch (lastDigit) {
            case 1 -> "покупка";
            case 2, 3, 4 -> "покупки";
            default -> "покупок";
        };
    }
    
    /**
     * Scheduled job для проверки потерянных клиентов
     * Запускается раз в день
     */
    @Scheduled(cron = "0 0 10 * * *") // Каждый день в 10:00
    @Transactional
    public void checkLostCustomers() {
        int lostDays = shopSettingsService.getLostDaysSinceLastPurchase();
        LocalDateTime threshold = LocalDateTime.now().minusDays(lostDays);
        
        List<User> users = userRepository.findByState(User.UserState.REGISTERED);
        int lostCount = 0;
        
        for (User user : users) {
            if (user.getCustomerStatus() != CustomerStatus.LOST &&
                user.getLastPurchaseAt() != null &&
                user.getLastPurchaseAt().isBefore(threshold)) {
                
                user.setCustomerStatus(CustomerStatus.LOST);
                user.setStatusUpdatedAt(LocalDateTime.now());
                userRepository.save(user);
                lostCount++;
                
                log.info("User {} marked as LOST (last purchase: {})", 
                    user.getChatId(), user.getLastPurchaseAt());
            }
        }
        
        if (lostCount > 0) {
            log.info("Marked {} users as LOST", lostCount);
        }
    }
    
    /**
     * Находит пользователей, близких к статусу REGULAR
     */
    public List<User> findUsersAlmostRegular() {
        int threshold = shopSettingsService.getRegularThresholdPurchases();
        return userRepository.findAll().stream()
            .filter(u -> u.getCustomerStatus() == CustomerStatus.NEW)
            .filter(u -> {
                int purchases = u.getPurchasesCount() != null ? u.getPurchasesCount() : 0;
                return purchases == threshold - 1;
            })
            .toList();
    }
    
    /**
     * Находит пользователей, близких к статусу VIP
     */
    public List<User> findUsersAlmostVip() {
        int threshold = shopSettingsService.getVipThresholdPurchases();
        return userRepository.findAll().stream()
            .filter(u -> u.getCustomerStatus() == CustomerStatus.REGULAR)
            .filter(u -> {
                int purchases = u.getPurchasesCount() != null ? u.getPurchasesCount() : 0;
                return purchases == threshold - 1;
            })
            .toList();
    }
    
    /**
     * Находит VIP клиентов, которые стали неактивными
     */
    public List<User> findInactiveVipCustomers(int inactiveDays) {
        LocalDateTime threshold = LocalDateTime.now().minusDays(inactiveDays);
        return userRepository.findAll().stream()
            .filter(u -> u.getCustomerStatus() == CustomerStatus.VIP)
            .filter(u -> u.getLastPurchaseAt() != null && u.getLastPurchaseAt().isBefore(threshold))
            .toList();
    }
}



