package com.plstk.loyaltybot.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Настройки магазина.
 * Multi-tenant: каждый магазин (shopId) имеет свои настройки.
 * Содержит все настройки для fast checkout, штампов, статусов клиентов.
 */
@Entity
@Table(name = "shop_settings", indexes = {
    @Index(name = "idx_shop_settings_shop_id", columnList = "shopId", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class ShopSettings {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Уникальный идентификатор магазина (для multi-tenancy).
     * Связывает настройки с BotInstance.
     */
    @Column(unique = true, length = 36)
    private String shopId;
    
    // ========== Основные настройки ==========
    
    @Column(nullable = false)
    @Builder.Default
    private String shopName = "Магазин";
    
    @Column
    private String defaultLocationId;
    
    @Column
    private String telegramChannelUrl;
    
    // ========== Накопительная скидка (настраиваемая система) ==========
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean discountTiersEnabled = false;  // По умолчанию выключено
    
    /**
     * Порог для скидки уровня 1 (по умолчанию 20,000 руб → 5%)
     */
    @Column(nullable = false)
    @Builder.Default
    private Double discountTier1Amount = 20000.0;
    
    @Column(nullable = false)
    @Builder.Default
    private Integer discountTier1Percent = 5;
    
    /**
     * Порог для скидки уровня 2 (по умолчанию 25,000 руб → 7%)
     */
    @Column(nullable = false)
    @Builder.Default
    private Double discountTier2Amount = 25000.0;
    
    @Column(nullable = false)
    @Builder.Default
    private Integer discountTier2Percent = 7;
    
    /**
     * Порог для скидки уровня 3 (по умолчанию 30,000 руб → 10%)
     */
    @Column(nullable = false)
    @Builder.Default
    private Double discountTier3Amount = 30000.0;
    
    @Column(nullable = false)
    @Builder.Default
    private Integer discountTier3Percent = 10;
    
    /**
     * Срок действия скидки в днях
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer discountValidityDays = 30;
    
    // ========== Постоянная накопительная скидка (не сгорает) ==========
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean permanentDiscountEnabled = false;
    
    /**
     * JSON-массив уровней постоянной скидки.
     * Формат: [{"amount":50000,"percent":1},{"amount":100000,"percent":2}]
     */
    @Column(columnDefinition = "TEXT")
    private String permanentDiscountTiers;
    
    // ========== Fast Checkout ==========
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean fastCheckoutEnabled = false;
    
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private FastCheckoutType fastCheckoutType = FastCheckoutType.STAMP;
    
    /**
     * Значение награды за fast checkout:
     * - для STAMP: количество штампов (обычно 1)
     * - для FIXED_POINTS: количество баллов
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer fastCheckoutValue = 1;
    
    /**
     * Минимальный интервал между fast checkout для одного клиента (в минутах)
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer fastCheckoutCooldownMinutes = 5;
    
    /**
     * Максимум fast checkout в день на одного клиента
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer fastCheckoutDailyLimitPerCustomer = 10;
    
    // ========== Штампы (Stamp Wallet) ==========
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean stampsEnabled = false;  // По умолчанию выключено
    
    /**
     * Сколько штампов начисляется за одну fast-покупку
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer stampsPerFastPurchase = 1;
    
    /**
     * Сколько штампов нужно для награды
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer stampsRequiredForReward = 10;
    
    @Column(nullable = false)
    @Builder.Default
    private String rewardTitle = "Бесплатный напиток";
    
    @Column
    @Builder.Default
    private String rewardDescription = "Любой напиток на ваш выбор";
    
    /**
     * Требуется ли подтверждение кассира для погашения награды
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean redeemRequiresCashierConfirm = true;
    
    /**
     * TTL кода погашения в минутах
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer redeemCodeTtlMinutes = 10;
    
    // ========== Балльная система (кэшбек) ==========
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean bonusPointsEnabled = false;
    
    /**
     * Процент кэшбека: сколько % от покупки возвращается баллами
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer bonusCashbackPercent = 5;
    
    /**
     * Максимум оплаты баллами: какую часть покупки можно оплатить (100 = без ограничений)
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer bonusMaxSpendPercent = 100;
    
    // ========== Статусы клиентов ==========
    
    /**
     * Количество покупок для статуса REGULAR
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer regularThresholdPurchases = 3;
    
    /**
     * Количество покупок для статуса VIP
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer vipThresholdPurchases = 10;
    
    /**
     * Опционально: минимальная сумма для VIP
     */
    @Column
    private Double vipThresholdTotalSpend;
    
    /**
     * Дней без покупок для статуса LOST
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer lostDaysSinceLastPurchase = 30;
    
    // ========== Авто-сообщения ==========
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean autoMessagesEnabled = true;
    
    /**
     * Максимум авто-сообщений одному клиенту в день
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer autoMessagesDailyLimitPerCustomer = 3;
    
    // ========== Кастомные сообщения ==========
    
    /**
     * Приветственное сообщение при /start.
     * Переменные: {shopName}, {userName}
     */
    @Column(columnDefinition = "TEXT")
    private String welcomeMessage;
    
    /**
     * Сообщение с кодом покупки.
     * Переменные: {code}, {userName}, {shopName}
     */
    @Column(columnDefinition = "TEXT")
    private String purchaseCodeMessage;
    
    /**
     * Сообщение при получении штампа.
     * Переменные: {stamps}, {stampsLeft}, {reward}, {userName}
     */
    @Column(columnDefinition = "TEXT")
    private String stampEarnedMessage;
    
    /**
     * Сообщение при получении награды.
     * Переменные: {reward}, {userName}, {code}
     */
    @Column(columnDefinition = "TEXT")
    private String rewardEarnedMessage;
    
    // ========== Методы для получения сообщений с дефолтами ==========
    
    public String getWelcomeMessageOrDefault() {
        if (welcomeMessage != null && !welcomeMessage.isBlank()) {
            return welcomeMessage;
        }
        return "👋 Добро пожаловать в программу лояльности {shopName}!\n\n" +
               "Для регистрации отправьте свой номер телефона.";
    }
    
    public String getPurchaseCodeMessageOrDefault() {
        if (purchaseCodeMessage != null && !purchaseCodeMessage.isBlank()) {
            return purchaseCodeMessage;
        }
        return "🛍 Код для покупки создан!\n\n" +
               "📋 Ваш код: *{code}*\n\n" +
               "⏱ Покажите этот код кассиру в течение 10 минут.";
    }
    
    public String getStampEarnedMessageOrDefault() {
        if (stampEarnedMessage != null && !stampEarnedMessage.isBlank()) {
            return stampEarnedMessage;
        }
        return "☕ +1 штамп!\n\n" +
               "📊 Всего штампов: {stamps}\n" +
               "До награды: {stampsLeft}\n\n" +
               "🎁 Награда: {reward}";
    }
    
    public String getRewardEarnedMessageOrDefault() {
        if (rewardEarnedMessage != null && !rewardEarnedMessage.isBlank()) {
            return rewardEarnedMessage;
        }
        return "🎉 Поздравляем! Вы получили награду!\n\n" +
               "🎁 {reward}\n\n" +
               "📋 Код для получения: *{code}*\n\n" +
               "Покажите этот код кассиру.";
    }
    
    // ========== Метаданные ==========
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * Тип награды за fast checkout
     */
    public enum FastCheckoutType {
        STAMP,        // Начисление штампа
        FIXED_POINTS  // Начисление фиксированных баллов
    }
    
    // ========== Вспомогательные методы для скидок ==========
    
    public record DiscountTier(Double amount, Integer percent) {}
    
    /**
     * Возвращает все уровни скидок: из JSON (если есть) или из фиксированных полей (fallback).
     */
    public List<DiscountTier> getDiscountTiersList() {
        if (permanentDiscountTiers != null && !permanentDiscountTiers.isBlank()) {
            try {
                return OBJECT_MAPPER.readValue(permanentDiscountTiers, new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                log.error("Failed to parse discountTiers JSON: {}", permanentDiscountTiers, e);
            }
        }
        List<DiscountTier> fallback = new ArrayList<>();
        if (discountTier1Amount != null && discountTier1Percent != null) {
            fallback.add(new DiscountTier(discountTier1Amount, discountTier1Percent));
        }
        if (discountTier2Amount != null && discountTier2Percent != null) {
            fallback.add(new DiscountTier(discountTier2Amount, discountTier2Percent));
        }
        if (discountTier3Amount != null && discountTier3Percent != null) {
            fallback.add(new DiscountTier(discountTier3Amount, discountTier3Percent));
        }
        return fallback;
    }
    
    public void setDiscountTiersList(List<DiscountTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            this.permanentDiscountTiers = null;
            return;
        }
        try {
            this.permanentDiscountTiers = OBJECT_MAPPER.writeValueAsString(tiers);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize discountTiers to JSON", e);
        }
    }
    
    /**
     * Рассчитывает уровень скидки на основе суммы (из всех уровней).
     */
    public Integer calculateDiscountLevel(double amount) {
        List<DiscountTier> tiers = getDiscountTiersList();
        if (tiers.isEmpty()) {
            return null;
        }
        return tiers.stream()
                .filter(t -> t.amount() != null && t.percent() != null)
                .sorted(Comparator.comparingDouble(DiscountTier::amount).reversed())
                .filter(t -> amount >= t.amount())
                .map(DiscountTier::percent)
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Возвращает минимальную сумму для заданного уровня скидки
     */
    public Double getRequiredAmountForDiscount(Integer discountPercent) {
        List<DiscountTier> tiers = getDiscountTiersList();
        return tiers.stream()
                .filter(t -> t.amount() != null && t.percent() != null)
                .sorted(Comparator.comparingDouble(DiscountTier::amount))
                .filter(t -> discountPercent != null && t.percent() >= discountPercent)
                .map(DiscountTier::amount)
                .findFirst()
                .orElse(tiers.isEmpty() ? 0.0 : tiers.get(0).amount());
    }
    
    public boolean isDiscountPermanent() {
        return discountValidityDays != null && discountValidityDays == 0;
    }
    
    /**
     * Текстовое описание системы скидок для бота.
     */
    public String getDiscountTiersDescription() {
        if (!discountTiersEnabled) {
            return "";
        }
        List<DiscountTier> tiers = getDiscountTiersList();
        if (tiers.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder("💡 Накопительная система скидок:\n\n");
        if (isDiscountPermanent()) {
            sb.append("Скидка НЕ сгорает!\n\n");
        } else {
            sb.append(String.format("Скидка действует %d дней после достижения уровня.\n\n", discountValidityDays));
        }
        
        tiers.stream()
                .filter(t -> t.amount() != null && t.percent() != null)
                .sorted(Comparator.comparingDouble(DiscountTier::amount))
                .forEach(t -> sb.append(String.format("• %d%% скидка — от %.0f руб\n", t.percent(), t.amount())));
        
        return sb.toString();
    }
    
    /**
     * Информация о прогрессе накопления скидки.
     */
    public String getDiscountProgressInfo(double accumulatedAmount, Integer currentDiscountLevel, boolean isDiscountValid) {
        if (!discountTiersEnabled) {
            return "";
        }
        List<DiscountTier> sorted = getDiscountTiersList().stream()
                .filter(t -> t.amount() != null && t.percent() != null)
                .sorted(Comparator.comparingDouble(DiscountTier::amount))
                .toList();
        if (sorted.isEmpty()) {
            return "";
        }
        
        for (DiscountTier tier : sorted) {
            if (accumulatedAmount < tier.amount()) {
                return String.format("До скидки %d%%: %.0f руб.", tier.percent(), tier.amount() - accumulatedAmount);
            }
        }
        DiscountTier maxTier = sorted.get(sorted.size() - 1);
        return String.format("Максимальная скидка %d%% достигнута! 🎉", maxTier.percent());
    }
    
    // Aliases for backward compatibility with code referencing "permanent" methods
    
    /** @deprecated Use getDiscountTiersList() */
    public List<DiscountTier> getPermanentDiscountTiersList() {
        return getDiscountTiersList();
    }
    
    /** @deprecated Use setDiscountTiersList() */
    public void setPermanentDiscountTiersList(List<DiscountTier> tiers) {
        setDiscountTiersList(tiers);
    }
    
    /** @deprecated Use calculateDiscountLevel() */
    public Integer calculatePermanentDiscountLevel(double totalSpend) {
        return calculateDiscountLevel(totalSpend);
    }
    
    /** @deprecated Use getDiscountTiersDescription() */
    public String getPermanentDiscountDescription() {
        return getDiscountTiersDescription();
    }
    
    /** @deprecated Use getDiscountProgressInfo() */
    public String getPermanentDiscountProgressInfo(double totalSpend) {
        return getDiscountProgressInfo(totalSpend, null, false);
    }
}



