package com.plstk.loyaltybot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.*;
import com.plstk.loyaltybot.repository.AchievementDefinitionRepository;
import com.plstk.loyaltybot.repository.CustomerAchievementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Сервис для работы с ачивками (достижениями).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AchievementService {
    
    private final AchievementDefinitionRepository achievementDefinitionRepository;
    private final CustomerAchievementRepository customerAchievementRepository;
    private final StampWalletService stampWalletService;
    private final ShopSettingsService shopSettingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Результат проверки ачивок
     */
    public record AchievementCheckResult(
        List<CustomerAchievement> newAchievements,
        boolean hasNewAchievements
    ) {}
    
    /**
     * Проверяет и начисляет ачивки после покупки
     */
    @Transactional
    public AchievementCheckResult checkAchievementsAfterPurchase(User user, Double amount, boolean isFastCheckout) {
        List<CustomerAchievement> newAchievements = new ArrayList<>();
        List<AchievementDefinition> activeAchievements = achievementDefinitionRepository.findByIsActiveTrueOrderByDisplayOrderDesc();
        
        for (AchievementDefinition achievement : activeAchievements) {
            if (shouldAwardAchievement(user, achievement, amount, isFastCheckout)) {
                CustomerAchievement awarded = awardAchievement(user, achievement, createMetadata(amount, isFastCheckout));
                if (awarded != null) {
                    newAchievements.add(awarded);
                }
            }
        }
        
        return new AchievementCheckResult(newAchievements, !newAchievements.isEmpty());
    }
    
    /**
     * Проверяет и начисляет ачивки при изменении статуса
     */
    @Transactional
    public AchievementCheckResult checkAchievementsOnStatusChange(User user, CustomerStatus oldStatus, CustomerStatus newStatus) {
        List<CustomerAchievement> newAchievements = new ArrayList<>();
        
        // Ачивка за статус REGULAR
        if (newStatus == CustomerStatus.REGULAR && oldStatus != CustomerStatus.REGULAR) {
            List<AchievementDefinition> regularAchievements = 
                achievementDefinitionRepository.findByTriggerTypeAndIsActiveTrue(AchievementDefinition.TriggerType.BECAME_REGULAR);
            
            for (AchievementDefinition achievement : regularAchievements) {
                if (canAwardAchievement(user, achievement)) {
                    CustomerAchievement awarded = awardAchievement(user, achievement, null);
                    if (awarded != null) {
                        newAchievements.add(awarded);
                    }
                }
            }
        }
        
        // Ачивка за статус VIP
        if (newStatus == CustomerStatus.VIP && oldStatus != CustomerStatus.VIP) {
            List<AchievementDefinition> vipAchievements = 
                achievementDefinitionRepository.findByTriggerTypeAndIsActiveTrue(AchievementDefinition.TriggerType.BECAME_VIP);
            
            for (AchievementDefinition achievement : vipAchievements) {
                if (canAwardAchievement(user, achievement)) {
                    CustomerAchievement awarded = awardAchievement(user, achievement, null);
                    if (awarded != null) {
                        newAchievements.add(awarded);
                    }
                }
            }
        }
        
        return new AchievementCheckResult(newAchievements, !newAchievements.isEmpty());
    }
    
    /**
     * Проверяет, нужно ли выдать ачивку
     */
    private boolean shouldAwardAchievement(User user, AchievementDefinition achievement, Double amount, boolean isFastCheckout) {
        // Проверяем базовые ограничения
        if (!canAwardAchievement(user, achievement)) {
            return false;
        }
        
        // Проверяем условие триггера
        return checkTriggerCondition(user, achievement, amount, isFastCheckout);
    }
    
    /**
     * Проверяет, можно ли выдать ачивку (cooldown, лимиты)
     */
    private boolean canAwardAchievement(User user, AchievementDefinition achievement) {
        // Проверяем максимум выдач
        if (achievement.getMaxAwardsPerCustomer() != null) {
            long count = customerAchievementRepository.countByUserAndAchievement(user, achievement);
            if (count >= achievement.getMaxAwardsPerCustomer()) {
                return false;
            }
        }
        
        // Проверяем cooldown
        if (achievement.getCooldownDays() != null && achievement.getCooldownDays() > 0) {
            Optional<CustomerAchievement> lastAwarded = 
                customerAchievementRepository.findFirstByUserAndAchievementOrderByAwardedAtDesc(user, achievement);
            
            if (lastAwarded.isPresent()) {
                LocalDateTime cooldownEnd = lastAwarded.get().getAwardedAt()
                    .plusDays(achievement.getCooldownDays());
                if (LocalDateTime.now().isBefore(cooldownEnd)) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    /**
     * Проверяет условие триггера
     */
    private boolean checkTriggerCondition(User user, AchievementDefinition achievement, Double amount, boolean isFastCheckout) {
        AchievementDefinition.TriggerType trigger = achievement.getTriggerType();
        Integer triggerValue = achievement.getTriggerValueInt();
        Integer periodDays = achievement.getTriggerValuePeriodDays();
        
        switch (trigger) {
            case FIRST_PURCHASE:
                // Первая покупка = purchasesCount == 1 после записи
                return user.getPurchasesCount() != null && user.getPurchasesCount() == 1;
                
            case N_PURCHASES_TOTAL:
                if (triggerValue == null) return false;
                return user.getPurchasesCount() != null && user.getPurchasesCount() >= triggerValue;
                
            case N_VISITS_TOTAL:
                if (triggerValue == null) return false;
                return user.getVisitsCount() != null && user.getVisitsCount() >= triggerValue;
                
            case N_PURCHASES_IN_PERIOD:
                if (triggerValue == null || periodDays == null) return false;
                return checkPurchasesInPeriod(user, triggerValue, periodDays);
                
            case COME_BACK_AFTER_DAYS:
                if (triggerValue == null) return false;
                return checkComeBackAfterDays(user, triggerValue);
                
            case PURCHASE_TIME_WINDOW:
                return checkPurchaseTimeWindow(achievement);
                
            case SPEND_IN_PERIOD:
                if (triggerValue == null || periodDays == null) return false;
                return checkSpendInPeriod(user, triggerValue, periodDays);
                
            case N_STAMPS_TOTAL:
                if (triggerValue == null) return false;
                return checkTotalStamps(user, triggerValue);
                
            case N_REWARDS_REDEEMED:
                if (triggerValue == null) return false;
                return checkRewardsRedeemed(user, triggerValue);
                
            // BECAME_REGULAR и BECAME_VIP обрабатываются в checkAchievementsOnStatusChange
            case BECAME_REGULAR:
            case BECAME_VIP:
                return false;
                
            default:
                return false;
        }
    }
    
    private boolean checkPurchasesInPeriod(User user, int requiredPurchases, int periodDays) {
        // Для этого нужен запрос к транзакциям
        // Пока упрощённая проверка через purchasesCount
        // TODO: добавить точную проверку через TransactionRepository
        return user.getPurchasesCount() != null && user.getPurchasesCount() >= requiredPurchases;
    }
    
    private boolean checkComeBackAfterDays(User user, int daysAway) {
        // Клиент "вернулся", если:
        // 1. У него была lastPurchaseAt раньше чем daysAway дней назад
        // 2. Сейчас он делает покупку
        // Это проверяется в момент покупки, когда lastPurchaseAt ещё старая
        if (user.getLastPurchaseAt() == null) {
            return false;
        }
        
        long daysSinceLastPurchase = java.time.Duration.between(
            user.getLastPurchaseAt(), LocalDateTime.now()).toDays();
        
        return daysSinceLastPurchase >= daysAway;
    }
    
    private boolean checkPurchaseTimeWindow(AchievementDefinition achievement) {
        String params = achievement.getTriggerParams();
        if (params == null) return false;
        
        try {
            Map<String, Object> paramsMap = objectMapper.readValue(params, Map.class);
            Integer startHour = (Integer) paramsMap.get("startHour");
            Integer endHour = (Integer) paramsMap.get("endHour");
            
            if (startHour == null || endHour == null) return false;
            
            int currentHour = LocalTime.now().getHour();
            
            if (startHour <= endHour) {
                return currentHour >= startHour && currentHour < endHour;
            } else {
                // Ночное окно (например, 22:00 - 06:00)
                return currentHour >= startHour || currentHour < endHour;
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse trigger params: {}", params, e);
            return false;
        }
    }
    
    private boolean checkSpendInPeriod(User user, int requiredAmount, int periodDays) {
        // TODO: добавить точную проверку через TransactionRepository
        return user.getTotalSpend() != null && user.getTotalSpend() >= requiredAmount;
    }
    
    private boolean checkTotalStamps(User user, int requiredStamps) {
        Optional<StampWallet> walletOpt = stampWalletService.getWallet(user);
        if (walletOpt.isEmpty()) return false;
        
        StampWallet wallet = walletOpt.get();
        return wallet.getTotalStampsEarned() != null && wallet.getTotalStampsEarned() >= requiredStamps;
    }
    
    private boolean checkRewardsRedeemed(User user, int requiredRewards) {
        Optional<StampWallet> walletOpt = stampWalletService.getWallet(user);
        if (walletOpt.isEmpty()) return false;
        
        StampWallet wallet = walletOpt.get();
        return wallet.getRewardsRedeemed() != null && wallet.getRewardsRedeemed() >= requiredRewards;
    }
    
    /**
     * Выдаёт ачивку пользователю
     */
    @Transactional
    public CustomerAchievement awardAchievement(User user, AchievementDefinition achievement, String metadata) {
        CustomerAchievement customerAchievement = CustomerAchievement.builder()
            .user(user)
            .achievement(achievement)
            .awardedAt(LocalDateTime.now())
            .metadata(metadata)
            .rewardApplied(false)
            .notificationSent(false)
            .build();
        
        CustomerAchievement saved = customerAchievementRepository.save(customerAchievement);
        
        log.info("Awarded achievement '{}' to user chatId={}", 
            achievement.getTitle(), user.getChatId());
        
        // Применяем награду если есть
        if (achievement.getRewardType() != AchievementDefinition.RewardType.NONE) {
            applyReward(user, achievement, saved);
        }
        
        return saved;
    }
    
    /**
     * Применяет награду за ачивку
     */
    private void applyReward(User user, AchievementDefinition achievement, CustomerAchievement customerAchievement) {
        AchievementDefinition.RewardType rewardType = achievement.getRewardType();
        Integer rewardValue = achievement.getRewardValue();
        
        if (rewardValue == null || rewardValue <= 0) {
            return;
        }
        
        switch (rewardType) {
            case BONUS_STAMPS:
                if (shopSettingsService.isStampsEnabled()) {
                    stampWalletService.addStamps(user, rewardValue);
                    log.info("Applied {} bonus stamps to user {}", rewardValue, user.getChatId());
                }
                break;
                
            case BONUS_POINTS:
                // TODO: реализовать систему баллов
                log.info("Applied {} bonus points to user {}", rewardValue, user.getChatId());
                break;
                
            case DISCOUNT_PERCENT:
            case DISCOUNT_FIXED:
                // TODO: создать персональный промо-код
                log.info("Created discount reward for user {}", user.getChatId());
                break;
                
            default:
                break;
        }
        
        customerAchievement.setRewardApplied(true);
        customerAchievement.setRewardAppliedAt(LocalDateTime.now());
        customerAchievementRepository.save(customerAchievement);
    }
    
    private String createMetadata(Double amount, boolean isFastCheckout) {
        try {
            Map<String, Object> meta = new HashMap<>();
            if (amount != null) {
                meta.put("amount", amount);
            }
            meta.put("fastCheckout", isFastCheckout);
            meta.put("timestamp", LocalDateTime.now().toString());
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
    
    // ========== Методы для отображения ==========
    
    /**
     * Получает все ачивки пользователя
     */
    public List<CustomerAchievement> getUserAchievements(User user) {
        return customerAchievementRepository.findByUserOrderByAwardedAtDesc(user);
    }
    
    /**
     * Получает все доступные ачивки
     */
    public List<AchievementDefinition> getAllAchievements() {
        return achievementDefinitionRepository.findByIsActiveTrueOrderByDisplayOrderDesc();
    }
    
    /**
     * Возвращает информацию об ачивках для отображения клиенту
     */
    public String getAchievementsDisplay(User user) {
        List<CustomerAchievement> userAchievements = getUserAchievements(user);
        List<AchievementDefinition> allAchievements = getAllAchievements();
        
        Set<Long> earnedIds = new HashSet<>();
        for (CustomerAchievement ca : userAchievements) {
            earnedIds.add(ca.getAchievement().getId());
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("🏆 *Ваши достижения*\n\n");
        
        if (userAchievements.isEmpty()) {
            sb.append("У вас пока нет достижений.\n");
            sb.append("Совершайте покупки, чтобы получать ачивки!\n\n");
        } else {
            sb.append("*Полученные:*\n");
            for (CustomerAchievement ca : userAchievements) {
                AchievementDefinition def = ca.getAchievement();
                sb.append(def.getEmoji()).append(" ").append(def.getTitle()).append("\n");
            }
            sb.append("\n");
        }
        
        // Показываем доступные ачивки
        List<AchievementDefinition> available = allAchievements.stream()
            .filter(a -> !earnedIds.contains(a.getId()) || 
                        (a.getMaxAwardsPerCustomer() == null || a.getMaxAwardsPerCustomer() > 1))
            .limit(5)
            .toList();
        
        if (!available.isEmpty()) {
            sb.append("*Доступные:*\n");
            for (AchievementDefinition def : available) {
                sb.append("○ ").append(def.getTitle());
                if (def.getDescription() != null) {
                    sb.append(" — _").append(def.getDescription()).append("_");
                }
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Создаёт ачивку по умолчанию (для инициализации)
     */
    @Transactional
    public AchievementDefinition createDefaultAchievement(
            String title, String description, String emoji,
            AchievementDefinition.TriggerType triggerType, Integer triggerValue) {
        
        AchievementDefinition achievement = AchievementDefinition.builder()
            .title(title)
            .description(description)
            .emoji(emoji)
            .triggerType(triggerType)
            .triggerValueInt(triggerValue)
            .rewardType(AchievementDefinition.RewardType.NONE)
            .isActive(true)
            .maxAwardsPerCustomer(1)
            .build();
        
        return achievementDefinitionRepository.save(achievement);
    }
    
    /**
     * Помечает ачивку как просмотренную (уведомление отправлено)
     */
    @Transactional
    public void markNotificationSent(CustomerAchievement achievement) {
        achievement.setNotificationSent(true);
        customerAchievementRepository.save(achievement);
    }
}



