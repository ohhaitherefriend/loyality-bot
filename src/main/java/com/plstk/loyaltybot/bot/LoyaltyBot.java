package com.plstk.loyaltybot.bot;

import com.plstk.loyaltybot.entity.DiscountCode;
import com.plstk.loyaltybot.entity.Promotion;
import com.plstk.loyaltybot.entity.PurchaseCode;
import com.plstk.loyaltybot.entity.Transaction;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.service.DiscountCodeService;
import com.plstk.loyaltybot.service.PromotionService;
import com.plstk.loyaltybot.service.PurchaseCodeService;
import com.plstk.loyaltybot.service.TransactionService;
import com.plstk.loyaltybot.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Slf4j
public class LoyaltyBot extends TelegramLongPollingBot {
    
    private final UserService userService;
    private final PurchaseCodeService purchaseCodeService;
    private final DiscountCodeService discountCodeService;
    private final TransactionService transactionService;
    private final PromotionService promotionService;
    
    // Временное хранилище для кодов покупок, ожидающих ввода суммы
    // Ключ: chatId админа, Значение: PurchaseCode
    private final Map<Long, PurchaseCode> pendingPurchaseCodes = new HashMap<>();
    
    // Временное хранилище для создаваемых промо-акций
    // Ключ: chatId админа, Значение: PromotionBuilder
    private final Map<Long, PromotionBuilder> pendingPromotions = new HashMap<>();
    
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
    
    // Кнопки для админа
    private static final String BTN_ENTER_CODE = "🔑 Ввести код покупки";
    private static final String BTN_SEND_DISCOUNT = "📢 Отправить скидку";
    private static final String BTN_PROMOTIONS = "🎁 Активные акции";
    private static final String BTN_STATS = "📊 Статистика";
    
    public LoyaltyBot(@Value("${telegram.bot.token}") String botToken,
                     UserService userService,
                     PurchaseCodeService purchaseCodeService,
                     DiscountCodeService discountCodeService,
                     TransactionService transactionService,
                     PromotionService promotionService) {
        super(botToken);
        this.userService = userService;
        this.purchaseCodeService = purchaseCodeService;
        this.discountCodeService = discountCodeService;
        this.transactionService = transactionService;
        this.promotionService = promotionService;
    }
    
