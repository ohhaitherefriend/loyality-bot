package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.BotInstance;
import com.plstk.loyaltybot.entity.MessengerPlatform;
import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.max.MaxApiClient;
import com.plstk.loyaltybot.max.MaxContext;
import com.plstk.loyaltybot.repository.BotInstanceRepository;
import com.plstk.loyaltybot.telegram.TelegramApiClient;
import com.plstk.loyaltybot.telegram.TelegramContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис для управления инстансами ботов (Telegram / Max).
 * Отвечает за подключение новых ботов, настройку webhook, 
 * валидацию запросов и управление жизненным циклом бота.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotInstanceService {
    
    private final BotInstanceRepository botInstanceRepository;
    private final TelegramApiClient telegramApiClient;
    private final MaxApiClient maxApiClient;
    private final TokenEncryptionService tokenEncryptionService;
    private final ShopSettingsService shopSettingsService;
    
    @Value("${server.base-url:}")
    private String serverBaseUrl;
    
    /**
     * Результат подключения бота
     */
    public record ConnectResult(
        boolean success,
        BotInstance botInstance,
        String shopId,
        String botUsername,
        String buyDeepLink,
        String adminDeepLink,
        String error
    ) {
        public static ConnectResult success(BotInstance bot) {
            return new ConnectResult(
                true, bot, bot.getShopId(), bot.getBotUsername(),
                bot.generateBuyDeepLink(null),
                bot.generateAdminDeepLink(),
                null
            );
        }
        
        public static ConnectResult error(String error) {
            return new ConnectResult(false, null, null, null, null, null, error);
        }
    }
    
    /**
     * Подключает новый бот к платформе.
     * 
     * Flow:
     * 1. Проверяет токен через getMe
     * 2. Проверяет что бот не подключен ранее
     * 3. Создаёт BotInstance с зашифрованным токеном
     * 4. Устанавливает webhook
     * 5. Настраивает команды и описание
     * 6. Создаёт ShopSettings по шаблону
     * 
     * @param botToken Токен от BotFather
     * @param businessType Тип бизнеса (COFFEE, RETAIL, SERVICE, HYBRID)
     * @param businessName Название бизнеса (опционально)
     * @param ownerEmail Email владельца (опционально)
     * @return Результат подключения
     */
    @Transactional
    public ConnectResult connectBot(String botToken, BotInstance.BusinessType businessType, 
                                    String businessName, String ownerEmail) {
        log.info("Connecting new bot, businessType={}, businessName={}", businessType, businessName);
        
        try {
            // 1. Проверяем токен через Telegram API
            TelegramApiClient.BotInfo botInfo = telegramApiClient.getMe(botToken);
            
            if (botInfo == null) {
                return ConnectResult.error("Не удалось получить информацию о боте. Проверьте токен.");
            }
            
            log.info("Bot info retrieved: id={}, username={}", botInfo.id(), botInfo.username());
            
            // 2. Проверяем что бот не подключен ранее
            if (botInstanceRepository.existsByTelegramBotId(botInfo.id())) {
                return ConnectResult.error("Этот бот уже подключен к платформе.");
            }
            
            // 3. Создаём BotInstance
            String shopId = UUID.randomUUID().toString();
            String webhookSecret = UUID.randomUUID().toString().replace("-", "");
            String encryptedToken = tokenEncryptionService.encrypt(botToken);
            
            BotInstance botInstance = BotInstance.builder()
                .shopId(shopId)
                .botToken(encryptedToken)
                .botUsername(botInfo.username())
                .telegramBotId(botInfo.id())
                .webhookSecret(webhookSecret)
                .businessType(businessType)
                .businessName(businessName)
                .ownerEmail(ownerEmail)
                .isActive(true)
                .status(BotInstance.BotStatus.CONNECTING)
                .build();
            
            botInstance = botInstanceRepository.save(botInstance);
            log.info("BotInstance created: id={}, shopId={}", botInstance.getId(), shopId);
            
            // 4. Устанавливаем webhook
            String webhookUrl = botInstance.generateWebhookUrl(getServerBaseUrl());
            boolean webhookSet = telegramApiClient.setWebhook(botToken, webhookUrl, webhookSecret);
            
            if (!webhookSet) {
                botInstance.setError("Не удалось установить webhook");
                botInstanceRepository.save(botInstance);
                return ConnectResult.error("Не удалось установить webhook. Проверьте URL сервера.");
            }
            
            botInstance.setWebhookUrl(webhookUrl);
            log.info("Webhook set: {}", webhookUrl);
            
            // 5. Настраиваем команды бота
            setupBotCommands(botToken, businessType);
            
            // 6. Настраиваем описание бота
            setupBotDescription(botToken, businessType, businessName);
            
            // 7. Создаём ShopSettings по шаблону
            createShopSettings(shopId, businessType, businessName);
            
            // 8. Активируем бота
            botInstance.setStatus(BotInstance.BotStatus.ACTIVE);
            botInstance.clearError();
            botInstance = botInstanceRepository.save(botInstance);
            
            log.info("Bot connected successfully: id={}, username={}, shopId={}", 
                botInstance.getId(), botInfo.username(), shopId);
            
            return ConnectResult.success(botInstance);
            
        } catch (TelegramApiClient.TelegramApiException e) {
            log.error("Telegram API error while connecting bot", e);
            return ConnectResult.error("Ошибка Telegram API: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error connecting bot", e);
            return ConnectResult.error("Внутренняя ошибка: " + e.getMessage());
        }
    }
    
    /**
     * Подключает бот Max к платформе.
     */
    @Transactional
    public ConnectResult connectMaxBot(String botToken, BotInstance.BusinessType businessType,
                                       String businessName, String ownerEmail) {
        log.info("Connecting new Max bot, businessType={}, businessName={}", businessType, businessName);

        try {
            MaxApiClient.BotInfo botInfo = maxApiClient.getMe(botToken);

            if (botInfo == null) {
                return ConnectResult.error("Не удалось получить информацию о Max-боте. Проверьте токен.");
            }

            log.info("Max bot info retrieved: id={}, username={}", botInfo.userId(), botInfo.username());

            if (botInstanceRepository.existsByTelegramBotId(botInfo.userId())) {
                return ConnectResult.error("Этот бот уже подключен к платформе.");
            }

            String shopId = UUID.randomUUID().toString();
            String webhookSecret = UUID.randomUUID().toString().replace("-", "")
                    .substring(0, Math.min(32, UUID.randomUUID().toString().replace("-", "").length()));
            String encryptedToken = tokenEncryptionService.encrypt(botToken);

            BotInstance botInstance = BotInstance.builder()
                    .shopId(shopId)
                    .botToken(encryptedToken)
                    .botUsername(botInfo.username() != null ? botInfo.username() : "max_bot_" + botInfo.userId())
                    .telegramBotId(botInfo.userId())
                    .webhookSecret(webhookSecret)
                    .platform(MessengerPlatform.MAX)
                    .businessType(businessType)
                    .businessName(businessName)
                    .ownerEmail(ownerEmail)
                    .isActive(true)
                    .status(BotInstance.BotStatus.CONNECTING)
                    .build();

            botInstance = botInstanceRepository.save(botInstance);
            log.info("Max BotInstance created: id={}, shopId={}", botInstance.getId(), shopId);

            String webhookUrl = botInstance.generateWebhookUrl(getServerBaseUrl());
            List<String> updateTypes = List.of("message_created", "message_callback", "bot_started");
            boolean subscribed = maxApiClient.subscribe(botToken, webhookUrl, updateTypes, webhookSecret);

            if (!subscribed) {
                botInstance.setError("Не удалось подписаться на webhook Max");
                botInstanceRepository.save(botInstance);
                return ConnectResult.error("Не удалось установить webhook для Max. Проверьте URL сервера.");
            }

            botInstance.setWebhookUrl(webhookUrl);
            log.info("Max webhook subscribed: {}", webhookUrl);

            createShopSettings(shopId, businessType, businessName);

            botInstance.setStatus(BotInstance.BotStatus.ACTIVE);
            botInstance.clearError();
            botInstance = botInstanceRepository.save(botInstance);

            log.info("Max bot connected successfully: id={}, username={}, shopId={}",
                    botInstance.getId(), botInfo.username(), shopId);

            return ConnectResult.success(botInstance);

        } catch (MaxApiClient.MaxApiException e) {
            log.error("Max API error while connecting bot", e);
            return ConnectResult.error("Ошибка Max API: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error connecting Max bot", e);
            return ConnectResult.error("Внутренняя ошибка: " + e.getMessage());
        }
    }

    /**
     * Отключает бот от платформы (Telegram или Max)
     */
    @Transactional
    public boolean disconnectBot(Long botInstanceId) {
        Optional<BotInstance> botOpt = botInstanceRepository.findById(botInstanceId);
        
        if (botOpt.isEmpty()) {
            return false;
        }
        
        BotInstance bot = botOpt.get();
        
        try {
            String decryptedToken = tokenEncryptionService.decrypt(bot.getBotToken());
            if (bot.getPlatform() == MessengerPlatform.MAX) {
                maxApiClient.unsubscribe(decryptedToken, bot.getWebhookUrl());
            } else {
                telegramApiClient.deleteWebhook(decryptedToken, false);
            }
        } catch (Exception e) {
            log.warn("Failed to delete webhook for bot {}", botInstanceId, e);
        }
        
        bot.setIsActive(false);
        bot.setStatus(BotInstance.BotStatus.DISABLED);
        bot.setWebhookUrl(null);
        botInstanceRepository.save(bot);
        
        log.info("Bot disconnected: id={}, platform={}", botInstanceId, bot.getPlatform());
        return true;
    }
    
    /**
     * Валидирует webhook запрос и возвращает BotInstance
     */
    public Optional<BotInstance> validateWebhook(Long botInstanceId, String secret) {
        return botInstanceRepository.findByIdAndWebhookSecret(botInstanceId, secret);
    }
    
    /**
     * Расшифровывает токен бота для внешнего использования.
     */
    public String getDecryptedToken(BotInstance botInstance) {
        return tokenEncryptionService.decrypt(botInstance.getBotToken());
    }

    /**
     * Создаёт TelegramContext для обработки Update
     */
    public TelegramContext createContext(BotInstance botInstance, Update update) {
        try {
            String decryptedToken = tokenEncryptionService.decrypt(botInstance.getBotToken());
            return TelegramContext.from(botInstance, decryptedToken, update);
        } catch (Exception e) {
            log.error("Failed to create context for bot {}", botInstance.getId(), e);
            return null;
        }
    }
    
    /**
     * Создаёт MaxContext для обработки Max Update
     */
    public MaxContext createMaxContext(BotInstance botInstance, Map<String, Object> update) {
        try {
            String decryptedToken = tokenEncryptionService.decrypt(botInstance.getBotToken());
            return MaxContext.from(botInstance, decryptedToken, update);
        } catch (Exception e) {
            log.error("Failed to create Max context for bot {}", botInstance.getId(), e);
            return null;
        }
    }
    
    /**
     * Записывает факт обработки webhook
     */
    @Transactional
    public void recordWebhook(BotInstance botInstance) {
        botInstance.incrementUpdatesProcessed();
        botInstanceRepository.save(botInstance);
    }
    
    /**
     * Находит BotInstance по ID
     */
    public Optional<BotInstance> findById(Long id) {
        return botInstanceRepository.findById(id);
    }
    
    /**
     * Находит BotInstance по shopId
     */
    public Optional<BotInstance> findByShopId(String shopId) {
        return botInstanceRepository.findByShopId(shopId);
    }
    
    /**
     * Находит все активные боты
     */
    public List<BotInstance> findAllActive() {
        return botInstanceRepository.findAllActive();
    }

    /**
     * Находит активные боты по списку shopId
     */
    public List<BotInstance> findActiveByShopIds(List<String> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return List.of();
        }
        return botInstanceRepository.findActiveByShopIdIn(shopIds);
    }

    /**
     * Находит активные боты по email владельца
     */
    public List<BotInstance> findActiveByOwnerEmail(String email) {
        if (email == null || email.isBlank()) {
            return List.of();
        }
        return botInstanceRepository.findActiveByOwnerEmail(email);
    }
    
    /**
     * Обновляет webhook для бота (при смене домена). Поддерживает Telegram и Max.
     */
    @Transactional
    public boolean updateWebhook(Long botInstanceId, String newBaseUrl) {
        Optional<BotInstance> botOpt = botInstanceRepository.findById(botInstanceId);
        
        if (botOpt.isEmpty()) {
            return false;
        }
        
        BotInstance bot = botOpt.get();
        
        try {
            String decryptedToken = tokenEncryptionService.decrypt(bot.getBotToken());
            String webhookUrl = bot.generateWebhookUrl(newBaseUrl);
            
            boolean success;
            if (bot.getPlatform() == MessengerPlatform.MAX) {
                List<String> updateTypes = List.of("message_created", "message_callback", "bot_started");
                success = maxApiClient.subscribe(decryptedToken, webhookUrl, updateTypes, bot.getWebhookSecret());
            } else {
                success = telegramApiClient.setWebhook(decryptedToken, webhookUrl, bot.getWebhookSecret());
            }
            
            if (success) {
                bot.setWebhookUrl(webhookUrl);
                bot.clearError();
                botInstanceRepository.save(bot);
            }
            
            return success;
        } catch (Exception e) {
            log.error("Failed to update webhook for bot {}", botInstanceId, e);
            return false;
        }
    }
    
    /**
     * Получает информацию о webhook бота
     */
    public TelegramApiClient.WebhookInfo getWebhookInfo(Long botInstanceId) {
        Optional<BotInstance> botOpt = botInstanceRepository.findById(botInstanceId);
        
        if (botOpt.isEmpty()) {
            return null;
        }
        
        BotInstance bot = botOpt.get();
        
        try {
            String decryptedToken = tokenEncryptionService.decrypt(bot.getBotToken());
            return telegramApiClient.getWebhookInfo(decryptedToken);
        } catch (Exception e) {
            log.error("Failed to get webhook info for bot {}", botInstanceId, e);
            return null;
        }
    }
    
    /**
     * Количество активных ботов
     */
    public long countActiveBots() {
        return botInstanceRepository.countActiveBots();
    }
    
    // ========== Private Methods ==========
    
    private String getServerBaseUrl() {
        if (serverBaseUrl != null && !serverBaseUrl.isEmpty()) {
            return serverBaseUrl;
        }
        // Fallback - должен быть настроен для production!
        log.warn("server.base-url not configured! Webhook will not work.");
        return "https://your-domain.com";
    }
    
    private void setupBotCommands(String botToken, BotInstance.BusinessType businessType) {
        List<TelegramApiClient.BotCommand> commands = switch (businessType) {
            case COFFEE -> List.of(
                new TelegramApiClient.BotCommand("start", "Начать / Регистрация"),
                new TelegramApiClient.BotCommand("buy", "Совершить покупку"),
                new TelegramApiClient.BotCommand("stamps", "Мои штампы"),
                new TelegramApiClient.BotCommand("status", "Мой статус")
            );
            case RETAIL -> List.of(
                new TelegramApiClient.BotCommand("start", "Начать / Регистрация"),
                new TelegramApiClient.BotCommand("buy", "Совершить покупку"),
                new TelegramApiClient.BotCommand("discounts", "Мои скидки"),
                new TelegramApiClient.BotCommand("status", "Мой статус")
            );
            case SERVICE, HYBRID -> List.of(
                new TelegramApiClient.BotCommand("start", "Начать / Регистрация"),
                new TelegramApiClient.BotCommand("buy", "Записаться / Купить"),
                new TelegramApiClient.BotCommand("points", "Мои баллы"),
                new TelegramApiClient.BotCommand("status", "Мой статус")
            );
        };
        
        try {
            telegramApiClient.setMyCommands(botToken, commands);
        } catch (Exception e) {
            log.warn("Failed to set commands for bot", e);
        }
    }
    
    private void setupBotDescription(String botToken, BotInstance.BusinessType businessType, String businessName) {
        String name = businessName != null ? businessName : "наш";
        
        String description = switch (businessType) {
            case COFFEE -> String.format(
                "🎉 Добро пожаловать в программу лояльности %s!\n\n" +
                "☕ Собирайте штампы за каждую покупку\n" +
                "🎁 Получайте бесплатные напитки\n" +
                "📊 Следите за своим прогрессом", name);
            case RETAIL -> String.format(
                "🎉 Добро пожаловать в программу лояльности %s!\n\n" +
                "💰 Накапливайте скидки с каждой покупкой\n" +
                "🎁 Участвуйте в акциях\n" +
                "📊 Следите за своими бонусами", name);
            case SERVICE, HYBRID -> String.format(
                "🎉 Добро пожаловать в программу лояльности %s!\n\n" +
                "⭐ Получайте баллы за каждый визит\n" +
                "🎁 Обменивайте на скидки и подарки\n" +
                "📊 Следите за своим статусом", name);
        };
        
        try {
            telegramApiClient.setMyDescription(botToken, description);
            telegramApiClient.setMyShortDescription(botToken, 
                String.format("Программа лояльности %s", name));
        } catch (Exception e) {
            log.warn("Failed to set description for bot", e);
        }
    }
    
    private void createShopSettings(String shopId, BotInstance.BusinessType businessType, String businessName) {
        // Создаём настройки магазина по шаблону
        ShopSettings settings = ShopSettings.builder()
            .shopId(shopId)
            .shopName(businessName != null ? businessName : "Магазин")
            .build();
        
        // Применяем шаблон в зависимости от типа бизнеса
        switch (businessType) {
            case COFFEE -> {
                settings.setFastCheckoutEnabled(true);
                settings.setStampsEnabled(true);
                settings.setDiscountTiersEnabled(false);
                settings.setFastCheckoutType(ShopSettings.FastCheckoutType.STAMP);
                settings.setFastCheckoutValue(1);
                settings.setStampsPerFastPurchase(1);
                settings.setStampsRequiredForReward(10);
                settings.setRewardTitle("Бесплатный напиток");
                settings.setRewardDescription("Любой напиток на ваш выбор");
            }
            case RETAIL -> {
                settings.setFastCheckoutEnabled(false);
                settings.setStampsEnabled(false);
                settings.setDiscountTiersEnabled(true);
                settings.setDiscountTier1Amount(20000.0);
                settings.setDiscountTier1Percent(5);
                settings.setDiscountTier2Amount(25000.0);
                settings.setDiscountTier2Percent(7);
                settings.setDiscountTier3Amount(30000.0);
                settings.setDiscountTier3Percent(10);
            }
            case SERVICE -> {
                settings.setFastCheckoutEnabled(true);
                settings.setStampsEnabled(false);
                settings.setDiscountTiersEnabled(false);
                settings.setFastCheckoutType(ShopSettings.FastCheckoutType.FIXED_POINTS);
                settings.setFastCheckoutValue(10);
            }
            case HYBRID -> {
                settings.setFastCheckoutEnabled(true);
                settings.setStampsEnabled(true);
                settings.setDiscountTiersEnabled(true);
            }
        }
        
        shopSettingsService.createSettings(settings);
        log.info("ShopSettings created for shopId={}, type={}", shopId, businessType);
    }
}

