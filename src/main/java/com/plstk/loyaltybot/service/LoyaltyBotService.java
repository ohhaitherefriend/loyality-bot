package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.config.CommerceProperties;
import com.plstk.loyaltybot.entity.*;
import com.plstk.loyaltybot.service.commerce.CommerceBotService;
import com.plstk.loyaltybot.telegram.TelegramApiClient;
import com.plstk.loyaltybot.telegram.TelegramContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Основной сервис обработки Telegram Updates.
 * Работает с TelegramContext для поддержки multi-tenant.
 * 
 * Логика взята из LoyaltyBot, адаптирована для работы с контекстом.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyBotService {
    
    private final TelegramApiClient telegramApiClient;
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
    private final BonusService bonusService;
    private final CommerceBotService commerceBotService;
    private final CommerceProperties commerceProperties;
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    
    // Временные хранилища (в production лучше использовать Redis)
    private final Map<String, PurchaseCode> pendingPurchaseCodes = new HashMap<>();
    private final Map<String, User> pendingClientNotes = new HashMap<>();
    
    // Кнопки для пользователя
    private static final String BTN_PURCHASE = "🛍 Я совершаю покупку";
    private static final String BTN_MY_STATUS = "📊 Мой статус";
    private static final String BTN_HISTORY = "📜 История покупок";
    private static final String BTN_DISCOUNTS = "🎁 Мои скидки";
    private static final String BTN_STAMPS = "☕ Мои штампы";
    private static final String BTN_ACHIEVEMENTS = "🏆 Достижения";
    private static final String BTN_OPEN_SHOP = "🛍 Открыть магазин";
    private static final String BTN_CATALOG = "🛒 Каталог в боте";
    
    // Кнопки для админа
    private static final String BTN_ENTER_CODE = "🔑 Ввести код покупки";
    private static final String BTN_ENTER_REDEEM = "🎁 Код награды";
    private static final String BTN_SEND_DISCOUNT = "📢 Отправить скидку";
    private static final String BTN_PROMOTIONS = "🎁 Активные акции";
    private static final String BTN_STATS = "📊 Статистика";
    
    // Callback data prefixes
    private static final String CB_FAST_CHECKOUT = "fast_checkout:";
    private static final String CB_AMOUNT_CHECKOUT = "amount_checkout:";
    private static final String CB_CANCEL_CHECKOUT = "cancel_checkout:";
    private static final String CB_REDEEM_REWARD = "redeem_reward";
    
    /**
     * Основной метод обработки Update.
     */
    public void processUpdate(TelegramContext ctx) {
        Update update = ctx.getUpdate();
        String shopId = ctx.getShopId();
        
        try {
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(ctx, update.getCallbackQuery());
            } else if (update.hasMessage()) {
                handleMessage(ctx, update.getMessage());
            }
        } catch (Exception e) {
            log.error("Error processing update for shopId={}, chatId={}", shopId, ctx.getChatId(), e);
            sendErrorMessage(ctx, "Произошла ошибка. Попробуйте позже.");
        }
    }
    
    /**
     * Обработка callback кнопок
     */
    private void handleCallbackQuery(TelegramContext ctx, CallbackQuery callbackQuery) {
        Long chatId = callbackQuery.getMessage().getChatId();
        String data = callbackQuery.getData();
        Integer messageId = callbackQuery.getMessage().getMessageId();
        
        // Находим пользователя по chatId + shopId
        Optional<User> userOpt = userService.findByChatIdAndShopId(chatId, ctx.getShopId());
        if (userOpt.isEmpty()) {
            return;
        }
        User user = userOpt.get();
        
        try {
            if (commerceBotService.isCommerceCallback(data)) {
                commerceBotService.handleCallback(ctx, callbackQuery, user);
            } else if (data.startsWith(CB_FAST_CHECKOUT)) {
                handleFastCheckoutCallback(ctx, data, user, messageId);
            } else if (data.startsWith(CB_AMOUNT_CHECKOUT)) {
                handleAmountCheckoutCallback(ctx, data, user, messageId);
            } else if (data.startsWith(CB_CANCEL_CHECKOUT)) {
                handleCancelCheckoutCallback(ctx, data, user, messageId);
            } else if (data.equals(CB_REDEEM_REWARD)) {
                handleRedeemRewardCallback(ctx, user);
            }
            // Добавьте остальные callback handlers по необходимости
            
            // Отвечаем на callback query
            telegramApiClient.answerCallbackQuery(ctx.getBotToken(), callbackQuery.getId(), null, false);
            
        } catch (Exception e) {
            log.error("Error handling callback query: {}", data, e);
            sendMessage(ctx, chatId, "❌ " + e.getMessage(), getUserKeyboard(user, ctx.getShopId()));
        }
    }
    
    /**
     * Обработка сообщений
     */
    private void handleMessage(TelegramContext ctx, Message message) {
        Long chatId = message.getChatId();
        String messageText = message.getText();
        String shopId = ctx.getShopId();
        
        // Находим пользователя
        Optional<User> userOpt = userService.findByChatIdAndShopId(chatId, shopId);
        
        // Команда /start
        if (messageText != null && messageText.startsWith("/start")) {
            handleStartCommand(ctx, message, userOpt, messageText);
            return;
        }
        
        if (userOpt.isEmpty()) {
            sendMessage(ctx, chatId, "⚠️ Пожалуйста, начните с команды /start", null);
            return;
        }
        
        User user = userOpt.get();
        
        // Обработка состояний пользователя
        if (user.getState() == User.UserState.AWAITING_PHONE) {
            handlePhoneNumber(ctx, message, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_ADMIN_CODE) {
            handleAdminCodeInput(ctx, messageText, user);
            return;
        }
        
        if (user.getState() == User.UserState.AWAITING_PURCHASE_AMOUNT) {
            handlePurchaseAmountInput(ctx, messageText, user);
            return;
        }

        if (user.getState() == User.UserState.AWAITING_ORDER_ADDRESS) {
            if (commerceBotService.handleMessage(ctx, user, messageText)) {
                return;
            }
        }

        if (messageText != null && (BTN_OPEN_SHOP.equals(messageText) || "/shop".equals(messageText))) {
            handleOpenShopButton(ctx, user);
            return;
        }

        if (commerceBotService.handleMessage(ctx, user, messageText)) {
            return;
        }
        
        // Обработка кнопок меню
        if (messageText != null) {
            switch (messageText) {
                case BTN_PURCHASE -> handlePurchaseButton(ctx, user);
                case BTN_MY_STATUS -> handleMyStatusButton(ctx, user);
                case BTN_HISTORY -> handleHistoryButton(ctx, user);
                case BTN_DISCOUNTS -> handleDiscountsButton(ctx, user);
                case BTN_STAMPS -> handleStampsButton(ctx, user);
                case BTN_ACHIEVEMENTS -> handleAchievementsButton(ctx, user);
                case BTN_ENTER_CODE -> handleEnterCodeButton(ctx, user);
                case BTN_ENTER_REDEEM -> handleEnterRedeemCodeButton(ctx, user);
                case BTN_SEND_DISCOUNT -> handleSendDiscountButton(ctx, user);
                case BTN_PROMOTIONS -> handlePromotionsButton(ctx, user);
                case BTN_STATS -> handleStatsButton(ctx, user);
                default -> sendMessage(ctx, chatId, "Используйте кнопки меню для навигации", 
                    getUserKeyboard(user, shopId));
            }
        }
    }
    
    // ========== Command Handlers ==========
    
    private void handleStartCommand(TelegramContext ctx, Message message, Optional<User> userOpt, String messageText) {
        Long chatId = message.getChatId();
        String shopId = ctx.getShopId();
        
        // Проверяем deep-link параметр
        String deepLinkParam = null;
        if (messageText.length() > 7) {
            deepLinkParam = messageText.substring(7).trim();
        }
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            commerceBotService.resetCheckoutState(ctx.getShopId(), chatId, user);
            
            // Обработка deep-link для покупки
            if (deepLinkParam != null && deepLinkParam.startsWith("buy_")) {
                handleBuyDeepLink(ctx, user, deepLinkParam);
                return;
            }
            
            sendMessage(ctx, chatId, "С возвращением, " + user.getFirstName() + "!", 
                getUserKeyboard(user, shopId));
        } else {
            // Регистрация нового пользователя - используем кастомное сообщение
            String welcomeMsg = getCustomMessage(shopId, "welcome", message.getFrom().getFirstName());
            sendMessage(ctx, chatId, welcomeMsg, getPhoneRequestKeyboard());
            
            User newUser = User.builder()
                .chatId(chatId)
                .shopId(shopId)  // Multi-tenant!
                .phoneNumber("")
                .firstName(message.getFrom().getFirstName())
                .lastName(message.getFrom().getLastName())
                .username(message.getFrom().getUserName())
                .role(User.UserRole.USER)
                .state(User.UserState.AWAITING_PHONE)
                .build();
            userService.save(newUser);
        }
    }
    
    private void handleBuyDeepLink(TelegramContext ctx, User user, String deepLinkParam) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        String locationId = null;
        if (deepLinkParam.length() > 4) {
            locationId = deepLinkParam.substring(4);
        }
        
        if (locationId == null || locationId.isEmpty()) {
            locationId = shopSettingsService.getDefaultLocationId(shopId);
        }
        
        PurchaseCode code = purchaseCodeService.generateCode(user);
        code.setFromDeepLink(true);
        code.setLocationId(locationId);
        
        sendMessage(ctx, chatId,
            "🛍 *Покупка через QR*\n\n" +
            "📋 Ваш код: *" + code.getCode() + "*\n\n" +
            "⏰ Код действителен до: " + code.getExpiresAt().format(DATE_FORMATTER) + "\n\n" +
            "Назовите этот код администратору.",
            getUserKeyboard(user, shopId), true);
        
        log.info("Generated purchase code {} from deep-link for user {}, location={}", 
            code.getCode(), user.getChatId(), locationId);
    }
    
    private void handlePhoneNumber(TelegramContext ctx, Message message, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        String phoneNumber = null;
        if (message.hasContact()) {
            phoneNumber = message.getContact().getPhoneNumber();
        } else if (message.hasText()) {
            phoneNumber = message.getText();
        }
        
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            sendMessage(ctx, chatId, "Пожалуйста, отправьте номер телефона.", null);
            return;
        }
        
        phoneNumber = phoneNumber.replaceAll("[^0-9+]", "");
        
        if (!isValidPhoneNumber(phoneNumber)) {
            sendMessage(ctx, chatId, 
                "❌ Некорректный формат номера телефона!", 
                getPhoneRequestKeyboard());
            return;
        }
        
        user.setPhoneNumber(phoneNumber);
        user.setState(User.UserState.REGISTERED);
        userService.save(user);
        
        // Формируем сообщение о регистрации
        StringBuilder registrationMessage = new StringBuilder();
        registrationMessage.append("✅ Регистрация успешна!\n\n");
        registrationMessage.append("📱 Телефон: ").append(phoneNumber).append("\n");
        
        // Добавляем информацию о системе лояльности
        if (shopSettingsService.isDiscountTiersEnabled(shopId)) {
            registrationMessage.append("\n").append(shopSettingsService.getDiscountTiersDescription(shopId));
        }
        
        if (shopSettingsService.isStampsEnabled(shopId)) {
            registrationMessage.append("\n\n☕ *Программа штампов*\n");
            registrationMessage.append("Собирайте штампы за каждую покупку!\n");
            registrationMessage.append(String.format("Соберите %d штампов и получите: %s 🎁", 
                shopSettingsService.getStampsRequiredForReward(shopId),
                shopSettingsService.getRewardTitle(shopId)));
        }
        
        sendMessage(ctx, chatId, registrationMessage.toString(), getUserKeyboard(user, shopId), true);
    }
    
    // ========== Button Handlers ==========
    
    private void handlePurchaseButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        PurchaseCode code = purchaseCodeService.generateCode(user);
        
        // Используем кастомное сообщение с кодом
        String codeMsg = getCustomMessage(shopId, "purchaseCode", user.getFirstName())
            .replace("{code}", code.getCode())
            .replace("{expiresAt}", code.getExpiresAt().format(DATE_FORMATTER));
        
        sendMessage(ctx, chatId, codeMsg, getUserKeyboard(user, shopId), true);
    }
    
    private void handleMyStatusButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        StringBuilder message = new StringBuilder();
        message.append(customerProfileService.getFullProfileInfo(user)).append("\n");
        message.append(customerProfileService.getStatusProgressInfo(user)).append("\n\n");
        
        if (shopSettingsService.isDiscountTiersEnabled(shopId)) {
            int validityDays = shopSettingsService.getDiscountValidityDays(shopId);
            double accumulated = userService.getAccumulatedAmount(user);
            double effectiveDiscount = user.getEffectiveDiscountPercent(validityDays);
            
            if (effectiveDiscount > 0) {
                message.append("🏷 Ваша скидка: *").append(String.format("%.0f", effectiveDiscount * 100)).append("%*\n");
            }
            message.append("💰 Накоплено: *").append(String.format("%.0f", accumulated)).append("* руб.\n");
            
            ShopSettings settings = shopSettingsService.getSettings(shopId);
            message.append(settings.getDiscountProgressInfo(accumulated, user.getDiscountLevel(), user.isDiscountValid(validityDays))).append("\n");
        }
        
        if (bonusService.isEnabled(shopId)) {
            message.append(bonusService.getBalanceInfo(user)).append("\n");
        }
        
        if (shopSettingsService.isStampsEnabled(shopId)) {
            Optional<StampWallet> walletOpt = stampWalletService.getWallet(user);
            int stampsRequired = shopSettingsService.getStampsRequiredForReward(shopId);
            
            if (walletOpt.isPresent()) {
                int stamps = walletOpt.get().getStampsCount() != null ? walletOpt.get().getStampsCount() : 0;
                message.append("☕ Штампы: ").append(stamps).append("/").append(stampsRequired).append("\n");
            }
        }
        
        sendMessage(ctx, chatId, message.toString(), getUserKeyboard(user, shopId), true);
    }
    
    private void handleHistoryButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        List<Transaction> transactions = transactionService.getUserRecentTransactions(user, 10);
        
        if (transactions.isEmpty()) {
            sendMessage(ctx, chatId, "📜 История покупок пуста", getUserKeyboard(user, shopId));
            return;
        }
        
        StringBuilder message = new StringBuilder("📜 *Последние покупки:*\n\n");
        for (Transaction tx : transactions) {
            message.append(tx.getCreatedAt().format(DATE_FORMATTER)).append("\n");
            if (tx.getDescription() != null) {
                message.append("_").append(tx.getDescription()).append("_\n");
            }
            message.append("\n");
        }
        
        sendMessage(ctx, chatId, message.toString(), getUserKeyboard(user, shopId), true);
    }
    
    private void handleDiscountsButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        List<DiscountCode> codes = discountCodeService.getActiveCodesForUser(user);
        
        if (codes.isEmpty()) {
            sendMessage(ctx, chatId, "🎁 У вас пока нет активных скидок", getUserKeyboard(user, shopId));
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
            message.append("\n");
        }
        
        sendMessage(ctx, chatId, message.toString(), getUserKeyboard(user, shopId), true);
    }
    
    private void handleStampsButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        if (!shopSettingsService.isStampsEnabled(shopId)) {
            sendMessage(ctx, chatId, "☕ Штампы пока недоступны", getUserKeyboard(user, shopId));
            return;
        }
        
        String progressInfo = stampWalletService.getStampProgressInfo(user);
        sendMessage(ctx, chatId, progressInfo, getUserKeyboard(user, shopId), true);
    }
    
    private void handleAchievementsButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        String achievementsInfo = achievementService.getAchievementsDisplay(user);
        sendMessage(ctx, chatId, achievementsInfo, getUserKeyboard(user, shopId), true);
    }
    
    // ========== Admin Handlers ==========
    
    private void handleEnterCodeButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(ctx, chatId, "❌ Эта функция доступна только администраторам", null);
            return;
        }
        
        userService.updateUserState(user, User.UserState.AWAITING_ADMIN_CODE);
        sendMessage(ctx, chatId, "🔑 Введите код покупки от клиента:", null);
    }
    
    private void handleAdminCodeInput(TelegramContext ctx, String code, User admin) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        Optional<PurchaseCode> purchaseCodeOpt = purchaseCodeService.findByCode(code.toUpperCase().trim(), shopId);
        
        if (purchaseCodeOpt.isEmpty()) {
            sendMessage(ctx, chatId, "❌ Код не найден.", getUserKeyboard(admin, shopId));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        PurchaseCode purchaseCode = purchaseCodeOpt.get();
        
        if (purchaseCode.getStatus() != PurchaseCode.CodeStatus.ACTIVE) {
            sendMessage(ctx, chatId, "❌ Код уже использован или истек.", getUserKeyboard(admin, shopId));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        User customer = purchaseCode.getUser();
        
        // Сохраняем код для дальнейшей обработки
        String key = shopId + ":" + chatId;
        pendingPurchaseCodes.put(key, purchaseCode);
        
        // Показываем информацию о клиенте
        StringBuilder customerInfo = new StringBuilder();
        customerInfo.append("✅ *Код найден!*\n\n");
        customerInfo.append("👤 *").append(customer.getFirstName()).append("*\n");
        customerInfo.append("📱 ").append(customer.getPhoneNumber()).append("\n");
        customerInfo.append("📊 ").append(customer.getStatusInfo()).append("\n\n");
        
        if (shopSettingsService.isFastCheckoutEnabled(shopId)) {
            customerInfo.append("*Выберите способ подтверждения:*");
            sendMessageWithInlineKeyboard(ctx, chatId, customerInfo.toString(), 
                createFastOrAmountKeyboard(purchaseCode.getCode()));
            userService.updateUserState(admin, User.UserState.AWAITING_FAST_OR_AMOUNT_CHOICE);
        } else {
            customerInfo.append("💵 *Введите сумму покупки (в рублях):*");
            sendMessage(ctx, chatId, customerInfo.toString(), null, true);
            userService.updateUserState(admin, User.UserState.AWAITING_PURCHASE_AMOUNT);
        }
    }
    
    private void handlePurchaseAmountInput(TelegramContext ctx, String messageText, User admin) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        if (admin.getRole() != User.UserRole.ADMIN) {
            return;
        }
        
        String key = shopId + ":" + chatId;
        PurchaseCode purchaseCode = pendingPurchaseCodes.get(key);
        
        if (purchaseCode == null) {
            sendMessage(ctx, chatId, "❌ Код не найден. Попробуйте еще раз.", getUserKeyboard(admin, shopId));
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        try {
            double purchaseAmount = Double.parseDouble(messageText.trim());
            
            if (purchaseAmount <= 0) {
                sendMessage(ctx, chatId, "❌ Сумма должна быть больше 0.", null);
                return;
            }
            
            User customer = purchaseCode.getUser();
            
            // Обрабатываем покупку
            purchaseCodeService.useCode(purchaseCode, admin);
            
            transactionService.createTransaction(
                customer, 0, Transaction.TransactionType.EARN,
                String.format("Покупка на %.2f руб.", purchaseAmount),
                purchaseAmount, purchaseCode, admin
            );
            
            customer.recordPurchase(purchaseAmount, false);
            userService.processPurchase(customer);
            
            // Начисляем бонусы
            double bonusAccrued = bonusService.accrueBonus(customer, purchaseAmount);
            
            pendingPurchaseCodes.remove(key);
            userService.updateUserState(admin, User.UserState.REGISTERED);
            
            int validityDays = shopSettingsService.getDiscountValidityDays(shopId);
            double effectiveDiscount = customer.getEffectiveDiscountPercent(validityDays);
            String discountInfo = effectiveDiscount > 0 
                ? String.format("\n🏷 Скидка клиента: %.0f%%", effectiveDiscount * 100) 
                : "";
            
            sendMessage(ctx, chatId, 
                "✅ *Покупка обработана!*\n\n" +
                "👤 Клиент: " + customer.getFirstName() + "\n" +
                "💵 Сумма: " + String.format("%.2f", purchaseAmount) + " руб." + discountInfo,
                getUserKeyboard(admin, shopId), true);
            
            StringBuilder customerMsg = new StringBuilder();
            customerMsg.append("✅ *Покупка зарегистрирована!*\n\n");
            customerMsg.append("🛍 Сумма: ").append(String.format("%.2f", purchaseAmount)).append(" руб.\n");
            if (effectiveDiscount > 0) {
                customerMsg.append("🏷 Ваша скидка: *").append(String.format("%.0f", effectiveDiscount * 100)).append("%*\n");
            }
            if (bonusAccrued > 0) {
                customerMsg.append(String.format("💳 +%.0f баллов! Баланс: %.0f\n", bonusAccrued, bonusService.getBalance(customer)));
            }
            if (shopSettingsService.isDiscountTiersEnabled(shopId)) {
                ShopSettings settings = shopSettingsService.getSettings(shopId);
                double accumulated = userService.getAccumulatedAmount(customer);
                String progress = settings.getDiscountProgressInfo(accumulated, customer.getDiscountLevel(), true);
                if (!progress.isEmpty()) {
                    customerMsg.append(progress).append("\n");
                }
            }
            
            sendMessage(ctx, customer.getChatId(), customerMsg.toString(), null, true);
            
        } catch (NumberFormatException e) {
            sendMessage(ctx, chatId, "❌ Введите корректное число.", null);
        }
    }
    
    private void handleEnterRedeemCodeButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(ctx, chatId, "❌ Эта функция доступна только администраторам", null);
            return;
        }
        
        userService.updateUserState(user, User.UserState.AWAITING_REDEEM_CODE);
        sendMessage(ctx, chatId, "🎁 Введите код награды от клиента:", null);
    }
    
    private void handleSendDiscountButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(ctx, chatId, "❌ Эта функция доступна только администраторам", null);
            return;
        }
        
        sendMessage(ctx, chatId, 
            "📢 *Создание акции*\n\n" +
            "Эта функция доступна через Web-админку.\n" +
            "Обратитесь к владельцу бизнеса.",
            getUserKeyboard(user, shopId), true);
    }
    
    private void handlePromotionsButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(ctx, chatId, "❌ Эта функция доступна только администраторам", null);
            return;
        }
        
        List<Promotion> activePromotions = promotionService.getActivePromotions();
        
        if (activePromotions.isEmpty()) {
            sendMessage(ctx, chatId, "🎁 Нет активных акций", getUserKeyboard(user, shopId));
            return;
        }
        
        StringBuilder message = new StringBuilder("🎁 *Активные акции:*\n\n");
        for (Promotion promo : activePromotions) {
            message.append("📌 *").append(promo.getDescription()).append("*\n");
            message.append("💰 Скидка: ").append(promo.getDiscountPercent()).append("%\n\n");
        }
        
        sendMessage(ctx, chatId, message.toString(), getUserKeyboard(user, shopId), true);
    }
    
    private void handleStatsButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        if (user.getRole() != User.UserRole.ADMIN) {
            sendMessage(ctx, chatId, "❌ Эта функция доступна только администраторам", null);
            return;
        }
        
        UserService.UserStats stats = userService.getUserStats();
        
        StringBuilder message = new StringBuilder();
        message.append("📊 *Статистика*\n\n");
        message.append("👥 Пользователей: *").append(stats.totalUsers).append("*\n");
        message.append("📝 Зарегистрированных: *").append(stats.registeredUsers).append("*\n");
        message.append("👑 Администраторов: *").append(stats.admins).append("*\n");
        
        sendMessage(ctx, chatId, message.toString(), getUserKeyboard(user, shopId), true);
    }
    
    // ========== Callback Handlers ==========
    
    private void handleFastCheckoutCallback(TelegramContext ctx, String data, User admin, Integer messageId) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        String purchaseCodeStr = data.substring(CB_FAST_CHECKOUT.length());
        String key = shopId + ":" + chatId;
        PurchaseCode purchaseCode = pendingPurchaseCodes.get(key);
        
        if (purchaseCode == null || !purchaseCode.getCode().equals(purchaseCodeStr)) {
            editMessage(ctx, chatId, messageId, "❌ Код не найден.");
            userService.updateUserState(admin, User.UserState.REGISTERED);
            return;
        }
        
        User customer = purchaseCode.getUser();
        
        // Обрабатываем fast checkout
        purchaseCode.setFastCheckout(true);
        purchaseCodeService.useCode(purchaseCode, admin);
        customer.incrementFastCheckoutCount();
        customerProfileService.recordPurchase(customer, null, true);
        
        // Начисляем штамп если включены
        if (shopSettingsService.isStampsEnabled(shopId)) {
            int stampsToAdd = shopSettingsService.getStampsPerFastPurchase(shopId);
            stampWalletService.addStamps(customer, stampsToAdd);
        }
        
        pendingPurchaseCodes.remove(key);
        userService.updateUserState(admin, User.UserState.REGISTERED);
        
        editMessage(ctx, chatId, messageId, 
            "✅ *Быстрая покупка подтверждена!*\n\n" +
            "👤 Клиент: " + customer.getFirstName());
        
        sendMessage(ctx, customer.getChatId(), "✅ *Покупка подтверждена!*", null, true);
    }
    
    private void handleAmountCheckoutCallback(TelegramContext ctx, String data, User admin, Integer messageId) {
        Long chatId = ctx.getChatId();
        
        userService.updateUserState(admin, User.UserState.AWAITING_PURCHASE_AMOUNT);
        editMessage(ctx, chatId, messageId, "💵 *Введите сумму покупки (в рублях):*");
    }
    
    private void handleCancelCheckoutCallback(TelegramContext ctx, String data, User admin, Integer messageId) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        String key = shopId + ":" + chatId;
        
        pendingPurchaseCodes.remove(key);
        userService.updateUserState(admin, User.UserState.REGISTERED);
        editMessage(ctx, chatId, messageId, "❌ Покупка отменена.");
        sendMessage(ctx, chatId, "📋 Главное меню:", getUserKeyboard(admin, shopId));
    }
    
    private void handleRedeemRewardCallback(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();
        
        try {
            RedeemCode redeemCode = stampWalletService.generateRedeemCode(user);
            
            sendMessage(ctx, chatId,
                "🎁 *Код для получения награды:*\n\n" +
                "📋 Код: *" + redeemCode.getCode() + "*\n\n" +
                "🎁 Награда: *" + redeemCode.getRewardTitle() + "*\n\n" +
                "Назовите этот код администратору!",
                getUserKeyboard(user, shopId), true);
            
        } catch (IllegalStateException e) {
            sendMessage(ctx, chatId, "❌ " + e.getMessage(), getUserKeyboard(user, shopId));
        }
    }
    
    private void handleOpenShopButton(TelegramContext ctx, User user) {
        Long chatId = ctx.getChatId();
        String shopId = ctx.getShopId();

        if (!commerceProperties.getMiniApp().isEnabled()) {
            sendMessage(ctx, chatId, "Магазин временно недоступен.", getUserKeyboard(user, shopId));
            return;
        }

        String baseUrl = commerceProperties.getMiniApp().getPublicUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String webAppUrl = baseUrl + "/store/" + shopId;

        Map<String, Object> keyboard = Map.of(
                "inline_keyboard", List.of(
                        List.of(Map.of(
                                "text", "🛍 Открыть магазин",
                                "web_app", Map.of("url", webAppUrl)
                        ))
                )
        );

        sendMessage(ctx, chatId, "Нажмите кнопку ниже, чтобы открыть магазин:", keyboard);
    }

    // ========== Keyboards ==========
    
    private Map<String, Object> getUserKeyboard(User user, String shopId) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        
        if (user.getRole() == User.UserRole.ADMIN) {
            // Админская клавиатура
            rows.add(List.of(
                Map.of("text", BTN_ENTER_CODE),
                Map.of("text", BTN_ENTER_REDEEM)
            ));
            rows.add(List.of(
                Map.of("text", BTN_SEND_DISCOUNT),
                Map.of("text", BTN_PROMOTIONS)
            ));
            rows.add(List.of(
                Map.of("text", BTN_STATS)
            ));
        } else {
            // Клавиатура пользователя
            rows.add(List.of(
                Map.of("text", BTN_OPEN_SHOP),
                Map.of("text", BTN_CATALOG)
            ));
            rows.add(List.of(
                Map.of("text", BTN_PURCHASE),
                Map.of("text", BTN_MY_STATUS)
            ));

            List<Map<String, Object>> row2 = new ArrayList<>();
            if (shopSettingsService.isStampsEnabled(shopId)) {
                row2.add(Map.of("text", BTN_STAMPS));
            }
            if (shopSettingsService.isDiscountTiersEnabled(shopId)) {
                row2.add(Map.of("text", BTN_DISCOUNTS));
            }
            if (!row2.isEmpty()) {
                rows.add(row2);
            }

            rows.add(List.of(
                Map.of("text", BTN_HISTORY)
            ));

            rows.add(List.of(
                Map.of("text", BTN_ACHIEVEMENTS)
            ));
        }
        
        return Map.of(
            "keyboard", rows,
            "resize_keyboard", true
        );
    }
    
    private Map<String, Object> getPhoneRequestKeyboard() {
        return Map.of(
            "keyboard", List.of(
                List.of(Map.of(
                    "text", "📱 Отправить номер телефона",
                    "request_contact", true
                ))
            ),
            "resize_keyboard", true,
            "one_time_keyboard", true
        );
    }
    
    private Map<String, Object> createFastOrAmountKeyboard(String purchaseCode) {
        return Map.of(
            "inline_keyboard", List.of(
                List.of(
                    Map.of("text", "💨 Быстро", "callback_data", CB_FAST_CHECKOUT + purchaseCode),
                    Map.of("text", "💵 С суммой", "callback_data", CB_AMOUNT_CHECKOUT + purchaseCode)
                ),
                List.of(
                    Map.of("text", "❌ Отмена", "callback_data", CB_CANCEL_CHECKOUT + purchaseCode)
                )
            )
        );
    }
    
    // ========== Message Sending ==========
    
    public void sendMessage(TelegramContext ctx, Long chatId, String text, Object keyboard) {
        sendMessage(ctx, chatId, text, keyboard, false);
    }
    
    public void sendMessage(TelegramContext ctx, Long chatId, String text, Object keyboard, boolean markdown) {
        telegramApiClient.sendMessage(
            ctx.getBotToken(),
            chatId,
            text,
            keyboard,
            markdown ? "Markdown" : null
        );
    }
    
    public void sendMessageWithInlineKeyboard(TelegramContext ctx, Long chatId, String text, Map<String, Object> keyboard) {
        telegramApiClient.sendMessage(
            ctx.getBotToken(),
            chatId,
            text,
            keyboard,
            "Markdown"
        );
    }
    
    public void editMessage(TelegramContext ctx, Long chatId, Integer messageId, String text) {
        telegramApiClient.editMessageText(
            ctx.getBotToken(),
            chatId,
            messageId,
            text,
            null,
            "Markdown"
        );
    }
    
    public void sendErrorMessage(TelegramContext ctx, String text) {
        if (ctx.getChatId() != null) {
            telegramApiClient.sendMessage(ctx.getBotToken(), ctx.getChatId(), "❌ " + text, null, null);
        }
    }
    
    // ========== Helpers ==========
    
    /**
     * Получает кастомное сообщение из настроек магазина с подстановкой переменных.
     * @param shopId ID магазина
     * @param messageType тип сообщения: "welcome", "purchaseCode", "stampEarned", "rewardEarned"
     * @param userName имя пользователя для подстановки
     */
    private String getCustomMessage(String shopId, String messageType, String userName) {
        ShopSettings settings = shopSettingsService.getSettings(shopId);
        String shopName = settings != null ? settings.getShopName() : "Магазин";
        
        String template = switch (messageType) {
            case "welcome" -> settings != null ? settings.getWelcomeMessageOrDefault() 
                : "👋 Добро пожаловать в программу лояльности!\n\nДля регистрации отправьте свой номер телефона.";
            case "purchaseCode" -> settings != null ? settings.getPurchaseCodeMessageOrDefault()
                : "🛍 Код для покупки создан!\n\n📋 Ваш код: *{code}*\n\n⏱ Покажите этот код кассиру.";
            case "stampEarned" -> settings != null ? settings.getStampEarnedMessageOrDefault()
                : "☕ +1 штамп!\n\n📊 Всего: {stamps}\nДо награды: {stampsLeft}";
            case "rewardEarned" -> settings != null ? settings.getRewardEarnedMessageOrDefault()
                : "🎉 Поздравляем! Вы получили награду!\n\n📋 Код: *{code}*";
            default -> "";
        };
        
        // Подстановка общих переменных
        return template
            .replace("{shopName}", shopName)
            .replace("{userName}", userName != null ? userName : "");
    }
    
    private boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return false;
        }
        String digitsOnly = phoneNumber.replaceAll("\\+", "");
        int length = digitsOnly.length();
        return length >= 10 && length <= 15 && digitsOnly.matches("^\\d+$");
    }
}

