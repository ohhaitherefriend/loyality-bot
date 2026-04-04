package com.plstk.loyaltybot.bot;

import com.plstk.loyaltybot.entity.*;
import com.plstk.loyaltybot.service.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Legacy single-tenant бот (Long Polling).
 * Активируется только если указан telegram.bot.token.
 * 
 * Для multi-tenant режима (BYOB) используется LoyaltyBotService + webhooks.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.token", matchIfMissing = false)
@Slf4j
public class LoyaltyBot extends TelegramLongPollingBot {
    
    private final UserService userService;
    private final PurchaseCodeService purchaseCodeService;
    private final DiscountCodeService discountCodeService;
    private final TransactionService transactionService;
    private final PromotionService promotionService;
    private final ShopSettingsService shopSettingsService;
    private final StampWalletService stampWalletService;
    private final CustomerProfileService customerProfileService;
    private final AutoTriggerService autoTriggerService;
    private final AchievementService achievementService;
    private final OwnerSignalService ownerSignalService;
    private final MessageComposerService messageComposerService;
    private final ManualBadgeService manualBadgeService;
    private final WeeklyReportService weeklyReportService;
    private final ClientMemoryService clientMemoryService;
    
    // Временное хранилище для кодов покупок, ожидающих ввода суммы
    // Ключ: chatId админа, Значение: PurchaseCode
    private final Map<Long, PurchaseCode> pendingPurchaseCodes = new HashMap<>();
    
    // Временное хранилище для создаваемых промо-акций
    // Ключ: chatId админа, Значение: PromotionBuilder
    private final Map<Long, PromotionBuilder> pendingPromotions = new HashMap<>();
    
    // Временное хранилище для отправки сообщения клиенту (из админки)
    // Ключ: chatId админа, Значение: chatId клиента
    private final Map<Long, Long> pendingMessageTargets = new HashMap<>();
    
    // Временное хранилище для заметок о клиентах
    // Ключ: chatId админа, Значение: User клиента для которого добавляется заметка
    private final Map<Long, User> pendingClientNotes = new HashMap<>();
    
    // Вспомогательный класс для хранения данных создаваемой акции
    private static class PromotionBuilder {
        Integer discountPercent;
        Integer durationDays;
        String description;
    }
    
    @Value("${telegram.bot.username}")
    private String botUsername;
    
    @Value("${telegram.bot.admin-secret:}")
    private String adminSecret;
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    
    // Кнопки для пользователя
    private static final String BTN_PURCHASE = "🛍 Я совершаю покупку";
    private static final String BTN_MY_STATUS = "📊 Мой статус";
    private static final String BTN_HISTORY = "📜 История покупок";
    private static final String BTN_DISCOUNTS = "🎁 Мои скидки";
    private static final String BTN_STAMPS = "☕ Мои штампы";
    private static final String BTN_ACHIEVEMENTS = "🏆 Достижения";
    
    // Кнопки для админа
    private static final String BTN_ENTER_CODE = "🔑 Ввести код покупки";
    private static final String BTN_ENTER_REDEEM = "🎁 Код награды";
    private static final String BTN_SEND_DISCOUNT = "📢 Отправить скидку";
    private static final String BTN_PROMOTIONS = "🎁 Активные акции";
    private static final String BTN_STATS = "📊 Статистика";
    private static final String BTN_SIGNALS = "🔔 Уведомления";
    private static final String BTN_MESSAGE = "✉️ Написать клиенту";
    private static final String BTN_BADGES = "🎖 Выдать бейдж";
    private static final String BTN_REPORTS = "📈 Отчёты";
    
    // Кнопки для клиента
    private static final String BTN_MY_BADGES = "🎖 Мои бейджи";
    private static final String BTN_CHANNEL = "📢 Новости и акции";
    
    // Callback data prefixes
    private static final String CB_FAST_CHECKOUT = "fast_checkout:";
    private static final String CB_AMOUNT_CHECKOUT = "amount_checkout:";
    private static final String CB_CANCEL_CHECKOUT = "cancel_checkout:";
    private static final String CB_REDEEM_REWARD = "redeem_reward";
    private static final String CB_CANCEL_REDEEM = "cancel_redeem:";
    private static final String CB_AWARD_BADGE = "award_badge:";
    private static final String CB_REVOKE_BADGE = "revoke_badge:";
    private static final String CB_ADD_NOTE = "add_note:";  // Добавить заметку о клиенте
    private static final String CB_SKIP_NOTE = "skip_note";  // Пропустить добавление заметки
    private static final String CB_REPORT_WEEKLY = "report_weekly";
    private static final String CB_REPORT_DAILY = "report_daily";
    private static final String CB_REPORT_MONTHLY = "report_monthly";
    
    // Временное хранилище для выдачи бейджей
    // Ключ: chatId админа, Значение: chatId клиента
    private final Map<Long, Long> pendingBadgeTargets = new HashMap<>();
    
    public LoyaltyBot(@Value("${telegram.bot.token}") String botToken,
                     UserService userService,
                     PurchaseCodeService purchaseCodeService,
                     DiscountCodeService discountCodeService,
                     TransactionService transactionService,
                     PromotionService promotionService,
                     ShopSettingsService shopSettingsService,
                     StampWalletService stampWalletService,
                     CustomerProfileService customerProfileService,
                     AutoTriggerService autoTriggerService,
                     AchievementService achievementService,
                     OwnerSignalService ownerSignalService,
                     MessageComposerService messageComposerService,
                     ManualBadgeService manualBadgeService,
                     WeeklyReportService weeklyReportService,
                     ClientMemoryService clientMemoryService) {
        super(botToken);
        this.userService = userService;
        this.purchaseCodeService = purchaseCodeService;
        this.discountCodeService = discountCodeService;
        this.transactionService = transactionService;
        this.promotionService = promotionService;
        this.shopSettingsService = shopSettingsService;
        this.stampWalletService = stampWalletService;
        this.customerProfileService = customerProfileService;
        this.autoTriggerService = autoTriggerService;
        this.achievementService = achievementService;
        this.ownerSignalService = ownerSignalService;
        this.messageComposerService = messageComposerService;
        this.manualBadgeService = manualBadgeService;
        this.weeklyReportService = weeklyReportService;
        this.clientMemoryService = clientMemoryService;
    }
    
    @PostConstruct
    public void init() {
        // Настраиваем callback для отправки авто-сообщений
        autoTriggerService.setMessageSender((chatId, message) -> 
            sendMessage(chatId, message, null, true));
        
        // Настраиваем callback для message composer
        messageComposerService.setMessageSender((chatId, message) ->
            sendMessage(chatId, message, null, true));
        
        // Настраиваем callback для еженедельного отчёта
        weeklyReportService.setReportSender(report -> {
            // Отправляем отчёт всем админам
            List<User> admins = userService.findByRole(User.UserRole.ADMIN);
            for (User admin : admins) {
                sendMessage(admin.getChatId(), report, null, true);
            }
        });
        
        log.info("LoyaltyBot initialized with auto-trigger, message composer and weekly report support");
    }
    