    @Override
    public String getBotUsername() {
        return botUsername;
    }
    
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                handleMessage(update);
            }
        } catch (Exception e) {
            log.error("Error processing update", e);
        }
    }
    
    private void handleMessage(Update update) {
        Long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().getText();
        
        Optional<User> userOpt = userService.findByChatId(chatId);
        
        // Команда /start
        if (messageText != null && messageText.equals("/start")) {
            handleStartCommand(chatId, update, userOpt);
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
        
        // Обработка кнопок меню
        if (messageText != null) {
            switch (messageText) {
                case BTN_PURCHASE -> handlePurchaseButton(chatId, user);
                case BTN_MY_STATUS -> handleMyStatusButton(chatId, user);
                case BTN_HISTORY -> handleHistoryButton(chatId, user);
                case BTN_DISCOUNTS -> handleDiscountsButton(chatId, user);
                case BTN_ENTER_CODE -> handleEnterCodeButton(chatId, user);
                case BTN_SEND_DISCOUNT -> handleSendDiscountButton(chatId, user);
                case BTN_PROMOTIONS -> handlePromotionsButton(chatId, user);
                case BTN_STATS -> handleStatsButton(chatId, user);
                default -> sendMessage(chatId, "Используйте кнопки меню для навигации", getUserKeyboard(user));
            }
        }
    }
    
    private void handleStartCommand(Long chatId, Update update, Optional<User> userOpt) {
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            sendMessage(chatId, "С возвращением, " + user.getFirstName() + "!", getUserKeyboard(user));
        } else {
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
        
        String registrationMessage = "✅ Регистрация успешна!\n\n" +
            "📱 Телефон: " + phoneNumber + "\n\n" +
            "💡 Накопительная система скидок:\n" +
            "Накапливайте покупки и получайте скидку на 30 дней!\n\n" +
            "• 5% скидка - от 20,000 руб\n" +
            "• 7% скидка - от 25,000 руб\n" +
            "• 10% скидка - от 30,000 руб\n\n" +
            "🔄 Продлевайте скидку, накопив сумму снова в течение 30 дней!";
        
        // Если есть активные акции, добавляем информацию о них
        if (!promoCodes.isEmpty()) {
            registrationMessage += "\n\n🎉 *У нас есть активные акции!*\n" +
                "Проверьте раздел \"🎁 Мои скидки\" для получения скидок.";
        }
        
        sendMessage(chatId, registrationMessage, getUserKeyboard(user), !promoCodes.isEmpty());
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
        double accumulated = userService.getAccumulatedAmount(user);
        
        StringBuilder message = new StringBuilder();
        message.append("📊 *Ваша накопительная скидка*\n\n");
        
        // Если есть действующая скидка, показываем её
        if (user.isDiscountValid()) {
            message.append("🎯 Ваша текущая скидка: *").append(user.getDiscountDescription()).append("*\n");
            
            if (user.getDiscountExpiresAt() != null) {
                message.append("⏰ Действует до: ").append(user.getDiscountExpiresAt().format(DATE_FORMATTER)).append("\n");
                long daysLeft = java.time.Duration.between(java.time.LocalDateTime.now(), user.getDiscountExpiresAt()).toDays();
                message.append("⌛ Осталось дней: *").append(daysLeft).append("*\n\n");
            }
            
            message.append("💰 Накоплено для продления: *").append(String.format("%.2f", accumulated)).append("* руб.\n");
            message.append("🚀 ").append(user.getNextDiscountLevelInfo(accumulated)).append("\n\n");
            message.append("💡 Совершите покупки на ").append(String.format("%.0f", user.getRequiredAmountForCurrentDiscount())).append(" руб. для продления скидки на 30 дней!\n");
        } else {
            message.append("🎯 Текущая скидка: *Нет скидки*\n\n");
            message.append("💰 Накоплено: *").append(String.format("%.2f", accumulated)).append("* руб.\n");
            message.append("🚀 ").append(user.getNextDiscountLevelInfo(accumulated)).append("\n\n");
        }
        
        message.append("📋 *Условия накопительной скидки:*\n");
        message.append("• 5% скидка - от 20,000 руб\n");
        message.append("• 7% скидка - от 25,000 руб\n");
        message.append("• 10% скидка - от 30,000 руб\n\n");
        message.append("⏰ Скидка действует 30 дней с момента активации\n");
        message.append("🔄 Накопите снова для продления!");
        
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
        
        // Получаем накопительную скидку клиента
        double loyaltyDiscount = customer.getDiscountPercent();
        
        // Получаем активные промо-коды клиента
        List<DiscountCode> activePromoCodes = discountCodeService.getActiveCodesForUser(customer);
        
        // Формируем информацию о скидках
        StringBuilder discountInfo = new StringBuilder();
        
        // Показываем накопительную скидку
        if (loyaltyDiscount > 0) {
            discountInfo.append("🎯 *Накопительная скидка: ")
                       .append(String.format("%.0f%%", loyaltyDiscount * 100))
                       .append("*\n");
            if (customer.getDiscountExpiresAt() != null) {
                long daysLeft = java.time.Duration.between(java.time.LocalDateTime.now(), customer.getDiscountExpiresAt()).toDays();
                discountInfo.append("⏰ Действует еще ").append(daysLeft).append(" дней\n");
            }
                double accumulated = userService.getAccumulatedAmount(customer);
                discountInfo.append("💰 Накоплено: ")
                       .append(String.format("%.2f", accumulated))
                       .append(" руб.\n");
        } else {
            double accumulated = userService.getAccumulatedAmount(customer);
            discountInfo.append("⚪ Накопительная скидка: нет\n");
            discountInfo.append("💰 Накоплено: ")
                       .append(String.format("%.2f", accumulated))
                       .append(" руб.\n");
        }
        
        // Показываем промо-акции
        if (!activePromoCodes.isEmpty()) {
            discountInfo.append("\n🎁 *Доступные акционные скидки:*\n");
            for (DiscountCode promoCode : activePromoCodes) {
                discountInfo.append("  • ")
                           .append(promoCode.getDescription())
                           .append(" - *")
                           .append(promoCode.getDiscountPercent())
                           .append("%*\n");
            }
            discountInfo.append("\n💡 Будет применена максимальная скидка\n");
        }
        
        discountInfo.append("\n");
        
        // Сохраняем код временно и переводим админа в состояние ожидания суммы
        pendingPurchaseCodes.put(chatId, purchaseCode);
        userService.updateUserState(admin, User.UserState.AWAITING_PURCHASE_AMOUNT);
        
        sendMessage(chatId, 
            "✅ *Код найден! Клиент:*\n" +
            "👤 " + customer.getFirstName() + " " + 
                (customer.getLastName() != null ? customer.getLastName() : "") + "\n" +
            "📱 Телефон: " + customer.getPhoneNumber() + "\n\n" +
            discountInfo.toString() +
            "💵 *Введите сумму покупки (в рублях):*", null, true);
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
            double loyaltyDiscount = customer.getDiscountPercent();
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
            
            sendMessage(chatId, adminMessage.toString(), getUserKeyboard(admin), true);
            
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
            row1.add(new KeyboardButton(BTN_MY_STATUS));
            
            KeyboardRow row2 = new KeyboardRow();
            row2.add(new KeyboardButton(BTN_SEND_DISCOUNT));
            row2.add(new KeyboardButton(BTN_PROMOTIONS));
            
            KeyboardRow row3 = new KeyboardRow();
            row3.add(new KeyboardButton(BTN_STATS));
            
            rows.add(row1);
            rows.add(row2);
            rows.add(row3);
        } else {
            // Клавиатура обычного пользователя
            KeyboardRow row1 = new KeyboardRow();
            row1.add(new KeyboardButton(BTN_PURCHASE));
            row1.add(new KeyboardButton(BTN_MY_STATUS));
            
            KeyboardRow row2 = new KeyboardRow();
            row2.add(new KeyboardButton(BTN_HISTORY));
            row2.add(new KeyboardButton(BTN_DISCOUNTS));
            
            rows.add(row1);
            rows.add(row2);
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
}
