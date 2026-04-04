package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.CustomerStatus;
import com.plstk.loyaltybot.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;

/**
 * Сервис автоматических триггеров (уведомлений) для клиентов.
 * Отправляет персональные сообщения при определённых событиях.
 */
@Service
@Slf4j
public class AutoTriggerService {
    
    private final ShopSettingsService shopSettingsService;
    
    // Callback для отправки сообщений (инжектится из бота)
    private BiConsumer<Long, String> messageSender;
    
    public AutoTriggerService(ShopSettingsService shopSettingsService) {
        this.shopSettingsService = shopSettingsService;
    }
    
    /**
     * Устанавливает callback для отправки сообщений
     * Вызывается из LoyaltyBot при инициализации
     */
    public void setMessageSender(BiConsumer<Long, String> sender) {
        this.messageSender = sender;
    }
    
    /**
     * Отправляет сообщение клиенту (если включены авто-сообщения)
     */
    private void sendMessage(User user, String message) {
        if (!shopSettingsService.isAutoMessagesEnabled()) {
            log.debug("Auto messages disabled, skipping message to user {}", user.getChatId());
            return;
        }
        
        if (messageSender == null) {
            log.warn("Message sender not configured, cannot send auto-trigger message");
            return;
        }
        
        try {
            messageSender.accept(user.getChatId(), message);
            log.info("Sent auto-trigger message to user {}", user.getChatId());
        } catch (Exception e) {
            log.error("Failed to send auto-trigger message to user {}", user.getChatId(), e);
        }
    }
    
    // ========== Триггеры покупок ==========
    
    /**
     * Триггер: Первая покупка клиента
     */
    public void onFirstPurchase(User user) {
        String message = """
            🎉 *Поздравляем с первой покупкой!*
            
            Добро пожаловать в нашу программу лояльности!
            
            📊 Ваш статус: %s
            
            Совершайте покупки и получайте награды! 🎁
            """.formatted(user.getStatusInfo());
        
        sendMessage(user, message);
    }
    
    /**
     * Триггер: Клиент стал REGULAR
     */
    public void onBecameRegular(User user) {
        String message = """
            ⭐ *Поздравляем!*
            
            Вы стали нашим *постоянным клиентом*!
            
            Спасибо за вашу лояльность. Продолжайте совершать покупки и станьте VIP! 👑
            """;
        
        sendMessage(user, message);
    }
    
    /**
     * Триггер: Клиент стал VIP
     */
    public void onBecameVip(User user) {
        String message = """
            👑 *Поздравляем!*
            
            Теперь вы наш *VIP клиент*!
            
            🎉 Благодарим вас за постоянство!
            Вас ждут особые привилегии и лучшие предложения.
            """;
        
        sendMessage(user, message);
    }
    
    /**
     * Триггер: Изменение статуса
     */
    public void onStatusChanged(User user, CustomerStatus oldStatus, CustomerStatus newStatus) {
        if (oldStatus == newStatus) return;
        
        switch (newStatus) {
            case REGULAR -> onBecameRegular(user);
            case VIP -> onBecameVip(user);
            case LOST -> {
                // Не отправляем сообщение при переходе в LOST
                // Это делается через отдельный scheduled job с другим сообщением
            }
            default -> {} // NEW - не требует уведомления
        }
    }
    
    // ========== Триггеры штампов ==========
    
    /**
     * Триггер: Остался 1 штамп до награды
     */
    public void onOneStampAway(User user, String rewardTitle) {
        String message = """
            ☕ *Почти готово!*
            
            До награды "%s" остался всего *1 штамп*!
            
            Приходите скорее! 🎁
            """.formatted(rewardTitle);
        
        sendMessage(user, message);
    }
    
    /**
     * Триггер: Заработана награда
     */
    public void onRewardEarned(User user, String rewardTitle, int stampsCount, int stampsRequired) {
        String stampVisual = generateStampVisual(stampsCount, stampsRequired);
        
        String message = """
            🎉 *Поздравляем!*
            
            Вы заработали награду: *%s*!
            
            %s
            
            Нажмите "🎁 Мои штампы" → "Получить награду" для погашения.
            """.formatted(rewardTitle, stampVisual);
        
        sendMessage(user, message);
    }
    
    /**
     * Триггер: Штамп добавлен (без награды)
     */
    public void onStampAdded(User user, int stampsCount, int stampsRequired, int stampsUntilReward) {
        String stampVisual = generateStampVisual(stampsCount, stampsRequired);
        
        String message = """
            ☕ *+1 штамп!*
            
            %s
            
            До награды: %d %s
            """.formatted(
                stampVisual,
                stampsUntilReward,
                getStampWord(stampsUntilReward)
            );
        
        sendMessage(user, message);
    }
    
    /**
     * Триггер: Награда погашена
     */
    public void onRewardRedeemed(User user, String rewardTitle) {
        String message = """
            ✅ *Награда получена!*
            
            🎁 %s
            
            Приятного! Начинайте копить штампы снова! ☕
            """.formatted(rewardTitle);
        
        sendMessage(user, message);
    }
    
    // ========== Триггеры возврата клиентов ==========
    
    /**
     * Триггер: VIP клиент неактивен
     */
    public void onVipInactive(User user, int inactiveDays) {
        String message = """
            👑 Мы скучаем по вам!
            
            Вы не заходили к нам уже %d дней.
            
            Возвращайтесь! Вас ждут приятные сюрпризы! 🎁
            """.formatted(inactiveDays);
        
        sendMessage(user, message);
    }
    
    /**
     * Триггер: Клиент стал LOST (давно не был)
     */
    public void onBecameLost(User user) {
        String message = """
            😢 *Мы скучаем!*
            
            Давно вас не видели...
            
            Возвращайтесь! Мы всегда вам рады! ❤️
            """;
        
        sendMessage(user, message);
    }
    
    // ========== Вспомогательные методы ==========
    
    private String generateStampVisual(int current, int required) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < required; i++) {
            if (i < current) {
                sb.append("☕");
            } else {
                sb.append("○");
            }
            if ((i + 1) % 5 == 0 && i < required - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
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
}



