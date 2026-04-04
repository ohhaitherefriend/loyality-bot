package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.repository.ShopSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для работы с настройками магазина.
 * Поддерживает multi-tenant режим (по shopId) и single-tenant для обратной совместимости.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShopSettingsService {
    
    private final ShopSettingsRepository shopSettingsRepository;
    
    // Кэш настроек для быстрого доступа (для single-tenant режима)
    private volatile ShopSettings cachedSettings;
    
    // Кэш настроек по shopId (для multi-tenant режима)
    private final java.util.concurrent.ConcurrentHashMap<String, ShopSettings> settingsCache = 
        new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Инициализация настроек по умолчанию при запуске (для обратной совместимости)
     */
    @PostConstruct
    @Transactional
    public void init() {
        getSettings(); // Создаст запись если её нет (single-tenant fallback)
        log.info("Shop settings initialized");
    }
    
    /**
     * Получает настройки магазина по shopId (multi-tenant режим)
     */
    @Transactional(readOnly = true)
    public ShopSettings getSettings(String shopId) {
        if (shopId == null) {
            return getSettings(); // Fallback to single-tenant
        }
        
        return settingsCache.computeIfAbsent(shopId, sid -> 
            shopSettingsRepository.findByShopId(sid)
                .orElseGet(this::getSettings) // Fallback to default settings
        );
    }
    
    /**
     * Получает настройки магазина (single-tenant режим, для обратной совместимости)
     */
    @Transactional
    public ShopSettings getSettings() {
        if (cachedSettings != null) {
            return cachedSettings;
        }
        
        ShopSettings settings = shopSettingsRepository.findFirstByOrderByIdAsc()
            .orElseGet(() -> {
                log.info("Creating default shop settings (all features disabled by default)");
                ShopSettings defaultSettings = ShopSettings.builder()
                    .shopName("Магазин")
                    .discountTiersEnabled(false)  // По умолчанию выключено
                    .fastCheckoutEnabled(false)   // По умолчанию выключено
                    .stampsEnabled(false)         // По умолчанию выключено
                    .build();
                return shopSettingsRepository.save(defaultSettings);
            });
        
        cachedSettings = settings;
        return settings;
    }
    
    /**
     * Создаёт настройки для нового магазина (multi-tenant режим)
     */
    @Transactional
    public ShopSettings createSettings(ShopSettings settings) {
        if (settings.getShopId() == null) {
            throw new IllegalArgumentException("shopId is required for multi-tenant settings");
        }
        
        if (shopSettingsRepository.existsByShopId(settings.getShopId())) {
            throw new IllegalStateException("Settings already exist for shopId: " + settings.getShopId());
        }
        
        ShopSettings saved = shopSettingsRepository.save(settings);
        settingsCache.put(settings.getShopId(), saved);
        log.info("Shop settings created for shopId={}", settings.getShopId());
        return saved;
    }
    
    /**
     * Обновляет настройки магазина
     */
    @Transactional
    public ShopSettings updateSettings(ShopSettings settings) {
        ShopSettings saved = shopSettingsRepository.save(settings);
        
        // Обновляем кэши
        if (settings.getShopId() != null) {
            settingsCache.put(settings.getShopId(), saved);
        } else {
            cachedSettings = saved;
        }
        
        log.info("Shop settings updated: id={}, shopId={}", saved.getId(), saved.getShopId());
        return saved;
    }
    
    /**
     * Обновляет настройки по shopId
     */
    @Transactional
    public ShopSettings updateSettings(String shopId, ShopSettings settings) {
        ShopSettings existing = shopSettingsRepository.findByShopId(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Settings not found for shopId: " + shopId));
        
        // Копируем настройки, сохраняя id и shopId
        settings.setId(existing.getId());
        settings.setShopId(shopId);
        
        return updateSettings(settings);
    }
    
    /**
     * Сбрасывает кэш (для тестов или после изменений через БД)
     */
    public void clearCache() {
        cachedSettings = null;
        settingsCache.clear();
    }
    
    /**
     * Сбрасывает кэш для конкретного shopId
     */
    public void clearCache(String shopId) {
        if (shopId != null) {
            settingsCache.remove(shopId);
        } else {
            cachedSettings = null;
        }
    }
    
    // ========== Быстрые методы доступа к настройкам ==========
    
    public boolean isDiscountTiersEnabled() {
        return Boolean.TRUE.equals(getSettings().getDiscountTiersEnabled());
    }
    
    public boolean isFastCheckoutEnabled() {
        return Boolean.TRUE.equals(getSettings().getFastCheckoutEnabled());
    }
    
    public boolean isStampsEnabled() {
        return Boolean.TRUE.equals(getSettings().getStampsEnabled());
    }
    
    public ShopSettings.FastCheckoutType getFastCheckoutType() {
        return getSettings().getFastCheckoutType();
    }
    
    public int getFastCheckoutValue() {
        Integer value = getSettings().getFastCheckoutValue();
        return value != null ? value : 1;
    }
    
    public int getFastCheckoutCooldownMinutes() {
        Integer value = getSettings().getFastCheckoutCooldownMinutes();
        return value != null ? value : 5;
    }
    
    public int getFastCheckoutDailyLimit() {
        Integer value = getSettings().getFastCheckoutDailyLimitPerCustomer();
        return value != null ? value : 10;
    }
    
    public int getStampsPerFastPurchase() {
        Integer value = getSettings().getStampsPerFastPurchase();
        return value != null ? value : 1;
    }
    
    public int getStampsRequiredForReward() {
        Integer value = getSettings().getStampsRequiredForReward();
        return value != null ? value : 10;
    }
    
    public String getRewardTitle() {
        String value = getSettings().getRewardTitle();
        return value != null ? value : "Бесплатный напиток";
    }
    
    public String getRewardDescription() {
        return getSettings().getRewardDescription();
    }
    
    public boolean isRedeemRequiresCashierConfirm() {
        return Boolean.TRUE.equals(getSettings().getRedeemRequiresCashierConfirm());
    }
    
    public int getRedeemCodeTtlMinutes() {
        Integer value = getSettings().getRedeemCodeTtlMinutes();
        return value != null ? value : 10;
    }
    
    public int getRegularThresholdPurchases() {
        Integer value = getSettings().getRegularThresholdPurchases();
        return value != null ? value : 3;
    }
    
    public int getVipThresholdPurchases() {
        Integer value = getSettings().getVipThresholdPurchases();
        return value != null ? value : 10;
    }
    
    public Double getVipThresholdTotalSpend() {
        return getSettings().getVipThresholdTotalSpend();
    }
    
    public int getLostDaysSinceLastPurchase() {
        Integer value = getSettings().getLostDaysSinceLastPurchase();
        return value != null ? value : 30;
    }
    
    public boolean isAutoMessagesEnabled() {
        return Boolean.TRUE.equals(getSettings().getAutoMessagesEnabled());
    }
    
    public int getAutoMessagesDailyLimit() {
        Integer value = getSettings().getAutoMessagesDailyLimitPerCustomer();
        return value != null ? value : 3;
    }
    
    public String getDefaultLocationId() {
        return getSettings().getDefaultLocationId();
    }
    
    public String getTelegramChannelUrl() {
        return getSettings().getTelegramChannelUrl();
    }
    
    public String getChannelUrl() {
        return getTelegramChannelUrl();
    }
    
    // ========== Методы для скидок ==========
    
    public int getDiscountValidityDays() {
        Integer value = getSettings().getDiscountValidityDays();
        return value != null ? value : 30;
    }
    
    /**
     * Рассчитывает уровень скидки на основе накопленной суммы
     */
    public Integer calculateDiscountLevel(double amount) {
        return getSettings().calculateDiscountLevel(amount);
    }
    
    /**
     * Возвращает минимальную сумму для заданного уровня скидки
     */
    public Double getRequiredAmountForDiscount(Integer discountPercent) {
        return getSettings().getRequiredAmountForDiscount(discountPercent);
    }
    
    /**
     * Возвращает текстовое описание системы скидок
     */
    public String getDiscountTiersDescription() {
        return getSettings().getDiscountTiersDescription();
    }
    
    /**
     * Возвращает информацию о прогрессе накопления скидки
     */
    public String getDiscountProgressInfo(double accumulatedAmount, Integer currentDiscountLevel, boolean isDiscountValid) {
        return getSettings().getDiscountProgressInfo(accumulatedAmount, currentDiscountLevel, isDiscountValid);
    }
    
    // ========== Методы включения функций ==========
    
    /**
     * Включает режим кофейни (fast checkout + штампы)
     */
    @Transactional
    public ShopSettings enableCoffeeShopMode() {
        ShopSettings settings = getSettings();
        settings.setFastCheckoutEnabled(true);
        settings.setStampsEnabled(true);
        settings.setFastCheckoutType(ShopSettings.FastCheckoutType.STAMP);
        settings.setFastCheckoutValue(1);
        settings.setStampsPerFastPurchase(1);
        settings.setStampsRequiredForReward(10);
        settings.setRewardTitle("Бесплатный напиток");
        settings.setRewardDescription("Любой напиток на ваш выбор");
        return updateSettings(settings);
    }
    
    /**
     * Включает режим магазина (накопительные скидки)
     */
    @Transactional
    public ShopSettings enableShopMode() {
        ShopSettings settings = getSettings();
        settings.setDiscountTiersEnabled(true);
        settings.setFastCheckoutEnabled(false);
        settings.setStampsEnabled(false);
        return updateSettings(settings);
    }
    
    /**
     * Включает гибридный режим (скидки + штампы)
     */
    @Transactional
    public ShopSettings enableHybridMode() {
        ShopSettings settings = getSettings();
        settings.setDiscountTiersEnabled(true);
        settings.setFastCheckoutEnabled(true);
        settings.setStampsEnabled(true);
        return updateSettings(settings);
    }
    
    // ========== Multi-tenant методы (перегрузки с shopId) ==========
    
    public boolean isDiscountTiersEnabled(String shopId) {
        return Boolean.TRUE.equals(getSettings(shopId).getDiscountTiersEnabled());
    }
    
    public boolean isFastCheckoutEnabled(String shopId) {
        return Boolean.TRUE.equals(getSettings(shopId).getFastCheckoutEnabled());
    }
    
    public boolean isStampsEnabled(String shopId) {
        return Boolean.TRUE.equals(getSettings(shopId).getStampsEnabled());
    }
    
    public ShopSettings.FastCheckoutType getFastCheckoutType(String shopId) {
        return getSettings(shopId).getFastCheckoutType();
    }
    
    public int getFastCheckoutValue(String shopId) {
        Integer value = getSettings(shopId).getFastCheckoutValue();
        return value != null ? value : 1;
    }
    
    public int getFastCheckoutCooldownMinutes(String shopId) {
        Integer value = getSettings(shopId).getFastCheckoutCooldownMinutes();
        return value != null ? value : 5;
    }
    
    public int getFastCheckoutDailyLimit(String shopId) {
        Integer value = getSettings(shopId).getFastCheckoutDailyLimitPerCustomer();
        return value != null ? value : 10;
    }
    
    public int getStampsPerFastPurchase(String shopId) {
        Integer value = getSettings(shopId).getStampsPerFastPurchase();
        return value != null ? value : 1;
    }
    
    public int getStampsRequiredForReward(String shopId) {
        Integer value = getSettings(shopId).getStampsRequiredForReward();
        return value != null ? value : 10;
    }
    
    public String getRewardTitle(String shopId) {
        String value = getSettings(shopId).getRewardTitle();
        return value != null ? value : "Бесплатный напиток";
    }
    
    public String getRewardDescription(String shopId) {
        return getSettings(shopId).getRewardDescription();
    }
    
    public boolean isRedeemRequiresCashierConfirm(String shopId) {
        return Boolean.TRUE.equals(getSettings(shopId).getRedeemRequiresCashierConfirm());
    }
    
    public int getRedeemCodeTtlMinutes(String shopId) {
        Integer value = getSettings(shopId).getRedeemCodeTtlMinutes();
        return value != null ? value : 10;
    }
    
    public int getRegularThresholdPurchases(String shopId) {
        Integer value = getSettings(shopId).getRegularThresholdPurchases();
        return value != null ? value : 3;
    }
    
    public int getVipThresholdPurchases(String shopId) {
        Integer value = getSettings(shopId).getVipThresholdPurchases();
        return value != null ? value : 10;
    }
    
    public Double getVipThresholdTotalSpend(String shopId) {
        return getSettings(shopId).getVipThresholdTotalSpend();
    }
    
    public int getLostDaysSinceLastPurchase(String shopId) {
        Integer value = getSettings(shopId).getLostDaysSinceLastPurchase();
        return value != null ? value : 30;
    }
    
    public boolean isAutoMessagesEnabled(String shopId) {
        return Boolean.TRUE.equals(getSettings(shopId).getAutoMessagesEnabled());
    }
    
    public int getAutoMessagesDailyLimit(String shopId) {
        Integer value = getSettings(shopId).getAutoMessagesDailyLimitPerCustomer();
        return value != null ? value : 3;
    }
    
    public String getDefaultLocationId(String shopId) {
        return getSettings(shopId).getDefaultLocationId();
    }
    
    public String getChannelUrl(String shopId) {
        return getSettings(shopId).getTelegramChannelUrl();
    }
    
    public int getDiscountValidityDays(String shopId) {
        Integer value = getSettings(shopId).getDiscountValidityDays();
        return value != null ? value : 30;
    }
    
    public Integer calculateDiscountLevel(String shopId, double amount) {
        return getSettings(shopId).calculateDiscountLevel(amount);
    }
    
    public Double getRequiredAmountForDiscount(String shopId, Integer discountPercent) {
        return getSettings(shopId).getRequiredAmountForDiscount(discountPercent);
    }
    
    public String getDiscountTiersDescription(String shopId) {
        return getSettings(shopId).getDiscountTiersDescription();
    }
    
    // ========== Постоянная скидка (multi-tenant) ==========
    
    public boolean isPermanentDiscountEnabled(String shopId) {
        return Boolean.TRUE.equals(getSettings(shopId).getPermanentDiscountEnabled());
    }
    
    public Integer calculatePermanentDiscountLevel(String shopId, double totalSpend) {
        return getSettings(shopId).calculatePermanentDiscountLevel(totalSpend);
    }
    
    public String getPermanentDiscountDescription(String shopId) {
        return getSettings(shopId).getPermanentDiscountDescription();
    }
    
    public String getPermanentDiscountProgressInfo(String shopId, double totalSpend) {
        return getSettings(shopId).getPermanentDiscountProgressInfo(totalSpend);
    }
}

