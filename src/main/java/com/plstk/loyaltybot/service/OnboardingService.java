package com.plstk.loyaltybot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.*;
import com.plstk.loyaltybot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис для управления онбордингом.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingService {
    
    private final OnboardingStateRepository onboardingStateRepository;
    private final ShopRepository shopRepository;
    private final ShopMemberRepository shopMemberRepository;
    private final BotInstanceService botInstanceService;
    private final SubscriptionService subscriptionService;
    private final ShopSettingsService shopSettingsService;
    private final BotInstanceRepository botInstanceRepository;
    private final ShopSettingsRepository shopSettingsRepository;
    private final ObjectMapper objectMapper;
    
    @Value("${billing.trial-days:7}")
    private int trialDays;
    
    /**
     * Начинает или возобновляет онбординг для пользователя
     */
    @Transactional
    public OnboardingState startOnboarding(Long userId) {
        log.info("Starting onboarding for userId={}", userId);
        
        Optional<OnboardingState> existingOpt = onboardingStateRepository.findActiveByUserId(userId);
        
        if (existingOpt.isPresent()) {
            log.info("Resuming existing onboarding for userId={}", userId);
            return existingOpt.get();
        }
        
        OnboardingState state = OnboardingState.createForUser(userId);
        state = onboardingStateRepository.save(state);
        
        log.info("Onboarding started: id={}, userId={}", state.getId(), userId);
        return state;
    }
    
    /**
     * Получает текущее состояние онбординга
     */
    public Optional<OnboardingState> getOnboardingState(Long userId) {
        return onboardingStateRepository.findActiveByUserId(userId);
    }
    
    /**
     * Создаёт магазин на шаге CREATE_SHOP
     */
    @Transactional
    public CreateShopResult createShop(Long userId, String name, String timezone, String templateType,
                                       LoyaltyMode loyaltyMode, Integer stampsRequiredForReward,
                                       String rewardTitle, Integer bonusPercent) {
        log.info("Creating shop in onboarding: userId={}, name={}, template={}, loyaltyMode={}",
            userId, name, templateType, loyaltyMode);
        
        OnboardingState state = onboardingStateRepository.findActiveByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("No active onboarding for user"));
        
        // Создаём Shop
        String shopId = UUID.randomUUID().toString();
        Shop shop = Shop.builder()
            .shopId(shopId)
            .name(name != null && !name.isBlank() ? name : "Мой магазин")
            .timezone(timezone != null && !timezone.isBlank() ? timezone : "Europe/Moscow")
            .ownerId(userId)
            .build();
        
        shop = shopRepository.save(shop);
        
        // Добавляем пользователя как OWNER
        ShopMember member = ShopMember.builder()
            .userId(userId)
            .shopId(shopId)
            .role(ShopMember.MemberRole.OWNER)
            .build();
        shopMemberRepository.save(member);
        
        // Создаём trial подписку
        subscriptionService.createTrialSubscription(shopId);
        
        // Обновляем состояние онбординга
        state.setShopId(shopId);
        state.setStep(OnboardingState.OnboardingStep.SHOP_CREATED);
        state.setDataJson(serializeDraft(new OnboardingDraft(
            resolveLoyaltyMode(loyaltyMode, templateType),
            stampsRequiredForReward,
            rewardTitle,
            bonusPercent,
            templateType
        )));
        onboardingStateRepository.save(state);
        
        log.info("Shop created in onboarding: shopId={}, userId={}", shopId, userId);
        
        return new CreateShopResult(shop, shopId, state);
    }
    
    public record CreateShopResult(Shop shop, String shopId, OnboardingState state) {}
    
    /**
     * Подключает бота на шаге CONNECT_BOT (по умолчанию Telegram)
     */
    @Transactional
    public ConnectBotResult connectBot(Long userId, String shopId, String botToken) {
        return connectBot(userId, shopId, botToken, MessengerPlatform.TELEGRAM);
    }

    /**
     * Подключает бота на шаге CONNECT_BOT с указанием платформы
     */
    @Transactional
    public ConnectBotResult connectBot(Long userId, String shopId, String botToken, MessengerPlatform platform) {
        log.info("Connecting {} bot in onboarding: userId={}, shopId={}", platform, userId, shopId);
        
        OnboardingState state = onboardingStateRepository.findActiveByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("No active onboarding for user"));
        
        if (!shopId.equals(state.getShopId())) {
            throw new IllegalArgumentException("Shop ID mismatch");
        }
        
        OnboardingDraft draft = parseDraft(state.getDataJson());
        LoyaltyMode loyaltyMode = resolveLoyaltyMode(draft, state.getDataJson());
        BotInstance.BusinessType businessType = mapBusinessType(loyaltyMode, draft);
        
        Shop shop = shopRepository.findByShopId(shopId).orElse(null);
        String businessName = shop != null ? shop.getName() : null;
        
        BotInstanceService.ConnectResult result = connectBotToExistingShop(
            botToken, shopId, businessType, businessName, platform
        );
        
        if (!result.success()) {
            return new ConnectBotResult(false, null, null, null, result.error(), state);
        }
        
        applyLoyaltyDefaults(shopId, loyaltyMode, draft);
        
        state.setStep(OnboardingState.OnboardingStep.BOT_CONNECTED);
        onboardingStateRepository.save(state);
        
        log.info("Bot connected in onboarding: shopId={}, botUsername={}, platform={}", 
            shopId, result.botUsername(), platform);
        
        return new ConnectBotResult(true, result.botInstance(), result.buyDeepLink(), result.adminDeepLink(), null, state);
    }
    
    /**
     * Подключает бота к существующему shopId (не создаёт новый). Поддерживает Telegram и Max.
     */
    private BotInstanceService.ConnectResult connectBotToExistingShop(
            String botToken, 
            String existingShopId,
            BotInstance.BusinessType businessType,
            String businessName,
            MessengerPlatform platform) {
        BotInstanceService.ConnectResult result;
        if (platform == MessengerPlatform.MAX) {
            result = botInstanceService.connectMaxBot(botToken, businessType, businessName, null);
        } else {
            result = botInstanceService.connectBot(botToken, businessType, businessName, null);
        }
        
        if (result.success()) {
            BotInstance bot = result.botInstance();
            String generatedShopId = bot.getShopId();
            
            bot.setShopId(existingShopId);
            botInstanceRepository.save(bot);
            
            shopSettingsRepository.findByShopId(generatedShopId).ifPresent(settings -> {
                settings.setShopId(existingShopId);
                shopSettingsRepository.save(settings);
            });
            
            log.info("Updated bot {} and settings shopId from {} to {}", 
                bot.getId(), generatedShopId, existingShopId);
        }
        
        return result;
    }
    
    public record ConnectBotResult(
        boolean success, 
        BotInstance botInstance, 
        String buyDeepLink,
        String adminDeepLink,
        String error, 
        OnboardingState state
    ) {}
    
    /**
     * Применяет шаблон настроек
     */
    @Transactional
    public OnboardingState applyTemplate(Long userId, String shopId, String templateType) {
        log.info("Applying template in onboarding: userId={}, shopId={}, template={}", userId, shopId, templateType);
        
        OnboardingState state = onboardingStateRepository.findActiveByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("No active onboarding for user"));
        
        // Настройки уже применяются при подключении бота в BotInstanceService
        // Здесь можно добавить дополнительную кастомизацию
        
        state.setStep(OnboardingState.OnboardingStep.SETTINGS_DONE);
        state = onboardingStateRepository.save(state);
        
        log.info("Template applied in onboarding: shopId={}", shopId);
        return state;
    }
    
    /**
     * Завершает онбординг
     */
    @Transactional
    public OnboardingState completeOnboarding(Long userId, String shopId) {
        log.info("Completing onboarding: userId={}, shopId={}", userId, shopId);
        
        OnboardingState state = onboardingStateRepository.findActiveByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("No active onboarding for user"));
        
        state.setStep(OnboardingState.OnboardingStep.COMPLETED);
        state.setCompleted(true);
        state = onboardingStateRepository.save(state);
        
        log.info("Onboarding completed: userId={}, shopId={}", userId, shopId);
        return state;
    }
    
    private LoyaltyMode resolveLoyaltyMode(LoyaltyMode loyaltyMode, String templateType) {
        if (loyaltyMode != null) {
            return loyaltyMode;
        }
        return mapTemplateToLoyaltyMode(templateType);
    }
    
    private LoyaltyMode resolveLoyaltyMode(OnboardingDraft draft, String dataJson) {
        if (draft != null && draft.loyaltyMode() != null) {
            return draft.loyaltyMode();
        }
        if (draft != null && draft.templateType() != null) {
            return mapTemplateToLoyaltyMode(draft.templateType());
        }
        if (dataJson != null && dataJson.contains("RETAIL")) {
            return LoyaltyMode.BONUS;
        }
        return LoyaltyMode.STAMPS;
    }
    
    private LoyaltyMode mapTemplateToLoyaltyMode(String templateType) {
        if (templateType == null) {
            return LoyaltyMode.STAMPS;
        }
        if (templateType.contains("RETAIL")) {
            return LoyaltyMode.BONUS;
        }
        return LoyaltyMode.STAMPS;
    }
    
    private BotInstance.BusinessType mapBusinessType(LoyaltyMode loyaltyMode, OnboardingDraft draft) {
        if (loyaltyMode == LoyaltyMode.BONUS || loyaltyMode == LoyaltyMode.CUMULATIVE_DISCOUNT) {
            return BotInstance.BusinessType.RETAIL;
        }
        if (draft != null && "SERVICE".equalsIgnoreCase(draft.templateType())) {
            return BotInstance.BusinessType.SERVICE;
        }
        return BotInstance.BusinessType.COFFEE;
    }
    
    private void applyLoyaltyDefaults(String shopId, LoyaltyMode loyaltyMode, OnboardingDraft draft) {
        ShopSettings settings = shopSettingsRepository.findByShopId(shopId).orElse(null);
        if (settings == null) {
            log.warn("No ShopSettings found for shopId={}, skipping loyalty defaults", shopId);
            return;
        }
        
        switch (loyaltyMode) {
            case STAMPS -> {
                settings.setStampsEnabled(true);
                settings.setDiscountTiersEnabled(false);
                settings.setFastCheckoutEnabled(true);
                settings.setFastCheckoutType(ShopSettings.FastCheckoutType.STAMP);
                settings.setFastCheckoutValue(1);
                settings.setStampsPerFastPurchase(1);
                settings.setStampsRequiredForReward(
                    draft != null && draft.stampsRequiredForReward() != null
                        ? draft.stampsRequiredForReward()
                        : 6
                );
                if (draft != null && draft.rewardTitle() != null && !draft.rewardTitle().isBlank()) {
                    settings.setRewardTitle(draft.rewardTitle().trim());
                }
            }
            case BONUS -> {
                settings.setStampsEnabled(false);
                settings.setFastCheckoutEnabled(false);
                settings.setDiscountTiersEnabled(false);
                settings.setBonusPointsEnabled(true);
                settings.setBonusCashbackPercent(
                    draft != null && draft.bonusPercent() != null ? draft.bonusPercent() : 5
                );
                settings.setBonusMaxSpendPercent(100);
            }
            case CUMULATIVE_DISCOUNT -> {
                settings.setStampsEnabled(false);
                settings.setFastCheckoutEnabled(false);
                settings.setDiscountTiersEnabled(false);
                settings.setPermanentDiscountEnabled(true);
                
                int basePercent = draft != null && draft.bonusPercent() != null ? draft.bonusPercent() : 1;
                var defaultTiers = List.of(
                    new ShopSettings.DiscountTier(50000.0, basePercent),
                    new ShopSettings.DiscountTier(100000.0, basePercent * 2),
                    new ShopSettings.DiscountTier(200000.0, basePercent * 3)
                );
                settings.setDiscountTiersList(defaultTiers);
            }
        }
        
        shopSettingsService.updateSettings(settings);
    }
    
    private String serializeDraft(OnboardingDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize onboarding draft", e);
            return null;
        }
    }
    
    private OnboardingDraft parseDraft(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(dataJson, OnboardingDraft.class);
        } catch (Exception e) {
            log.warn("Failed to parse onboarding draft: {}", dataJson, e);
            return null;
        }
    }
    
    private record OnboardingDraft(
        LoyaltyMode loyaltyMode,
        Integer stampsRequiredForReward,
        String rewardTitle,
        Integer bonusPercent,
        String templateType
    ) {}
}