    @Override
    public String getBotUsername() {
        return botUsername;
    }
    
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                handleMessage(update);
            }
        } catch (Exception e) {
            log.error("Error processing update", e);
        }
    }
    
    /**
     * Обработка inline callback кнопок
     */
    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        Long chatId = callbackQuery.getMessage().getChatId();
        String data = callbackQuery.getData();
        Integer messageId = callbackQuery.getMessage().getMessageId();
        
        Optional<User> userOpt = userService.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            return;
        }
        User user = userOpt.get();
        
        try {
            if (data.startsWith(CB_FAST_CHECKOUT)) {
                handleFastCheckoutCallback(chatId, data, user, messageId);
            } else if (data.startsWith(CB_AMOUNT_CHECKOUT)) {
                handleAmountCheckoutCallback(chatId, data, user, messageId);
            } else if (data.startsWith(CB_CANCEL_CHECKOUT)) {
                handleCancelCheckoutCallback(chatId, data, user, messageId);
            } else if (data.equals(CB_REDEEM_REWARD)) {
                handleRedeemRewardCallback(chatId, user);
            } else if (data.startsWith(CB_CANCEL_REDEEM)) {
                handleCancelRedeemCallback(chatId, data, user);
            } else if (data.startsWith(CB_AWARD_BADGE)) {
                handleAwardBadgeCallback(chatId, data, user, messageId);
            } else if (data.startsWith(CB_REVOKE_BADGE)) {
                handleRevokeBadgeCallback(chatId, data, user, messageId);
            } else if (data.equals(CB_REPORT_WEEKLY)) {
                handleReportCallback(chatId, "weekly", user, messageId);
            } else if (data.equals(CB_REPORT_DAILY)) {
                handleReportCallback(chatId, "daily", user, messageId);
            } else if (data.equals(CB_REPORT_MONTHLY)) {
                handleReportCallback(chatId, "monthly", user, messageId);
            } else if (data.startsWith(CB_ADD_NOTE)) {
                handleAddNoteCallback(chatId, data, user, messageId);
            } else if (data.equals(CB_SKIP_NOTE)) {
                handleSkipNoteCallback(chatId, user, messageId);
            }
        } catch (Exception e) {
            log.error("Error handling callback query: {}", data, e);
            sendMessage(chatId, "❌ Ошибка: " + e.getMessage(), getUserKeyboard(user));
        }
    }
    
    private void handleMessage(Update update) {
        Long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().getText();
        
        Optional<User> userOpt = userService.findByChatId(chatId);
        
        // Команда /start (с возможным deep-link)
        if (messageText != null && messageText.startsWith("/start")) {
            handleStartCommand(chatId, update, userOpt, messageText);
            return;
        }
        
        // Команда /makeadmin
        if (messageText != null && messageText.startsWith("/makeadmin")) {
            handleMakeAdminCommand(chatId, messageText, userOpt);
            return;
        }
        
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "⚠️ Пожалуйста, начните с команды /start");
            return;
        }
        
        User user = userOpt.get();
        
        // Обработка состояний пользователя
        if (user.getState() == User.UserState.AWAITING_PHONE) {
            handlePhoneNumber(chatId, update, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_ADMIN_CODE) {
            handleAdminCodeInput(chatId, messageText, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_PURCHASE_AMOUNT) {
            handlePurchaseAmountInput(chatId, messageText, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_REDEEM_CODE) {
            handleRedeemCodeInput(chatId, messageText, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_FAST_OR_AMOUNT_CHOICE) {
            // В этом состоянии ожидаем только callback кнопки, текст игнорируем
            sendMessage(chatId, "⏳ Выберите способ подтверждения, нажав на кнопку выше.");
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_PROMOTION_DISCOUNT) {
            handlePromotionDiscountInput(chatId, messageText, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_PROMOTION_DURATION) {
            handlePromotionDurationInput(chatId, messageText, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_PROMOTION_DESCRIPTION) {
            handlePromotionDescriptionInput(chatId, messageText, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_CLIENT_NOTE) {
            handleClientNoteInput(chatId, messageText, user);
            return;
        }
        
        // Обработка кнопок меню
        if (messageText != null) {
            switch (messageText) {
                case BTN_PURCHASE -> handlePurchaseButton(chatId, user);
                case BTN_MY_STATUS -> handleMyStatusButton(chatId, user);
                case BTN_HISTORY -> handleHistoryButton(chatId, user);
                case BTN_DISCOUNTS -> handleDiscountsButton(chatId, user);
                case BTN_STAMPS -> handleStampsButton(chatId, user);
                case BTN_ACHIEVEMENTS -> handleAchievementsButton(chatId, user);
                case BTN_ENTER_CODE -> handleEnterCodeButton(chatId, user);
                case BTN_ENTER_REDEEM -> handleEnterRedeemCodeButton(chatId, user);
                case BTN_SEND_DISCOUNT -> handleSendDiscountButton(chatId, user);
                case BTN_PROMOTIONS -> handlePromotionsButton(chatId, user);
                case BTN_STATS -> handleStatsButton(chatId, user);
                case BTN_MESSAGE -> handleMessageButton(chatId, user);
                case BTN_BADGES -> handleBadgesButton(chatId, user);
                case BTN_REPORTS -> handleReportsButton(chatId, user);
                case BTN_MY_BADGES -> handleMyBadgesButton(chatId, user);
                case BTN_CHANNEL -> handleChannelButton(chatId, user);
                default -> {
                    // Проверяем кнопку сигналов (может содержать счётчик)
                    if (messageText.startsWith(BTN_SIGNALS)) {
                        handleSignalsButton(chatId, user);
                    } else {
                        sendMessage(chatId, "Используйте кнопки меню для навигации", getUserKeyboard(user));
                    }
                }
            }
        }
    }
    
    private void handleStartCommand(Long chatId, Update update, Optional<User> userOpt, String messageText) {
        // Проверяем deep-link параметр
        String deepLinkParam = null;
        if (messageText.length() > 7) { // "/start " = 7 символов
            deepLinkParam = messageText.substring(7).trim();
        }
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            
            // Обработка deep-link для покупки
            if (deepLinkParam != null && deepLinkParam.startsWith("buy_")) {
                handleBuyDeepLink(chatId, user, deepLinkParam);
                return;
            }
            
            sendMessage(chatId, "С возвращением, " + user.getFirstName() + "!", getUserKeyboard(user));
        } else {
            // Сохраняем deep-link для обработки после регистрации
            String pendingDeepLink = deepLinkParam;
            
            sendMessage(chatId, 
                "👋 Добро пожаловать в программу лояльности!\n\n" +
                "Для регистрации, пожалуйста, поделитесь своим номером телефона, " +
                "нажав на кнопку ниже.", 
                getPhoneRequestKeyboard());
            
            User newUser = User.builder()
                    .chatId(chatId)
                    .phoneNumber("")
                    .firstName(update.getMessage().getFrom().getFirstName())
                    .lastName(update.getMessage().getFrom().getLastName())
                    .username(update.getMessage().getFrom().getUserName())
                    .role(User.UserRole.USER)
                    .state(User.UserState.AWAITING_PHONE)
                    .build();
            userService.updateUserState(newUser, User.UserState.AWAITING_PHONE);
        }
    }
    
    /**
     * Обработка QR deep-link для покупки
     * Формат: buy_<locationId> или просто buy
     */
    private void handleBuyDeepLink(Long chatId, User user, String deepLinkParam) {
        // Парсим locationId (если есть)
        String locationId = null;
        if (deepLinkParam.length() > 4) { // "buy_" = 4 символа
            locationId = deepLinkParam.substring(4);
        }
        
        // Используем default location если не указан
        if (locationId == null || locationId.isEmpty()) {
            locationId = shopSettingsService.getDefaultLocationId();
        }
        
        // Генерируем код покупки
        PurchaseCode code = purchaseCodeService.generateCode(user);
        code.setFromDeepLink(true);
        code.setLocationId(locationId);
        
        sendMessage(chatId,
            "🛍 *Покупка через QR*\n\n" +
            "📋 Ваш код: *" + code.getCode() + "*\n\n" +
            "⏰ Код действителен до: " + code.getExpiresAt().format(DATE_FORMATTER) + "\n\n" +
            "Назовите этот код администратору.",
            getUserKeyboard(user), true);
        
        log.info("Generated purchase code {} from deep-link for user {}, location={}", 
            code.getCode(), user.getChatId(), locationId);
    }
    
    private void handlePhoneNumber(Long chatId, Update update, User user) {
        String phoneNumber = null;
        
        if (update.getMessage().hasContact()) {
            phoneNumber = update.getMessage().getContact().getPhoneNumber();
        } else if (update.getMessage().hasText()) {
            phoneNumber = update.getMessage().getText();
        }
        
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            sendMessage(chatId, "Пожалуйста, отправьте номер телефона.");
            return;
        }
        
        // Нормализация номера телефона
        phoneNumber = phoneNumber.replaceAll("[^0-9+]", "");
        
        // Валидация формата номера телефона
        if (!isValidPhoneNumber(phoneNumber)) {
            sendMessage(chatId, 
                "❌ Некорректный формат номера телефона!\n\n" +
                "Пожалуйста, используйте кнопку \"📱 Отправить номер телефона\" " +
                "или введите номер в формате:\n" +
                "+79991234567 или 89991234567\n\n" +
                "⚠️ Не вводите код покупки вместо номера телефона!", 
                getPhoneRequestKeyboard());
            return;
        }
        
        // Проверка, не занят ли номер другим пользователем
        Optional<User> existingUser = userService.findByPhoneNumber(phoneNumber);
        if (existingUser.isPresent() && !existingUser.get().getChatId().equals(chatId)) {
            sendMessage(chatId, "❌ Этот номер телефона уже зарегистрирован другим пользователем!");
            return;
        }
        
        user.setPhoneNumber(phoneNumber);
        user.setState(User.UserState.REGISTERED);
        userService.updateUserState(user, User.UserState.REGISTERED);
        
        // Генерируем промо-коды для новых акций
        List<DiscountCode> promoCodes = promotionService.generatePromotionCodesForNewUser(user);
        
        // Формируем сообщение о регистрации динамически
        StringBuilder registrationMessage = new StringBuilder();
        registrationMessage.append("✅ Регистрация успешна!\n\n");
        registrationMessage.append("📱 Телефон: ").append(phoneNumber).append("\n");
        
        // Добавляем информацию о системе лояльности в зависимости от настроек
        boolean hasFeatures = false;
        
        // Накопительная скидка
        if (shopSettingsService.isDiscountTiersEnabled()) {
            registrationMessage.append("\n").append(shopSettingsService.getDiscountTiersDescription());
            hasFeatures = true;
        }
        
        // Штампы
        if (shopSettingsService.isStampsEnabled()) {
            if (hasFeatures) {
                registrationMessage.append("\n\n━━━━━━━━━━━━━━━━━━━━\n");
            } else {
                registrationMessage.append("\n");
            }
            registrationMessage.append("☕ *Программа штампов*\n\n");
            registrationMessage.append("Собирайте штампы за каждую покупку!\n");
            registrationMessage.append(String.format("Соберите %d штампов и получите: %s 🎁", 
                shopSettingsService.getStampsRequiredForReward(),
                shopSettingsService.getRewardTitle()));
            hasFeatures = true;
        }
        
        // Если ничего не включено
        if (!hasFeatures) {
            registrationMessage.append("\n🎉 Добро пожаловать в нашу программу лояльности!");
        }
        
        // Если есть активные акции, добавляем информацию о них
        if (!promoCodes.isEmpty()) {
            registrationMessage.append("\n\n🎉 *У нас есть активные акции!*\n")
                .append("Проверьте раздел \"🎁 Мои скидки\" для получения скидок.");
        }
        
        sendMessage(chatId, registrationMessage.toString(), getUserKeyboard(user), !promoCodes.isEmpty());
    }
    
    private void handlePurchaseButton(Long chatId, User user) {
        PurchaseCode code = purchaseCodeService.generateCode(user);
        
        sendMessage(chatId,
            "🛍 Код для покупки создан!\n\n" +
            "📋 Ваш код: *" + code.getCode() + "*\n\n" +
            "⏰ Код действителен до: " + code.getExpiresAt().format(DATE_FORMATTER) + "\n\n" +
            "Назовите этот код администратору в магазине.",
            getUserKeyboard(user), true);
    }
    
    private void handleMyStatusButton(Long chatId, User user) {
        StringBuilder message = new StringBuilder();
        
        // Профиль клиента
        message.append(customerProfileService.getFullProfileInfo(user)).append("\n");
        
        // Прогресс статуса
        message.append(customerProfileService.getStatusProgressInfo(user)).append("\n\n");
        
        // Накопительная скидка (если включена)
        if (shopSettingsService.isDiscountTiersEnabled()) {
            double accumulated = userService.getAccumulatedAmount(user);
            
            message.append("━━━━━━━━━━━━━━━━━━━━\n");
            message.append("💰 *Накопительная скидка*\n\n");
            
            if (user.isDiscountValid()) {
                message.append("🎯 Текущая скидка: *").append(user.getDiscountDescription()).append("*\n");
                
                if (user.getDiscountExpiresAt() != null) {
                    long daysLeft = java.time.Duration.between(java.time.LocalDateTime.now(), user.getDiscountExpiresAt()).toDays();
                    message.append("⏰ Действует еще ").append(daysLeft).append(" дней\n");
                }
                
                message.append("📈 Накоплено: *").append(String.format("%.2f", accumulated)).append("* руб.\n");
                message.append("🚀 ").append(user.getNextDiscountLevelInfo(accumulated)).append("\n");
            } else {
                message.append("📈 Накоплено: *").append(String.format("%.2f", accumulated)).append("* руб.\n");
                message.append("🚀 ").append(user.getNextDiscountLevelInfo(accumulated)).append("\n");
            }
        }
        
        // Штампы (если включены)
        if (shopSettingsService.isStampsEnabled()) {
            message.append("\n━━━━━━━━━━━━━━━━━━━━\n");
            message.append("☕ *Штампы*\n\n");
            
            Optional<StampWallet> walletOpt = stampWalletService.getWallet(user);
            int stampsRequired = shopSettingsService.getStampsRequiredForReward();
            
            if (walletOpt.isPresent()) {
                StampWallet wallet = walletOpt.get();
                int stamps = wallet.getStampsCount() != null ? wallet.getStampsCount() : 0;
                int rewards = wallet.getRewardsAvailable() != null ? wallet.getRewardsAvailable() : 0;
                
                message.append(stampWalletService.generateStampVisual(stamps, stampsRequired)).append("\n");
                message.append("Штампов: ").append(stamps).append("/").append(stampsRequired).append("\n");
                
                if (rewards > 0) {
                    message.append("🎁 Доступно наград: *").append(rewards).append("*\n");
                }
            } else {
                message.append(stampWalletService.generateStampVisual(0, stampsRequired)).append("\n");
                message.append("Штампов: 0/").append(stampsRequired).append("\n");
            }
        }
        
        sendMessage(chatId, message.toString(), getUserKeyboard(user), true);
    }
    
    private void handleHistoryButton(Long chatId, User user) {
        List<Transaction> transactions = transactionService.getUserRecentTransactions(user, 10);
        
        if (transactions.isEmpty()) {
            sendMessage(chatId, "📜 История покупок пуста", getUserKeyboard(user));
            return;
        }
        
        StringBuilder message = new StringBuilder("📜 *Последние покупки:*\n\n");
        
        for (Transaction tx : transactions) {
            message.append(tx.getCreatedAt().format(DATE_FORMATTER))
                   .append("\n");
            
            if (tx.getDescription() != null) {
                message.append("_").append(tx.getDescription()).append("_\n");
            }
            message.append("\n");
        }
        
        sendMessage(chatId, message.toString(), getUserKeyboard(user), true);
    }
    
    private void handleDiscountsButton(Long chatId, User user) {
        List<DiscountCode> codes = discountCodeService.getActiveCodesForUser(user);
        
        if (codes.isEmpty()) {
            sendMessage(chatId, "🎁 У вас пока нет активных скидок", getUserKeyboard(user));
            return;
        }
        
        StringBuilder message = new StringBuilder("🎁 *Ваши активные скидки:*\n\n");
        
        for (DiscountCode code : codes) {
            if (code.getDescription() != null) {
                message.append("🎉 ").append(code.getDescription()).append("\n");
            }
            if (code.getDiscountPercent() != null) {
                message.append("💰 Скидка: *").append(code.getDiscountPercent()).append("%*\n");
            }
            if (code.getExpiresAt() != null) {
                message.append("⏰ Действует до: ")
                       .append(code.getExpiresAt().format(DATE_FORMATTER))
                       .append("\n");
            }
            message.append("💡 Скидка применится автоматически при покупке\n");
            message.append("\n");
        }
        
        sendMessage(chatId, message.toString(), getUserKeyboard(user), true);
    }
    
    // Админ функции
    private void handleEnterCodeButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        // Проверяем, что админ завершил регистрацию
        if (user.getState() != User.UserState.REGISTERED) {
            sendMessage(chatId, 
                "⚠️ Пожалуйста, завершите регистрацию перед использованием административных функций.\n\n" +
                "Отправьте /start и зарегистрируйте свой номер телефона.");
            return;
        }
        
        userService.updateUserState(user, User.UserState.AWAITING_ADMIN_CODE);
        sendMessage(chatId, "🔑 Введите код покупки от клиента:");
    }
    
    private void handleAdminCodeInput(Long chatId, String code, User admin) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        Optional<PurchaseCode> purchaseCodeOpt = purchaseCodeService.findByCode(code.toUpperCase().trim());
        
        if (purchaseCodeOpt.isEmpty()) {
            sendMessage(chatId, "❌ Код не найден. Проверьте правильность ввода.");
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        PurchaseCode purchaseCode = purchaseCodeOpt.get();
        
        // Проверяем статус кода
        if (purchaseCode.getStatus() != PurchaseCode.CodeStatus.ACTIVE) {
            sendMessage(chatId, "❌ Код уже использован или истек.", getUserKeyboard(admin));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        // Получаем клиента
        User customer = purchaseCode.getUser();
        
        // Формируем информацию о клиенте (Client Snapshot)
        StringBuilder customerInfo = new StringBuilder();
        customerInfo.append("✅ *Код найден!*\n\n");
        
        // === Client Snapshot: Краткий портрет клиента ===
        customerInfo.append("👤 *").append(customer.getFirstName());
        if (customer.getLastName() != null) {
            customerInfo.append(" ").append(customer.getLastName());
        }
        customerInfo.append("*\n");
        customerInfo.append("📱 ").append(customer.getPhoneNumber()).append("\n");
        customerInfo.append("📊 ").append(customer.getStatusInfo()).append("\n");
        
        // Статистика клиента
        Integer purchases = customer.getPurchasesCount();
        Double totalSpend = customer.getTotalSpend();
        if (purchases != null && purchases > 0) {
            customerInfo.append("🛒 Покупок: ").append(purchases);
            if (totalSpend != null && totalSpend > 0) {
                double avgCheck = totalSpend / purchases;
                customerInfo.append(" (средн. чек: ").append(String.format("%.0f", avgCheck)).append(" руб.)");
            }
            customerInfo.append("\n");
        }
        
        // Заметки персонала (память о клиенте)
        List<ClientNote> clientNotes = clientMemoryService.getActiveNotes(customer);
        if (!clientNotes.isEmpty()) {
            customerInfo.append("\n🧠 *Заметки:*\n");
            for (ClientNote note : clientNotes) {
                customerInfo.append(note.getDisplayText()).append("\n");
            }
        }
        customerInfo.append("\n");
        
        // Показываем информацию о скидках (если включены)
        if (shopSettingsService.isDiscountTiersEnabled()) {
            double loyaltyDiscount = customer.getEffectiveDiscountPercent();
            List<DiscountCode> activePromoCodes = discountCodeService.getActiveCodesForUser(customer);
            
            if (loyaltyDiscount > 0) {
                customerInfo.append("🎯 *Скидка: ")
                    .append(String.format("%.0f%%", loyaltyDiscount * 100))
                    .append("*\n");
                if (customer.getDiscountExpiresAt() != null) {
                    long daysLeft = java.time.Duration.between(java.time.LocalDateTime.now(), customer.getDiscountExpiresAt()).toDays();
                    customerInfo.append("⏰ Действует еще ").append(daysLeft).append(" дней\n");
                }
            }
            
            double accumulated = userService.getAccumulatedAmount(customer);
            customerInfo.append("💰 Накоплено: ").append(String.format("%.2f", accumulated)).append(" руб.\n");
            
            if (!activePromoCodes.isEmpty()) {
                customerInfo.append("\n🎁 *Акционные скидки:*\n");
                for (DiscountCode promoCode : activePromoCodes) {
                    customerInfo.append("  • ").append(promoCode.getDescription())
                        .append(" - *").append(promoCode.getDiscountPercent()).append("%*\n");
                }
            }
            customerInfo.append("\n");
        }
        
        // Показываем информацию о штампах (если включены)
        if (shopSettingsService.isStampsEnabled()) {
            Optional<StampWallet> walletOpt = stampWalletService.getWallet(customer);
            int stampsRequired = shopSettingsService.getStampsRequiredForReward();
            
            if (walletOpt.isPresent()) {
                StampWallet wallet = walletOpt.get();
                int stamps = wallet.getStampsCount() != null ? wallet.getStampsCount() : 0;
                customerInfo.append("☕ Штампы: ").append(stamps).append("/").append(stampsRequired).append("\n");
            } else {
                customerInfo.append("☕ Штампы: 0/").append(stampsRequired).append("\n");
            }
            customerInfo.append("\n");
        }
        
        // Сохраняем код временно
        pendingPurchaseCodes.put(chatId, purchaseCode);
        
        // Проверяем, включен ли fast checkout
        if (shopSettingsService.isFastCheckoutEnabled()) {
            // Проверяем cooldown и лимиты для клиента
            boolean canFastCheckout = customer.canFastCheckout(shopSettingsService.getFastCheckoutCooldownMinutes())
                && customer.canFastCheckoutToday(shopSettingsService.getFastCheckoutDailyLimit());
            
            if (canFastCheckout) {
                // Показываем inline кнопки: Быстро / С суммой / Отмена
                userService.updateUserState(admin, User.UserState.AWAITING_FAST_OR_AMOUNT_CHOICE);
                
                customerInfo.append("*Выберите способ подтверждения:*");
                
                InlineKeyboardMarkup keyboard = createFastOrAmountKeyboard(purchaseCode.getCode());
                sendMessageWithInlineKeyboard(chatId, customerInfo.toString(), keyboard);
            } else {
                // Fast checkout недоступен (cooldown/лимит)
                userService.updateUserState(admin, User.UserState.AWAITING_PURCHASE_AMOUNT);
                customerInfo.append("⚠️ Быстрое подтверждение временно недоступно для этого клиента.\n\n");
                customerInfo.append("💵 *Введите сумму покупки (в рублях):*");
                sendMessage(chatId, customerInfo.toString(), null, true);
            }
        } else {
            // Fast checkout выключен — только ввод суммы
            userService.updateUserState(admin, User.UserState.AWAITING_PURCHASE_AMOUNT);
            customerInfo.append("💵 *Введите сумму покупки (в рублях):*");
            sendMessage(chatId, customerInfo.toString(), null, true);
        }
    }
    
    /**
     * Создаёт inline keyboard для выбора Fast/Amount
     */
    private InlineKeyboardMarkup createFastOrAmountKeyboard(String purchaseCode) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        
        // Первый ряд: Быстро и С суммой
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        
        InlineKeyboardButton fastBtn = new InlineKeyboardButton();
        fastBtn.setText("💨 Быстро");
        fastBtn.setCallbackData(CB_FAST_CHECKOUT + purchaseCode);
        row1.add(fastBtn);
        
        InlineKeyboardButton amountBtn = new InlineKeyboardButton();
        amountBtn.setText("💵 С суммой");
        amountBtn.setCallbackData(CB_AMOUNT_CHECKOUT + purchaseCode);
        row1.add(amountBtn);
        
        keyboard.add(row1);
        
        // Второй ряд: Отмена
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton cancelBtn = new InlineKeyboardButton();
        cancelBtn.setText("❌ Отмена");
        cancelBtn.setCallbackData(CB_CANCEL_CHECKOUT + purchaseCode);
        row2.add(cancelBtn);
        
        keyboard.add(row2);
        
        markup.setKeyboard(keyboard);
        return markup;
    }
    
    /**
     * Создаёт inline keyboard для добавления заметки о клиенте
     */
    private InlineKeyboardMarkup createAddNoteKeyboard(Long customerId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        
        List<InlineKeyboardButton> row = new ArrayList<>();
        
        InlineKeyboardButton addNoteBtn = new InlineKeyboardButton();
        addNoteBtn.setText("📝 Добавить заметку");
        addNoteBtn.setCallbackData(CB_ADD_NOTE + customerId);
        row.add(addNoteBtn);
        
        InlineKeyboardButton skipBtn = new InlineKeyboardButton();
        skipBtn.setText("⏭ Пропустить");
        skipBtn.setCallbackData(CB_SKIP_NOTE);
        row.add(skipBtn);
        
        keyboard.add(row);
        markup.setKeyboard(keyboard);
        return markup;
    }
    
    private void handlePurchaseAmountInput(Long chatId, String messageText, User admin) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        // Получаем сохраненный код
        PurchaseCode purchaseCode = pendingPurchaseCodes.get(chatId);
        if (purchaseCode == null) {
            sendMessage(chatId, "❌ Ошибка: код не найден. Попробуйте еще раз.", getUserKeyboard(admin));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        try {
            // Парсим сумму покупки
            double purchaseAmount = Double.parseDouble(messageText.trim());
            
            if (purchaseAmount <= 0) {
                sendMessage(chatId, "❌ Сумма покупки должна быть больше 0. Введите корректную сумму:");
                return;
            }
            
            // Получаем клиента
            User customer = purchaseCode.getUser();
            
            // Получаем данные ДО добавления покупки
            double accumulatedBefore = userService.getAccumulatedAmount(customer);
            boolean hadDiscountBefore = customer.isDiscountValid();
            
            // Определяем максимальную доступную скидку
            double loyaltyDiscount = customer.getEffectiveDiscountPercent();
            List<DiscountCode> activePromoCodes = discountCodeService.getActiveCodesForUser(customer);
            
            double appliedDiscount = loyaltyDiscount;
            DiscountCode usedPromoCode = null;
            String discountSource = "накопительная";
            
            // Проверяем промо-коды и выбираем максимальную скидку
            for (DiscountCode promoCode : activePromoCodes) {
                double promoDiscount = promoCode.getDiscountPercent() / 100.0;
                if (promoDiscount > appliedDiscount) {
                    appliedDiscount = promoDiscount;
                    usedPromoCode = promoCode;
                    discountSource = "промо-код \"" + promoCode.getDescription() + "\"";
                }
            }
            
            // Помечаем код покупки как использованный
            purchaseCodeService.useCode(purchaseCode, admin);
            
            // Если используется промо-код - помечаем его как использованный
            if (usedPromoCode != null) {
                discountCodeService.useCode(usedPromoCode);
            }
            
            // Создаем транзакцию ПЕРВЫМ делом (до processPurchase!)
            String transactionDescription = String.format("Покупка на %.2f руб. (скидка %.0f%% - %s)", 
                purchaseAmount, appliedDiscount * 100, discountSource);
            
            transactionService.createTransaction(
                customer,
                0, // баллов нет
                Transaction.TransactionType.EARN,
                transactionDescription,
                purchaseAmount,  // Сумма покупки
                purchaseCode,
                admin
            );
            
            // Обрабатываем покупку - проверяем накопления из транзакций
            userService.processPurchase(customer);
            
            // Обновляем информацию о клиенте
            customer = userService.findByChatId(customer.getChatId()).orElseThrow();
            
            // Удаляем код из временного хранилища
            pendingPurchaseCodes.remove(chatId);
            
            // Возвращаем админа в обычное состояние
            userService.updateUserState(admin, User.UserState.REGISTERED);
            
            // Рассчитываем финальную сумму
            double finalAmount = purchaseAmount * (1 - appliedDiscount);
            double savedAmount = purchaseAmount - finalAmount;
            
            // Формируем сообщение для админа
            StringBuilder adminMessage = new StringBuilder();
            adminMessage.append("✅ *Покупка успешно обработана!*\n\n");
            adminMessage.append("👤 Клиент: ").append(customer.getFirstName()).append("\n");
            adminMessage.append("💵 Сумма покупки: ").append(String.format("%.2f", purchaseAmount)).append(" руб.\n");
            
            if (appliedDiscount > 0) {
                adminMessage.append("🎁 Применена скидка: *").append(String.format("%.0f%%", appliedDiscount * 100)).append("*");
                if (usedPromoCode != null) {
                    adminMessage.append(" (промо-код)");
                } else {
                    adminMessage.append(" (накопительная)");
                }
                adminMessage.append("\n");
                adminMessage.append("💰 Скидка: -").append(String.format("%.2f", savedAmount)).append(" руб.\n");
                adminMessage.append("💳 К оплате: *").append(String.format("%.2f", finalAmount)).append("* руб.\n\n");
            } else {
                adminMessage.append("💳 К оплате: ").append(String.format("%.2f", finalAmount)).append(" руб.\n\n");
            }
            
            double accumulatedAfter = userService.getAccumulatedAmount(customer);
            adminMessage.append("📈 Накопленная сумма: ").append(String.format("%.2f", accumulatedBefore))
                       .append(" → ").append(String.format("%.2f", accumulatedAfter)).append(" руб.\n");
            adminMessage.append("🎯 Накопительная скидка: *").append(customer.getDiscountDescription()).append("*");
            
            // Если скидка была активирована или продлена
            if (!hadDiscountBefore && customer.isDiscountValid()) {
                adminMessage.append(" 🎉 *АКТИВИРОВАНА!*");
            } else if (hadDiscountBefore && customer.isDiscountValid() && accumulatedAfter == 0.0) {
                adminMessage.append(" 🔄 *ПРОДЛЕНА!*");
            }
            
            // Отправляем сообщение с кнопкой добавления заметки
            adminMessage.append("\n\n_📝 Добавить заметку о клиенте?_");
            InlineKeyboardMarkup noteKeyboard = createAddNoteKeyboard(customer.getId());
            sendMessageWithInlineKeyboard(chatId, adminMessage.toString(), noteKeyboard);
            
            // Формируем сообщение для клиента
            StringBuilder clientMessage = new StringBuilder();
            clientMessage.append("✅ *Покупка зарегистрирована!*\n\n");
            clientMessage.append("🛍 Сумма покупки: ").append(String.format("%.2f", purchaseAmount)).append(" руб.\n");
            
            // Если была применена скидка, показываем её
            if (appliedDiscount > 0) {
                clientMessage.append("🎁 Применена скидка: *").append(String.format("%.0f%%", appliedDiscount * 100)).append("*");
                if (usedPromoCode != null) {
                    clientMessage.append(" (").append(usedPromoCode.getDescription()).append(")");
                }
                clientMessage.append("\n");
                clientMessage.append("💰 Вы экономите: ").append(String.format("%.2f", savedAmount)).append(" руб.\n");
                clientMessage.append("💳 К оплате: *").append(String.format("%.2f", finalAmount)).append("* руб.\n\n");
            } else {
                clientMessage.append("💳 К оплате: ").append(String.format("%.2f", finalAmount)).append(" руб.\n\n");
            }
            
            clientMessage.append("📊 Накоплено: *").append(String.format("%.2f", accumulatedAfter)).append("* руб.\n");
            clientMessage.append("🎯 Ваша накопительная скидка: *").append(customer.getDiscountDescription()).append("*");
            
            // Если скидка была активирована или продлена
            if (!hadDiscountBefore && customer.isDiscountValid()) {
                clientMessage.append(" 🎉 *АКТИВИРОВАНА!*\n\n");
                clientMessage.append("🔥 Поздравляем! Вы получили накопительную скидку на 30 дней!\n");
            } else if (hadDiscountBefore && customer.isDiscountValid() && accumulatedAfter == 0.0) {
                clientMessage.append(" 🔄 *ПРОДЛЕНА НА 30 ДНЕЙ!*\n\n");
            } else {
                clientMessage.append("\n\n");
            }
            
            clientMessage.append("🚀 ").append(customer.getNextDiscountLevelInfo(accumulatedAfter));
            
            sendMessage(customer.getChatId(), clientMessage.toString(), null, true);
            
        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Пожалуйста, введите корректное число (сумму покупки)");
        } catch (IllegalStateException e) {
            pendingPurchaseCodes.remove(chatId);
            sendMessage(chatId, "❌ " + e.getMessage(), getUserKeyboard(admin));
            userService.updateUserState(admin, User.UserState.REGISTERED);
        }
    }
    
    // ========== Fast Checkout Callbacks ==========
    
    /**
     * Обработка нажатия "Быстро" (fast checkout)
     */
    private void handleFastCheckoutCallback(Long chatId, String data, User admin, Integer messageId) {
        String purchaseCodeStr = data.substring(CB_FAST_CHECKOUT.length());
        PurchaseCode purchaseCode = pendingPurchaseCodes.get(chatId);
        
        if (purchaseCode == null || !purchaseCode.getCode().equals(purchaseCodeStr)) {
            editMessage(chatId, messageId, "❌ Код покупки не найден или истёк. Попробуйте снова.");
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        User customer = purchaseCode.getUser();
        
        // Проверяем cooldown и лимиты
        if (!customer.canFastCheckout(shopSettingsService.getFastCheckoutCooldownMinutes())) {
            editMessage(chatId, messageId, "❌ Слишком частые покупки. Подождите несколько минут.");
            return;
        }
        
        if (!customer.canFastCheckoutToday(shopSettingsService.getFastCheckoutDailyLimit())) {
            editMessage(chatId, messageId, "❌ Превышен дневной лимит быстрых покупок для этого клиента.");
            return;
        }
        
        try {
            // Обрабатываем fast checkout
            processFastCheckout(chatId, purchaseCode, admin, customer, messageId);
        } catch (Exception e) {
            log.error("Error processing fast checkout", e);
            editMessage(chatId, messageId, "❌ Ошибка: " + e.getMessage());
        } finally {
            pendingPurchaseCodes.remove(chatId);
            userService.updateUserState(admin, User.UserState.REGISTERED);
        }
    }
    
    /**
     * Обработка fast checkout
     */
    private void processFastCheckout(Long chatId, PurchaseCode purchaseCode, User admin, User customer, Integer messageId) {
        boolean isFirstPurchase = customer.getFirstPurchaseAt() == null;
        CustomerStatus oldStatus = customer.getCustomerStatus();
        
        // Помечаем код как использованный
        purchaseCode.setFastCheckout(true);
        purchaseCodeService.useCode(purchaseCode, admin);
        
        // Обновляем счётчик fast checkout
        customer.incrementFastCheckoutCount();
        
        // Обновляем профиль клиента
        CustomerProfileService.ProfileUpdateResult profileResult = 
            customerProfileService.recordPurchase(customer, null, true);
        
        StringBuilder adminMessage = new StringBuilder();
        StringBuilder clientMessage = new StringBuilder();
        
        adminMessage.append("✅ *Быстрая покупка подтверждена!*\n\n");
        adminMessage.append("👤 Клиент: ").append(customer.getFirstName()).append("\n");
        
        clientMessage.append("✅ *Покупка подтверждена!*\n\n");
        
        // Обрабатываем награду в зависимости от типа
        ShopSettings.FastCheckoutType rewardType = shopSettingsService.getFastCheckoutType();
        
        if (rewardType == ShopSettings.FastCheckoutType.STAMP && shopSettingsService.isStampsEnabled()) {
            // Начисляем штамп
            int stampsToAdd = shopSettingsService.getStampsPerFastPurchase();
            StampWalletService.AddStampsResult stampResult = stampWalletService.addStamps(customer, stampsToAdd);
            
            int stampsRequired = shopSettingsService.getStampsRequiredForReward();
            String stampVisual = stampWalletService.generateStampVisual(
                stampResult.wallet().getStampsCount(), stampsRequired);
            
            adminMessage.append("☕ +").append(stampsToAdd).append(" штамп\n");
            adminMessage.append(stampVisual).append("\n");
            
            clientMessage.append("☕ *+").append(stampsToAdd).append(" штамп!*\n\n");
            clientMessage.append(stampVisual).append("\n\n");
            
            if (stampResult.earnedReward()) {
                String rewardTitle = shopSettingsService.getRewardTitle();
                adminMessage.append("\n🎉 *Клиент заработал награду!*\n");
                clientMessage.append("🎉 *Поздравляем! Вы заработали награду:*\n");
                clientMessage.append("🎁 *").append(rewardTitle).append("*\n\n");
                clientMessage.append("Нажмите \"☕ Мои штампы\" для погашения!\n");
                
                // Триггер: заработана награда
                autoTriggerService.onRewardEarned(customer, rewardTitle, 
                    stampResult.wallet().getStampsCount(), stampsRequired);
            } else {
                clientMessage.append("📊 До награды: ").append(stampResult.stampsUntilNextReward())
                    .append(" ").append(getStampWord(stampResult.stampsUntilNextReward()));
                
                // Триггер: остался 1 штамп
                if (stampResult.stampsUntilNextReward() == 1) {
                    autoTriggerService.onOneStampAway(customer, shopSettingsService.getRewardTitle());
                }
            }
            
            // Создаём транзакцию
            transactionService.createTransaction(
                customer, 0, Transaction.TransactionType.EARN,
                "Быстрая покупка (+" + stampsToAdd + " штамп)",
                null, purchaseCode, admin
            );
            
        } else if (rewardType == ShopSettings.FastCheckoutType.FIXED_POINTS) {
            // Начисляем фиксированные баллы
            int points = shopSettingsService.getFastCheckoutValue();
            adminMessage.append("🎯 +").append(points).append(" баллов\n");
            clientMessage.append("🎯 *+").append(points).append(" баллов!*\n");
            
            transactionService.createTransaction(
                customer, points, Transaction.TransactionType.EARN,
                "Быстрая покупка (+" + points + " баллов)",
                null, purchaseCode, admin
            );
        }
        
        // Информация о статусе
        if (profileResult.statusChanged()) {
            adminMessage.append("\n📊 Статус: ").append(oldStatus).append(" → ")
                .append(profileResult.newStatus().getDisplayWithEmoji()).append("\n");
            
            // Триггеры статусов
            autoTriggerService.onStatusChanged(customer, oldStatus, profileResult.newStatus());
            
            // Проверяем ачивки за смену статуса
            achievementService.checkAchievementsOnStatusChange(customer, oldStatus, profileResult.newStatus());
        }
        
        // Триггер первой покупки
        if (isFirstPurchase) {
            autoTriggerService.onFirstPurchase(customer);
        }
        
        // Проверяем ачивки после покупки
        AchievementService.AchievementCheckResult achievementResult = 
            achievementService.checkAchievementsAfterPurchase(customer, null, true);
        
        if (achievementResult.hasNewAchievements()) {
            for (CustomerAchievement ca : achievementResult.newAchievements()) {
                AchievementDefinition def = ca.getAchievement();
                adminMessage.append("\n🏆 Ачивка: ").append(def.getDisplayWithEmoji());
                clientMessage.append("\n\n🏆 *Новая ачивка!*\n")
                    .append(def.getEmoji()).append(" ").append(def.getTitle());
                if (def.getDescription() != null) {
                    clientMessage.append("\n_").append(def.getDescription()).append("_");
                }
                achievementService.markNotificationSent(ca);
            }
        }
        
        // Редактируем сообщение админа
        editMessage(chatId, messageId, adminMessage.toString());
        
        // Отправляем сообщение клиенту
        sendMessage(customer.getChatId(), clientMessage.toString(), null, true);
        
        // Предлагаем добавить заметку о клиенте
        InlineKeyboardMarkup noteKeyboard = createAddNoteKeyboard(customer.getId());
        sendMessageWithInlineKeyboard(chatId, "_📝 Добавить заметку о клиенте?_", noteKeyboard);
    }
    
    /**
     * Обработка нажатия "С суммой"
     */
    private void handleAmountCheckoutCallback(Long chatId, String data, User admin, Integer messageId) {
        String purchaseCodeStr = data.substring(CB_AMOUNT_CHECKOUT.length());
        PurchaseCode purchaseCode = pendingPurchaseCodes.get(chatId);
        
        if (purchaseCode == null || !purchaseCode.getCode().equals(purchaseCodeStr)) {
            editMessage(chatId, messageId, "❌ Код покупки не найден. Попробуйте снова.");
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        // Переводим в режим ввода суммы
        userService.updateUserState(admin, User.UserState.AWAITING_PURCHASE_AMOUNT);
        editMessage(chatId, messageId, "💵 *Введите сумму покупки (в рублях):*");
    }
    
    /**
     * Обработка нажатия "Отмена"
     */
    private void handleCancelCheckoutCallback(Long chatId, String data, User admin, Integer messageId) {
        pendingPurchaseCodes.remove(chatId);
        userService.updateUserState(admin, User.UserState.REGISTERED);
        editMessage(chatId, messageId, "❌ Покупка отменена.");
        sendMessage(chatId, "Главное меню:", getUserKeyboard(admin));
    }
    
    // ========== Stamps UI ==========
    
    /**
     * Обработка кнопки "Мои штампы"
     */
    private void handleStampsButton(Long chatId, User user) {
        if (!shopSettingsService.isStampsEnabled()) {
            sendMessage(chatId, "☕ Штампы пока недоступны", getUserKeyboard(user));
            return;
        }
        
        String progressInfo = stampWalletService.getStampProgressInfo(user);
        
        Optional<StampWallet> walletOpt = stampWalletService.getWallet(user);
        boolean hasRewards = walletOpt.isPresent() && walletOpt.get().hasAvailableRewards();
        
        if (hasRewards) {
            // Показываем кнопку погашения награды
            InlineKeyboardMarkup keyboard = createRedeemKeyboard();
            sendMessageWithInlineKeyboard(chatId, progressInfo, keyboard);
        } else {
            sendMessage(chatId, progressInfo, getUserKeyboard(user), true);
        }
    }
    
    /**
     * Создаёт inline keyboard для погашения награды
     */
    private InlineKeyboardMarkup createRedeemKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        
        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton redeemBtn = new InlineKeyboardButton();
        redeemBtn.setText("🎁 Получить награду");
        redeemBtn.setCallbackData(CB_REDEEM_REWARD);
        row.add(redeemBtn);
        
        keyboard.add(row);
        markup.setKeyboard(keyboard);
        return markup;
    }
    
    /**
     * Обработка нажатия "Получить награду"
     */
    private void handleRedeemRewardCallback(Long chatId, User user) {
        try {
            RedeemCode redeemCode = stampWalletService.generateRedeemCode(user);
            
            String message = """
                🎁 *Код для получения награды:*
                
                📋 Код: *%s*
                
                🎁 Награда: *%s*
                %s
                
                ⏰ Код действителен до: %s
                
                Назовите этот код администратору для получения награды!
                """.formatted(
                    redeemCode.getCode(),
                    redeemCode.getRewardTitle(),
                    redeemCode.getRewardDescription() != null ? redeemCode.getRewardDescription() : "",
                    redeemCode.getExpiresAt().format(DATE_FORMATTER)
                );
            
            // Кнопка отмены
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> row = new ArrayList<>();
            InlineKeyboardButton cancelBtn = new InlineKeyboardButton();
            cancelBtn.setText("❌ Отменить");
            cancelBtn.setCallbackData(CB_CANCEL_REDEEM + redeemCode.getCode());
            row.add(cancelBtn);
            rows.add(row);
            keyboard.setKeyboard(rows);
            
            sendMessageWithInlineKeyboard(chatId, message, keyboard);
            
        } catch (IllegalStateException e) {
            sendMessage(chatId, "❌ " + e.getMessage(), getUserKeyboard(user));
        }
    }
    
    /**
     * Обработка отмены кода погашения
     */
    private void handleCancelRedeemCallback(Long chatId, String data, User user) {
        String code = data.substring(CB_CANCEL_REDEEM.length());
        Optional<RedeemCode> redeemCodeOpt = stampWalletService.findRedeemCode(code);
        
        if (redeemCodeOpt.isPresent() && redeemCodeOpt.get().isActive()) {
            stampWalletService.cancelRedeemCode(redeemCodeOpt.get());
            sendMessage(chatId, "❌ Код погашения отменён.", getUserKeyboard(user));
        } else {
            sendMessage(chatId, "Код уже использован или истёк.", getUserKeyboard(user));
        }
    }
    
    // ========== Admin Redeem Code Handler ==========
    
    /**
     * Обработка кнопки "Код награды" (админ)
     */
    private void handleEnterRedeemCodeButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        if (user.getState() != User.UserState.REGISTERED) {
            sendMessage(chatId, 
                "⚠️ Пожалуйста, завершите регистрацию перед использованием административных функций.");
            return;
        }
        
        userService.updateUserState(user, User.UserState.AWAITING_REDEEM_CODE);
        sendMessage(chatId, "🎁 Введите код награды от клиента:");
    }
    
    /**
     * Обработка ввода кода награды (админ)
     */
    private void handleRedeemCodeInput(Long chatId, String code, User admin) {
        try {
            StampWalletService.RedeemResult result = stampWalletService.confirmRedeem(code, admin);
            
            User customer = result.customer();
            
            // Сообщение админу
            String adminMessage = """
                ✅ *Награда погашена!*
                
                👤 Клиент: %s
                🎁 Награда: %s
                ☕ Осталось наград: %d
                """.formatted(
                    customer.getFirstName(),
                    result.rewardTitle(),
                    result.wallet().getRewardsAvailable()
                );
            
            sendMessage(chatId, adminMessage, getUserKeyboard(admin), true);
            
            // Сообщение клиенту
            autoTriggerService.onRewardRedeemed(customer, result.rewardTitle());
            
        } catch (IllegalStateException e) {
            sendMessage(chatId, "❌ " + e.getMessage(), getUserKeyboard(admin));
        } finally {
            userService.updateUserState(admin, User.UserState.REGISTERED);
        }
    }
    
    private String getStampWord(int count) {
        int abs = Math.abs(count) % 100;
        int lastDigit = abs % 10;
        
        if (abs >= 11 && abs <= 19) {
            return "штампов";
        }
        
        return switch (lastDigit) {
            case 1 -> "штамп";
            case 2, 3, 4 -> "штампа";
            default -> "штампов";
        };
    }
    
    // ========== Achievements UI ==========
    
    /**
     * Обработка кнопки "Достижения"
     */
    private void handleAchievementsButton(Long chatId, User user) {
        String achievementsInfo = achievementService.getAchievementsDisplay(user);
        sendMessage(chatId, achievementsInfo, getUserKeyboard(user), true);
    }
    
    // ========== Owner Signals (Admin) ==========
    
    /**
     * Обработка кнопки "Уведомления" (админ)
     */
    private void handleSignalsButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        List<OwnerSignal> signals = ownerSignalService.getActiveSignals();
        String display = ownerSignalService.formatSignalsForDisplay(signals, 5);
        
        // Помечаем как прочитанные
        ownerSignalService.markAllAsSeen();
        
        sendMessage(chatId, display, getUserKeyboard(user), true);
    }
    
    // ========== Message Composer (Admin) ==========
    
    /**
     * Обработка кнопки "Написать клиенту" (админ)
     */
    private void handleMessageButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        sendMessage(chatId, 
            "✉️ *Отправка сообщения клиенту*\n\n" +
            "Введите номер телефона клиента (например: +79991234567):\n\n" +
            "💡 Доступные переменные:\n" +
            "`{name}` — имя клиента\n" +
            "`{status}` — статус\n" +
            "`{stamps}` — штампов\n" +
            "`{stampsLeft}` — до награды",
            null, true);
        
        // TODO: Добавить состояние AWAITING_MESSAGE_TARGET
        // Пока упрощённая реализация без полноценного flow
    }
    
    // ========== Manual Badges (Admin) ==========
    
    /**
     * Обработка кнопки "Выдать бейдж" (админ)
     */
    private void handleBadgesButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        List<ManualBadgeDefinition> badges = manualBadgeService.getAvailableBadges();
        
        if (badges.isEmpty()) {
            sendMessage(chatId, 
                "🎖 *Выдача бейджей*\n\n" +
                "Нет доступных бейджей.\n\n" +
                "Бейджи создаются администратором базы данных.",
                getUserKeyboard(user), true);
            return;
        }
        
        StringBuilder message = new StringBuilder();
        message.append("🎖 *Выдача бейджей*\n\n");
        message.append("Введите номер телефона клиента для выдачи бейджа:\n\n");
        message.append("*Доступные бейджи:*\n");
        
        for (ManualBadgeDefinition badge : badges) {
            message.append(badge.getEmoji()).append(" ").append(badge.getTitle());
            if (badge.getPerkType() != ManualBadgeDefinition.PerkType.NONE) {
                message.append(" — _").append(badge.getPerkDescription()).append("_");
            }
            message.append("\n");
        }
        
        sendMessage(chatId, message.toString(), getUserKeyboard(user), true);
        
        // TODO: добавить состояние AWAITING_BADGE_TARGET
    }
    
    /**
     * Callback для выдачи бейджа
     */
    private void handleAwardBadgeCallback(Long chatId, String data, User admin, Integer messageId) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        // Формат: award_badge:<badgeId>:<customerChatId>
        String[] parts = data.substring(CB_AWARD_BADGE.length()).split(":");
        if (parts.length < 2) {
            editMessage(chatId, messageId, "❌ Неверный формат данных");
            return;
        }
        
        try {
            Long badgeId = Long.parseLong(parts[0]);
            Long customerChatId = Long.parseLong(parts[1]);
            
            Optional<ManualBadgeDefinition> badgeOpt = manualBadgeService.getBadgeById(badgeId);
            Optional<User> customerOpt = userService.findByChatId(customerChatId);
            
            if (badgeOpt.isEmpty() || customerOpt.isEmpty()) {
                editMessage(chatId, messageId, "❌ Бейдж или клиент не найден");
                return;
            }
            
            ManualBadgeService.AwardResult result = manualBadgeService.awardBadge(
                customerOpt.get(), badgeOpt.get(), admin, null);
            
            if (result.success()) {
                CustomerBadge awarded = result.badge();
                
                editMessage(chatId, messageId, 
                    "✅ *Бейдж выдан!*\n\n" +
                    "👤 Клиент: " + customerOpt.get().getFirstName() + "\n" +
                    awarded.getFormattedDisplay());
                
                // Отправляем уведомление клиенту
                sendMessage(customerOpt.get().getChatId(), 
                    "🎉 *Поздравляем! Вы получили бейдж!*\n\n" +
                    awarded.getFormattedDisplay(),
                    null, true);
                
                manualBadgeService.markNotificationSent(awarded);
            } else {
                editMessage(chatId, messageId, "❌ " + result.error());
            }
            
        } catch (NumberFormatException e) {
            editMessage(chatId, messageId, "❌ Неверный формат данных");
        }
    }
    
    /**
     * Callback для отзыва бейджа
     */
    private void handleRevokeBadgeCallback(Long chatId, String data, User admin, Integer messageId) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        // Формат: revoke_badge:<customerBadgeId>
        try {
            Long badgeId = Long.parseLong(data.substring(CB_REVOKE_BADGE.length()));
            // TODO: реализовать отзыв бейджа
            editMessage(chatId, messageId, "❌ Функция отзыва бейджа в разработке");
        } catch (NumberFormatException e) {
            editMessage(chatId, messageId, "❌ Неверный формат данных");
        }
    }
    
    // ========== Client Notes (память о клиентах) ==========
    
    /**
     * Обработка нажатия "Добавить заметку"
     */
    private void handleAddNoteCallback(Long chatId, String data, User admin, Integer messageId) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        try {
            // Формат: add_note:<customerId> (это database ID)
            Long customerId = Long.parseLong(data.substring(CB_ADD_NOTE.length()));
            
            // Находим клиента по database ID
            Optional<User> customerOpt = userService.findById(customerId);
            if (customerOpt.isEmpty()) {
                editMessage(chatId, messageId, "❌ Клиент не найден");
                return;
            }
            
            User customer = customerOpt.get();
            
            // Сохраняем клиента для которого пишем заметку
            pendingClientNotes.put(chatId, customer);
            
            // Переводим в состояние ожидания заметки
            userService.updateUserState(admin, User.UserState.AWAITING_CLIENT_NOTE);
            
            // Редактируем сообщение
            editMessage(chatId, messageId, "📝 *Добавление заметки*\n\n" +
                "Клиент: " + customer.getFirstName() + "\n\n" +
                "Введите короткую заметку о клиенте (до 200 символов):\n\n" +
                "_Примеры:_\n" +
                "• Любит металл\n" +
                "• Миндальное молоко\n" +
                "• Берёт подарки");
            
        } catch (NumberFormatException e) {
            editMessage(chatId, messageId, "❌ Неверный формат данных");
        }
    }
    
    /**
     * Обработка нажатия "Пропустить" (не добавлять заметку)
     */
    private void handleSkipNoteCallback(Long chatId, User admin, Integer messageId) {
        // Просто удаляем/редактируем сообщение
        editMessage(chatId, messageId, "✅ Покупка обработана");
        sendMessage(chatId, "📋 Главное меню", getUserKeyboard(admin));
    }
    
    /**
     * Обработка ввода текста заметки о клиенте
     */
    private void handleClientNoteInput(Long chatId, String noteText, User admin) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        User customer = pendingClientNotes.get(chatId);
        if (customer == null) {
            sendMessage(chatId, "❌ Ошибка: клиент не найден. Попробуйте снова.", getUserKeyboard(admin));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        try {
            // Проверяем длину
            if (noteText == null || noteText.trim().isEmpty()) {
                sendMessage(chatId, "❌ Заметка не может быть пустой. Введите текст заметки:");
                return;
            }
            
            String trimmedNote = noteText.trim();
            if (trimmedNote.length() > ClientNote.MAX_TEXT_LENGTH) {
                sendMessage(chatId, String.format(
                    "⚠️ Заметка слишком длинная (максимум %d символов). Сейчас: %d\n\n" +
                    "Введите более короткую заметку:",
                    ClientNote.MAX_TEXT_LENGTH, trimmedNote.length()));
                return;
            }
            
            // Сохраняем заметку
            clientMemoryService.addNote(customer, trimmedNote, admin);
            
            // Очищаем временное хранилище
            pendingClientNotes.remove(chatId);
            
            // Возвращаем в обычное состояние
            userService.updateUserState(admin, User.UserState.REGISTERED);
            
            sendMessage(chatId, 
                "✅ *Заметка добавлена!*\n\n" +
                "👤 Клиент: " + customer.getFirstName() + "\n" +
                "📝 Заметка: _" + trimmedNote + "_\n\n" +
                "Эта заметка будет показана при следующем визите клиента.",
                getUserKeyboard(admin), true);
            
        } catch (Exception e) {
            log.error("Error adding client note", e);
            sendMessage(chatId, "❌ Ошибка: " + e.getMessage(), getUserKeyboard(admin));
            pendingClientNotes.remove(chatId);
            userService.updateUserState(admin, User.UserState.REGISTERED);
        }
    }
    
    /**
     * Обработка кнопки "Мои бейджи" (клиент)
     */
    private void handleMyBadgesButton(Long chatId, User user) {
        String badgesInfo = manualBadgeService.formatBadgesForDisplay(user);
        sendMessage(chatId, badgesInfo, getUserKeyboard(user), true);
    }
    
    // ========== Reports (Admin) ==========
    
    /**
     * Обработка кнопки "Отчёты" (админ)
     */
    private void handleReportsButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        // Быстрая статистика
        String quickStats = weeklyReportService.getQuickStats();
        
        // Кнопки для выбора отчёта
        InlineKeyboardMarkup keyboard = createReportKeyboard();
        
        sendMessageWithInlineKeyboard(chatId, 
            quickStats + "\n\n*Выберите отчёт:*", 
            keyboard);
    }
    
    /**
     * Создаёт inline keyboard для выбора отчёта
     */
    private InlineKeyboardMarkup createReportKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton dailyBtn = new InlineKeyboardButton();
        dailyBtn.setText("📅 Сегодня");
        dailyBtn.setCallbackData(CB_REPORT_DAILY);
        row1.add(dailyBtn);
        
        InlineKeyboardButton weeklyBtn = new InlineKeyboardButton();
        weeklyBtn.setText("📆 Неделя");
        weeklyBtn.setCallbackData(CB_REPORT_WEEKLY);
        row1.add(weeklyBtn);
        
        InlineKeyboardButton monthlyBtn = new InlineKeyboardButton();
        monthlyBtn.setText("📊 Месяц");
        monthlyBtn.setCallbackData(CB_REPORT_MONTHLY);
        row1.add(monthlyBtn);
        
        keyboard.add(row1);
        markup.setKeyboard(keyboard);
        return markup;
    }
    
    /**
     * Callback для генерации отчёта
     */
    private void handleReportCallback(Long chatId, String reportType, User user, Integer messageId) {
        if (user.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        String report = switch (reportType) {
            case "daily" -> weeklyReportService.buildDailyReport();
            case "weekly" -> weeklyReportService.buildWeeklyReport();
            case "monthly" -> weeklyReportService.buildMonthlyReport();
            default -> "❌ Неизвестный тип отчёта";
        };
        
        editMessage(chatId, messageId, report);
    }
    
    // ========== Channel (Client) ==========
    
    /**
     * Обработка кнопки "Новости и акции"
     */
    private void handleChannelButton(Long chatId, User user) {
        String channelUrl = shopSettingsService.getChannelUrl();
        
        if (channelUrl == null || channelUrl.isEmpty()) {
            sendMessage(chatId, "📢 Канал с новостями пока не настроен", getUserKeyboard(user));
            return;
        }
        
        // Кнопка-ссылка на канал
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        
        InlineKeyboardButton channelBtn = new InlineKeyboardButton();
        channelBtn.setText("📢 Открыть канал");
        channelBtn.setUrl(channelUrl);
        row.add(channelBtn);
        
        rows.add(row);
        keyboard.setKeyboard(rows);
        
        sendMessageWithInlineKeyboard(chatId, 
            "📢 *Новости и акции*\n\n" +
            "Подпишитесь на наш канал, чтобы узнавать о скидках, акциях и новинках первыми!",
            keyboard);
    }
    
    private void handleSendDiscountButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        // Проверяем, что админ завершил регистрацию
        if (user.getState() != User.UserState.REGISTERED) {
            sendMessage(chatId, 
                "⚠️ Пожалуйста, завершите регистрацию перед использованием административных функций.\n\n" +
                "Отправьте /start и зарегистрируйте свой номер телефона.");
            return;
        }
        
        // Инициализируем создание новой промо-акции
        pendingPromotions.put(chatId, new PromotionBuilder());
        userService.updateUserState(user, User.UserState.AWAITING_PROMOTION_DISCOUNT);
        
        sendMessage(chatId, 
            "📢 *Создание промо-акции*\n\n" +
            "Акция будет доступна всем пользователям (включая новых) и каждый сможет использовать её только один раз.\n\n" +
            "📝 Шаг 1/3: Введите процент скидки (например: 10):", 
            null, true);
    }
    
    private void handlePromotionDiscountInput(Long chatId, String messageText, User admin) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        PromotionBuilder builder = pendingPromotions.get(chatId);
        if (builder == null) {
            sendMessage(chatId, "❌ Ошибка: данные акции не найдены. Попробуйте еще раз.", getUserKeyboard(admin));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        try {
            int discountPercent = Integer.parseInt(messageText.trim());
            
            if (discountPercent <= 0 || discountPercent > 100) {
                sendMessage(chatId, "❌ Процент скидки должен быть от 1 до 100. Введите корректное значение:");
                return;
            }
            
            builder.discountPercent = discountPercent;
            userService.updateUserState(admin, User.UserState.AWAITING_PROMOTION_DURATION);
            
            sendMessage(chatId, 
                "✅ Скидка: " + discountPercent + "%\n\n" +
                "📝 Шаг 2/3: Введите срок действия акции в днях (например: 7 для недели):");
            
        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Пожалуйста, введите корректное число (процент скидки):");
        }
    }
    
    private void handlePromotionDurationInput(Long chatId, String messageText, User admin) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        PromotionBuilder builder = pendingPromotions.get(chatId);
        if (builder == null) {
            sendMessage(chatId, "❌ Ошибка: данные акции не найдены. Попробуйте еще раз.", getUserKeyboard(admin));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        try {
            int durationDays = Integer.parseInt(messageText.trim());
            
            if (durationDays <= 0 || durationDays > 365) {
                sendMessage(chatId, "❌ Срок действия должен быть от 1 до 365 дней. Введите корректное значение:");
                return;
            }
            
            builder.durationDays = durationDays;
            userService.updateUserState(admin, User.UserState.AWAITING_PROMOTION_DESCRIPTION);
            
            sendMessage(chatId, 
                "✅ Скидка: " + builder.discountPercent + "%\n" +
                "✅ Срок действия: " + durationDays + " дней\n\n" +
                "📝 Шаг 3/3: Введите описание акции (например: \"Осенняя распродажа\"):");
            
        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Пожалуйста, введите корректное число (количество дней):");
        }
    }
    
    private void handlePromotionDescriptionInput(Long chatId, String messageText, User admin) {
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        PromotionBuilder builder = pendingPromotions.get(chatId);
        if (builder == null) {
            sendMessage(chatId, "❌ Ошибка: данные акции не найдены. Попробуйте еще раз.", getUserKeyboard(admin));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        String description = messageText.trim();
        if (description.isEmpty()) {
            sendMessage(chatId, "❌ Описание не может быть пустым. Введите описание акции:");
            return;
        }
        
        builder.description = description;
        
        // Создаем промо-акцию
        try {
            java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now()
                .plusDays(builder.durationDays);
            
            Promotion promotion = promotionService.createPromotion(
                builder.discountPercent,
                builder.description,
                expiresAt,
                admin
            );
            
            // Получаем количество пользователей для отчета
            List<User> users = userService.findAllRegisteredUsers();
            
            // Очищаем временные данные
            pendingPromotions.remove(chatId);
            userService.updateUserState(admin, User.UserState.REGISTERED);
            
            sendMessage(chatId, 
                "✅ *Промо-акция успешно создана!*\n\n" +
                "🎁 Скидка: *" + builder.discountPercent + "%*\n" +
                "📝 Описание: " + builder.description + "\n" +
                "⏰ Действует до: " + expiresAt.format(DATE_FORMATTER) + "\n" +
                "👥 Скидка отправлена: *" + users.size() + "* пользователям\n\n" +
                "💡 Новые пользователи будут автоматически получать эту скидку при регистрации!",
                getUserKeyboard(admin), true);
            
            // Отправляем уведомления всем пользователям
            for (User user : users) {
                try {
                    List<DiscountCode> userCodes = discountCodeService.getActiveCodesForUser(user);
                    // Находим только что созданный код для этого пользователя
                    DiscountCode newCode = userCodes.stream()
                        .filter(code -> code.getDescription().equals(description))
                        .findFirst()
                        .orElse(null);
                    
                    if (newCode != null) {
                        sendMessage(user.getChatId(),
                            "🎉 *Новая акция!*\n\n" +
                            "🎁 " + description + "\n" +
                            "💰 Скидка: *" + builder.discountPercent + "%*\n" +
                            "⏰ Действует до: " + expiresAt.format(DATE_FORMATTER) + "\n\n" +
                            "💡 Скидка применится автоматически при покупке!\n" +
                            "🔥 Действует только один раз!",
                            null, true);
                    }
                } catch (Exception e) {
                    log.error("Failed to send promo notification to user chatId={}", user.getChatId(), e);
                }
            }
            
            log.info("Promotion id={} created and notifications sent to {} users", 
                promotion.getId(), users.size());
            
        } catch (Exception e) {
            log.error("Error creating promotion", e);
            pendingPromotions.remove(chatId);
            userService.updateUserState(admin, User.UserState.REGISTERED);
            sendMessage(chatId, "❌ Ошибка при создании акции: " + e.getMessage(), getUserKeyboard(admin));
        }
    }
    
    private void handlePromotionsButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        // Проверяем, что админ завершил регистрацию
        if (user.getState() != User.UserState.REGISTERED) {
            sendMessage(chatId, 
                "⚠️ Пожалуйста, завершите регистрацию перед использованием административных функций.");
            return;
        }
        
        List<Promotion> activePromotions = promotionService.getActivePromotions();
        
        if (activePromotions.isEmpty()) {
            sendMessage(chatId, "🎁 Нет активных акций\n\nСоздайте новую акцию через кнопку \"📢 Отправить скидку\"", getUserKeyboard(user));
            return;
        }
        
        StringBuilder message = new StringBuilder("🎁 *Активные промо-акции:*\n\n");
        
        for (Promotion promo : activePromotions) {
            message.append("📌 *").append(promo.getDescription()).append("*\n");
            message.append("💰 Скидка: ").append(promo.getDiscountPercent()).append("%\n");
            message.append("⏰ Действует до: ").append(promo.getExpiresAt().format(DATE_FORMATTER)).append("\n");
            message.append("👤 Создал: ").append(promo.getCreatedBy().getFirstName()).append("\n");
            message.append("\n");
        }
        
        message.append("💡 Новые пользователи автоматически получают коды всех активных акций при регистрации.");
        
        sendMessage(chatId, message.toString(), getUserKeyboard(user), true);
    }
    
    private void handleStatsButton(Long chatId, User user) {
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(chatId, "❌ Эта функция доступна только администраторам");
            return;
        }
        
        // Проверяем, что админ завершил регистрацию
        if (user.getState() != User.UserState.REGISTERED) {
            sendMessage(chatId, 
                "⚠️ Пожалуйста, завершите регистрацию перед использованием административных функций.");
            return;
        }
        
        // Получаем статистику
        UserService.UserStats stats = userService.getUserStats();
        
        // Получаем статистику по активным промо-акциям
        List<Promotion> activePromotions = promotionService.getActivePromotions();
        
        // Формируем сообщение
        StringBuilder message = new StringBuilder();
        message.append("📊 *Статистика системы*\n\n");
        
        // Статистика пользователей
        message.append("👥 *Пользователи:*\n");
        message.append("  • Всего пользователей: *").append(stats.totalUsers).append("*\n");
        message.append("  • Зарегистрированных: *").append(stats.registeredUsers).append("*\n");
        message.append("  • Администраторов: *").append(stats.admins).append("*\n\n");
        
        // Распределение по уровням скидок
        message.append("🎯 *Активные скидки:*\n");
        message.append("  • Без скидки: *").append(stats.noDiscountUsers).append("* чел.\n");
        message.append("  • 5% скидка: *").append(stats.discount5Users).append("* чел.\n");
        message.append("  • 7% скидка: *").append(stats.discount7Users).append("* чел.\n");
        message.append("  • 10% скидка: *").append(stats.discount10Users).append("* чел.\n\n");
        
        // Продажи за текущий месяц
        message.append("💰 *Продажи за текущий месяц:*\n");
        message.append("  • Общая сумма: *").append(String.format("%.2f", stats.totalMonthlyAmount)).append("* руб.\n");
        if (stats.registeredUsers > 0) {
            double avgPerUser = stats.totalMonthlyAmount / stats.registeredUsers;
            message.append("  • Средняя на пользователя: *").append(String.format("%.2f", avgPerUser)).append("* руб.\n");
        }
        message.append("\n");
        
        // Статистика промо-акций
        message.append("🎁 *Промо-акции:*\n");
        message.append("  • Активных акций: *").append(activePromotions.size()).append("*\n\n");
        
        // Примечание о системе накопления
        message.append("ℹ️ *Система накопления:*\n");
        message.append("  • Скидки действуют 30 дней\n");
        message.append("  • Продление при повторном накоплении\n");
        
        sendMessage(chatId, message.toString(), getUserKeyboard(user), true);
    }
    
    private void handleMakeAdminCommand(Long chatId, String messageText, Optional<User> userOpt) {
        if (userOpt.isEmpty()) {
            sendMessage(chatId, "⚠️ Сначала зарегистрируйтесь в боте командой /start");
            return;
        }
        
        User user = userOpt.get();
        
        // Проверяем, что пользователь прошел полную регистрацию
        if (user.getState() != User.UserState.REGISTERED || 
            user.getPhoneNumber() == null || 
            user.getPhoneNumber().trim().isEmpty()) {
            sendMessage(chatId, 
                "⚠️ Для получения прав администратора необходимо сначала завершить регистрацию!\n\n" +
                "Пожалуйста, отправьте /start и зарегистрируйте свой номер телефона.");
            return;
        }
        
        if (user.getRole() == User.UserRole.ADMIN) {
            sendMessage(chatId, "✅ Вы уже являетесь администратором!");
            return;
        }
        
        if (adminSecret == null || adminSecret.isEmpty()) {
            sendMessage(chatId, 
                "❌ Секретный код не настроен.\n\n" +
                "Для назначения администратора обратитесь к владельцу системы или " +
                "используйте SQL запрос через H2 Console.\n\n" +
                "Подробнее: см. файл ADMIN_SETUP.md");
            return;
        }
        
        String[] parts = messageText.trim().split("\\s+", 2);
        if (parts.length < 2) {
            sendMessage(chatId, 
                "❌ Неверный формат команды.\n\n" +
                "Используйте: /makeadmin СЕКРЕТНЫЙ_КОД");
            return;
        }
        
        String providedCode = parts[1];
        
        if (!adminSecret.equals(providedCode)) {
            log.warn("Failed admin promotion attempt for chatId={}, provided code={}", 
                chatId, providedCode);
            sendMessage(chatId, "❌ Неверный секретный код!");
            return;
        }
        
        try {
            User updatedUser = userService.setUserRole(user, User.UserRole.ADMIN);
            log.info("User chatId={} phone={} promoted to ADMIN", 
                updatedUser.getChatId(), updatedUser.getPhoneNumber());
            
            sendMessage(chatId, 
                "✅ Поздравляем! Вы назначены администратором!\n\n" +
                "🎉 Теперь у вас есть доступ к административным функциям.\n\n" +
                "Отправьте /start чтобы увидеть новое меню.",
                getUserKeyboard(updatedUser));
        } catch (Exception e) {
            log.error("Error promoting user to admin", e);
            sendMessage(chatId, "❌ Ошибка при назначении администратором");
        }
    }
    
    /**
     * Проверяет, является ли строка валидным номером телефона
     * Отсеивает коды покупки и другие короткие числовые строки
     */
    private boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return false;
        }
        
        // Убираем + в начале для подсчета цифр
        String digitsOnly = phoneNumber.replaceAll("\\+", "");
        
        // Минимум 10 цифр (чтобы отсеять 6-значные коды покупки)
        // Максимум 15 цифр (международный стандарт E.164)
        int length = digitsOnly.length();
        if (length < 10 || length > 15) {
            return false;
        }
        
        // Проверяем, что строка содержит только цифры (после удаления +)
        if (!digitsOnly.matches("^\\d+$")) {
            return false;
        }
        
        // Дополнительная проверка для российских номеров
        // Должны начинаться с +7, 7, 8 или другого кода страны
        if (phoneNumber.startsWith("+7") || phoneNumber.startsWith("7") || phoneNumber.startsWith("8")) {
            // Российский номер должен быть 11 цифр (8XXXXXXXXXX) или +7XXXXXXXXXX
            return digitsOnly.length() == 11;
        }
        
        // Для других стран - просто проверяем длину
        return true;
    }
    
    private ReplyKeyboardMarkup getUserKeyboard(User user) {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        
        List<KeyboardRow> rows = new ArrayList<>();
        
        if (user.getRole() == User.UserRole.ADMIN) {
            // Админская клавиатура
            KeyboardRow row1 = new KeyboardRow();
            row1.add(new KeyboardButton(BTN_ENTER_CODE));
            
            // Кнопка для кода награды (если штампы включены)
            if (shopSettingsService.isStampsEnabled()) {
                row1.add(new KeyboardButton(BTN_ENTER_REDEEM));
            } else {
                row1.add(new KeyboardButton(BTN_STATS));
            }
            
            KeyboardRow row2 = new KeyboardRow();
            row2.add(new KeyboardButton(BTN_SEND_DISCOUNT));
            row2.add(new KeyboardButton(BTN_PROMOTIONS));
            
            KeyboardRow row3 = new KeyboardRow();
            // Показываем количество непрочитанных сигналов
            long unreadSignals = ownerSignalService.countUnseenSignals();
            String signalsBtn = unreadSignals > 0 
                ? BTN_SIGNALS + " (" + unreadSignals + ")" 
                : BTN_SIGNALS;
            row3.add(new KeyboardButton(signalsBtn));
            row3.add(new KeyboardButton(BTN_REPORTS));
            
            KeyboardRow row4 = new KeyboardRow();
            row4.add(new KeyboardButton(BTN_BADGES));
            row4.add(new KeyboardButton(BTN_MESSAGE));
            
            KeyboardRow row5 = new KeyboardRow();
            row5.add(new KeyboardButton(BTN_STATS));
            
            rows.add(row1);
            rows.add(row2);
            rows.add(row3);
            rows.add(row4);
            rows.add(row5);
        } else {
            // Клавиатура обычного пользователя
            KeyboardRow row1 = new KeyboardRow();
            row1.add(new KeyboardButton(BTN_PURCHASE));
            row1.add(new KeyboardButton(BTN_MY_STATUS));
            
            KeyboardRow row2 = new KeyboardRow();
            
            // Показываем штампы если включены
            if (shopSettingsService.isStampsEnabled()) {
                row2.add(new KeyboardButton(BTN_STAMPS));
            } else {
                row2.add(new KeyboardButton(BTN_HISTORY));
            }
            
            // Показываем скидки если включены
            if (shopSettingsService.isDiscountTiersEnabled()) {
                row2.add(new KeyboardButton(BTN_DISCOUNTS));
            } else if (!shopSettingsService.isStampsEnabled()) {
                // Если ничего не включено, показываем историю
                row2.add(new KeyboardButton(BTN_HISTORY));
            }
            
            rows.add(row1);
            rows.add(row2);
            
            // Третий ряд: достижения и бейджи
            KeyboardRow row3 = new KeyboardRow();
            row3.add(new KeyboardButton(BTN_ACHIEVEMENTS));
            row3.add(new KeyboardButton(BTN_MY_BADGES));
            rows.add(row3);
            
            // Четвёртый ряд: история и канал (если настроен)
            KeyboardRow row4 = new KeyboardRow();
            row4.add(new KeyboardButton(BTN_HISTORY));
            if (shopSettingsService.getChannelUrl() != null && !shopSettingsService.getChannelUrl().isEmpty()) {
                row4.add(new KeyboardButton(BTN_CHANNEL));
            }
            rows.add(row4);
        }
        
        keyboard.setKeyboard(rows);
        return keyboard;
    }
    
    private ReplyKeyboardMarkup getPhoneRequestKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);
        
        KeyboardRow row = new KeyboardRow();
        KeyboardButton button = new KeyboardButton("📱 Отправить номер телефона");
        button.setRequestContact(true);
        row.add(button);
        
        keyboard.setKeyboard(List.of(row));
        return keyboard;
    }
    
    private void sendMessage(Long chatId, String text) {
        sendMessage(chatId, text, null, false);
    }
    
    private void sendMessage(Long chatId, String text, ReplyKeyboardMarkup keyboard) {
        sendMessage(chatId, text, keyboard, false);
    }
    
    private void sendMessage(Long chatId, String text, ReplyKeyboardMarkup keyboard, boolean markdown) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        
        if (markdown) {
            message.setParseMode("Markdown");
        }
        
        if (keyboard != null) {
            message.setReplyMarkup(keyboard);
        }
        
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
    }
    
    /**
     * Отправляет сообщение с inline keyboard
     */
    private void sendMessageWithInlineKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");
        message.setReplyMarkup(keyboard);
        
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message with inline keyboard", e);
        }
    }
    
    /**
     * Редактирует существующее сообщение
     */
    private void editMessage(Long chatId, Integer messageId, String text) {
        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(chatId.toString());
        editMessage.setMessageId(messageId);
        editMessage.setText(text);
        editMessage.setParseMode("Markdown");
        
        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message", e);
            // Fallback: отправляем новое сообщение
            sendMessage(chatId, text, null, true);
        }
    }
}
