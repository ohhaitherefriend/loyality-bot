package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.*;
import com.plstk.loyaltybot.repository.MessageLogRepository;
import com.plstk.loyaltybot.repository.MessageTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Сервис для составления и отправки персональных сообщений клиентам.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageComposerService {
    
    private final MessageTemplateRepository messageTemplateRepository;
    private final MessageLogRepository messageLogRepository;
    private final ShopSettingsService shopSettingsService;
    private final StampWalletService stampWalletService;
    
    // Callback для отправки сообщений (инжектится из бота)
    private BiConsumer<Long, String> messageSender;
    
    /**
     * Устанавливает callback для отправки сообщений
     */
    public void setMessageSender(BiConsumer<Long, String> sender) {
        this.messageSender = sender;
    }
    
    // ========== Отправка сообщений ==========
    
    /**
     * Результат отправки
     */
    public record SendResult(
        boolean success,
        String error,
        MessageLog log
    ) {}
    
    /**
     * Отправляет персональное сообщение клиенту
     */
    @Transactional
    public SendResult sendMessage(User customer, String content, MessageLog.MessageType type, User sentBy) {
        // Проверяем rate-limit
        if (!canSendMessage(customer, type)) {
            return new SendResult(false, "Превышен лимит сообщений для этого клиента", null);
        }
        
        // Подставляем переменные
        String processedContent = processVariables(content, customer);
        
        // Пробуем отправить
        MessageLog.DeliveryStatus status = MessageLog.DeliveryStatus.SENT;
        String errorMessage = null;
        
        try {
            if (messageSender != null) {
                messageSender.accept(customer.getChatId(), processedContent);
            } else {
                log.warn("Message sender not configured");
            }
        } catch (Exception e) {
            status = MessageLog.DeliveryStatus.FAILED;
            errorMessage = e.getMessage();
            log.error("Failed to send message to user {}", customer.getChatId(), e);
        }
        
        // Логируем
        MessageLog messageLog = MessageLog.builder()
            .user(customer)
            .messageType(type)
            .content(processedContent)
            .sentBy(sentBy)
            .sentAt(LocalDateTime.now())
            .status(status)
            .errorMessage(errorMessage)
            .build();
        
        MessageLog saved = messageLogRepository.save(messageLog);
        
        boolean success = status == MessageLog.DeliveryStatus.SENT;
        log.info("Message {} to user {}: {}", success ? "sent" : "failed", customer.getChatId(), type);
        
        return new SendResult(success, errorMessage, saved);
    }
    
    /**
     * Отправляет сообщение по шаблону
     */
    @Transactional
    public SendResult sendFromTemplate(User customer, MessageTemplate template, User sentBy) {
        String content = template.getContent();
        
        MessageLog.MessageType type = switch (template.getCategory()) {
            case PROMOTION -> MessageLog.MessageType.PROMOTION;
            case REMINDER -> MessageLog.MessageType.REMINDER;
            case ACHIEVEMENT -> MessageLog.MessageType.ACHIEVEMENT;
            case STATUS_CHANGE -> MessageLog.MessageType.STATUS_CHANGE;
            default -> MessageLog.MessageType.MANUAL;
        };
        
        SendResult result = sendMessage(customer, content, type, sentBy);
        
        // Связываем с шаблоном
        if (result.log() != null) {
            result.log().setTemplate(template);
            messageLogRepository.save(result.log());
        }
        
        return result;
    }
    
    /**
     * Отправляет авто-триггер сообщение
     */
    @Transactional
    public SendResult sendAutoTrigger(User customer, MessageTemplate.AutoTriggerType triggerType) {
        if (!shopSettingsService.isAutoMessagesEnabled()) {
            return new SendResult(false, "Авто-сообщения отключены", null);
        }
        
        Optional<MessageTemplate> templateOpt = messageTemplateRepository
            .findByAutoTriggerAndIsActiveTrue(triggerType);
        
        if (templateOpt.isEmpty()) {
            // Используем дефолтный текст
            String defaultContent = getDefaultTriggerContent(triggerType, customer);
            return sendMessage(customer, defaultContent, MessageLog.MessageType.AUTO_TRIGGER, null);
        }
        
        MessageTemplate template = templateOpt.get();
        SendResult result = sendMessage(customer, template.getContent(), MessageLog.MessageType.AUTO_TRIGGER, null);
        
        if (result.log() != null) {
            result.log().setTemplate(template);
            messageLogRepository.save(result.log());
        }
        
        return result;
    }
    
    // ========== Проверки ==========
    
    /**
     * Проверяет, можно ли отправить сообщение (rate-limit)
     */
    public boolean canSendMessage(User customer, MessageLog.MessageType type) {
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        
        if (type == MessageLog.MessageType.AUTO_TRIGGER) {
            int limit = shopSettingsService.getAutoMessagesDailyLimit();
            long count = messageLogRepository.countAutoMessagesSince(customer, startOfDay);
            return count < limit;
        }
        
        // Общий лимит на все сообщения в день
        long totalCount = messageLogRepository.countMessagesSince(customer, startOfDay);
        return totalCount < 10; // Максимум 10 сообщений в день
    }
    
    // ========== Обработка переменных ==========
    
    /**
     * Подставляет переменные в текст
     */
    public String processVariables(String content, User customer) {
        if (content == null) return "";
        
        String result = content;
        
        // {name}
        result = result.replace(MessageTemplate.VAR_NAME, 
            customer.getFirstName() != null ? customer.getFirstName() : "");
        
        // {status}
        CustomerStatus status = customer.getCustomerStatus();
        result = result.replace(MessageTemplate.VAR_STATUS, 
            status != null ? status.getDisplayWithEmoji() : CustomerStatus.NEW.getDisplayWithEmoji());
        
        // {purchasesCount}
        result = result.replace(MessageTemplate.VAR_PURCHASES_COUNT, 
            String.valueOf(customer.getPurchasesCount() != null ? customer.getPurchasesCount() : 0));
        
        // {totalSpend}
        result = result.replace(MessageTemplate.VAR_TOTAL_SPEND, 
            String.format("%.2f", customer.getTotalSpend() != null ? customer.getTotalSpend() : 0.0));
        
        // {daysSinceLastVisit}
        Long daysSince = customer.getDaysSinceLastPurchase();
        result = result.replace(MessageTemplate.VAR_DAYS_SINCE_VISIT, 
            daysSince != null ? String.valueOf(daysSince) : "много");
        
        // {discount}
        double discount = customer.getEffectiveDiscountPercent();
        result = result.replace(MessageTemplate.VAR_DISCOUNT, 
            discount > 0 ? String.format("%.0f%%", discount * 100) : "нет");
        
        // Штампы (если включены)
        if (shopSettingsService.isStampsEnabled()) {
            Optional<StampWallet> walletOpt = stampWalletService.getWallet(customer);
            int stampsRequired = shopSettingsService.getStampsRequiredForReward();
            
            if (walletOpt.isPresent()) {
                StampWallet wallet = walletOpt.get();
                int stamps = wallet.getStampsCount() != null ? wallet.getStampsCount() : 0;
                int rewards = wallet.getRewardsAvailable() != null ? wallet.getRewardsAvailable() : 0;
                
                result = result.replace(MessageTemplate.VAR_STAMPS, String.valueOf(stamps));
                result = result.replace(MessageTemplate.VAR_STAMPS_LEFT, String.valueOf(stampsRequired - stamps));
                result = result.replace(MessageTemplate.VAR_REWARDS, String.valueOf(rewards));
            } else {
                result = result.replace(MessageTemplate.VAR_STAMPS, "0");
                result = result.replace(MessageTemplate.VAR_STAMPS_LEFT, String.valueOf(stampsRequired));
                result = result.replace(MessageTemplate.VAR_REWARDS, "0");
            }
        }
        
        return result;
    }
    
    /**
     * Возвращает дефолтный текст для триггера
     */
    private String getDefaultTriggerContent(MessageTemplate.AutoTriggerType triggerType, User customer) {
        return switch (triggerType) {
            case FIRST_PURCHASE -> """
                🎉 Поздравляем с первой покупкой, {name}!
                
                Добро пожаловать в нашу программу лояльности!
                """;
            case ONE_STAMP_LEFT -> """
                ☕ {name}, до награды остался всего 1 штамп!
                
                Приходите скорее! 🎁
                """;
            case BECAME_REGULAR -> """
                ⭐ Поздравляем, {name}!
                
                Вы стали нашим постоянным клиентом!
                """;
            case BECAME_VIP -> """
                👑 {name}, добро пожаловать в VIP клуб!
                
                Благодарим за вашу лояльность!
                """;
            case VIP_BECAME_LOST -> """
                👑 {name}, мы скучаем по вам!
                
                Вы не были у нас уже {daysSinceLastVisit} дней.
                Возвращайтесь!
                """;
            case REWARD_AVAILABLE -> """
                🎁 {name}, у вас есть награда!
                
                Нажмите "Мои штампы" для погашения.
                """;
            case INACTIVE_REMINDER -> """
                Привет, {name}! 👋
                
                Давно вас не видели ({daysSinceLastVisit} дней).
                Ждём вас снова!
                """;
            default -> "Привет, {name}!";
        };
    }
    
    // ========== Шаблоны ==========
    
    /**
     * Получает все активные шаблоны
     */
    public List<MessageTemplate> getActiveTemplates() {
        return messageTemplateRepository.findByIsActiveTrueOrderByNameAsc();
    }
    
    /**
     * Получает шаблоны по категории
     */
    public List<MessageTemplate> getTemplatesByCategory(MessageTemplate.TemplateCategory category) {
        return messageTemplateRepository.findByCategoryAndIsActiveTrueOrderByNameAsc(category);
    }
    
    /**
     * Создаёт новый шаблон
     */
    @Transactional
    public MessageTemplate createTemplate(String name, String content, MessageTemplate.TemplateCategory category) {
        MessageTemplate template = MessageTemplate.builder()
            .name(name)
            .content(content)
            .category(category)
            .isActive(true)
            .isSystem(false)
            .build();
        
        return messageTemplateRepository.save(template);
    }
    
    /**
     * Создаёт системный шаблон с авто-триггером
     */
    @Transactional
    public MessageTemplate createAutoTriggerTemplate(
            String name, String content, 
            MessageTemplate.AutoTriggerType autoTrigger) {
        
        MessageTemplate template = MessageTemplate.builder()
            .name(name)
            .content(content)
            .category(MessageTemplate.TemplateCategory.GENERAL)
            .autoTrigger(autoTrigger)
            .isActive(true)
            .isSystem(true)
            .build();
        
        return messageTemplateRepository.save(template);
    }
    
    // ========== Статистика ==========
    
    /**
     * Получает статистику отправок за период
     */
    public List<Object[]> getMessageStatsSince(LocalDateTime since) {
        return messageLogRepository.getMessageStatsSince(since);
    }
    
    /**
     * Получает последние сообщения клиенту
     */
    public List<MessageLog> getRecentMessages(User customer) {
        return messageLogRepository.findTop10ByUserOrderBySentAtDesc(customer);
    }
}



