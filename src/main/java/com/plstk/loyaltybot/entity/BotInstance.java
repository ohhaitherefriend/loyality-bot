package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Сущность для хранения информации о подключенных ботах (Telegram / Max).
 * Каждый бизнес/магазин подключает свой бот.
 * Один shopId = один BotInstance.
 */
@Entity
@Table(name = "bot_instances", indexes = {
    @Index(name = "idx_bot_instance_shop_id", columnList = "shopId", unique = true),
    @Index(name = "idx_bot_instance_bot_username", columnList = "botUsername")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotInstance {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Уникальный идентификатор магазина/бизнеса.
     * Генерируется при подключении бота.
     */
    @Column(nullable = false, unique = true, length = 36)
    private String shopId;
    
    /**
     * Токен бота от BotFather.
     * Хранится в зашифрованном виде.
     */
    @Column(nullable = false, length = 512)
    private String botToken;
    
    /**
     * Username бота (без @).
     * Получается через getMe API.
     */
    @Column(nullable = false, length = 64)
    private String botUsername;
    
    /**
     * Bot ID на платформе (числовой).
     * Получается через getMe API.
     */
    @Column(nullable = false)
    private Long telegramBotId;
    
    /**
     * Платформа мессенджера (TELEGRAM или MAX).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private MessengerPlatform platform = MessengerPlatform.TELEGRAM;
    
    /**
     * Секрет для валидации webhook запросов.
     * Генерируется при подключении бота.
     */
    @Column(nullable = false, length = 64)
    private String webhookSecret;
    
    /**
     * URL вебхука (полный путь).
     */
    @Column(length = 512)
    private String webhookUrl;
    
    /**
     * Активен ли бот.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    
    /**
     * Статус подключения бота.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BotStatus status = BotStatus.PENDING;
    
    /**
     * Тип шаблона при онбординге.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BusinessType businessType = BusinessType.COFFEE;
    
    /**
     * Название бизнеса (опционально).
     */
    @Column(length = 255)
    private String businessName;
    
    /**
     * Email владельца для уведомлений.
     */
    @Column(length = 255)
    private String ownerEmail;
    
    /**
     * Telegram chatId владельца (для уведомлений).
     */
    private Long ownerChatId;
    
    /**
     * Дата последнего успешного webhook.
     */
    private LocalDateTime lastWebhookAt;
    
    /**
     * Количество обработанных апдейтов.
     */
    @Builder.Default
    private Long updatesProcessed = 0L;
    
    /**
     * Сообщение об ошибке (если есть).
     */
    @Column(length = 1024)
    private String lastError;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (shopId == null) {
            shopId = UUID.randomUUID().toString();
        }
        if (webhookSecret == null) {
            webhookSecret = UUID.randomUUID().toString().replace("-", "");
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * Статус бота
     */
    public enum BotStatus {
        PENDING,      // Ожидает настройки webhook
        CONNECTING,   // В процессе подключения
        ACTIVE,       // Работает
        ERROR,        // Ошибка
        DISABLED      // Отключен
    }
    
    /**
     * Тип бизнеса (влияет на начальные настройки)
     */
    public enum BusinessType {
        COFFEE,    // Кофейня: штампы + fast checkout
        RETAIL,    // Розница: накопительные скидки
        SERVICE,   // Услуги: баллы
        HYBRID     // Гибридный: всё вместе
    }
    
    /**
     * Генерирует URL для webhook в зависимости от платформы
     */
    public String generateWebhookUrl(String baseUrl) {
        if (platform == MessengerPlatform.MAX) {
            return String.format("%s/max/webhook/%s/%s", baseUrl, id, webhookSecret);
        }
        return String.format("%s/tg/webhook/%s/%s", baseUrl, id, webhookSecret);
    }
    
    /**
     * Генерирует deep-link для покупки
     */
    public String generateBuyDeepLink(String locationId) {
        String location = locationId != null ? locationId : "default";
        if (platform == MessengerPlatform.MAX) {
            return String.format("https://max.ru/%s?start=buy_%s_%s", botUsername, shopId, location);
        }
        return String.format("https://t.me/%s?start=buy_%s_%s", botUsername, shopId, location);
    }
    
    /**
     * Генерирует deep-link для регистрации админа
     */
    public String generateAdminDeepLink() {
        if (platform == MessengerPlatform.MAX) {
            return String.format("https://max.ru/%s?start=admin_%s", botUsername, shopId);
        }
        return String.format("https://t.me/%s?start=admin_%s", botUsername, shopId);
    }
    
    /**
     * Увеличивает счётчик обработанных апдейтов
     */
    public void incrementUpdatesProcessed() {
        if (updatesProcessed == null) {
            updatesProcessed = 0L;
        }
        updatesProcessed++;
        lastWebhookAt = LocalDateTime.now();
    }
    
    /**
     * Устанавливает ошибку
     */
    public void setError(String error) {
        this.lastError = error;
        this.status = BotStatus.ERROR;
    }
    
    /**
     * Очищает ошибку и активирует бот
     */
    public void clearError() {
        this.lastError = null;
        this.status = BotStatus.ACTIVE;
    }
}

