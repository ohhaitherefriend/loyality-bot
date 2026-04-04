package com.plstk.loyaltybot.telegram;

import com.plstk.loyaltybot.service.LoyaltyBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Роутер для обработки Telegram Updates.
 * Получает TelegramContext и направляет на обработку в соответствующий сервис.
 * 
 * Асинхронная обработка для быстрого ответа Telegram серверу.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramUpdateRouter {
    
    private final LoyaltyBotService loyaltyBotService;
    
    /**
     * Направляет Update на обработку.
     * Выполняется асинхронно.
     */
    @Async("telegramUpdateExecutor")
    public void route(TelegramContext context) {
        if (context == null) {
            log.warn("Received null context, skipping");
            return;
        }
        
        try {
            log.debug("Routing update for shopId={}, chatId={}", 
                context.getShopId(), context.getChatId());
            
            // Вся логика обработки в LoyaltyBotService
            loyaltyBotService.processUpdate(context);
            
        } catch (Exception e) {
            log.error("Error routing update for shopId={}, chatId={}: {}", 
                context.getShopId(), context.getChatId(), e.getMessage(), e);
            
            // Пытаемся отправить сообщение об ошибке пользователю
            try {
                loyaltyBotService.sendErrorMessage(context, "Произошла ошибка. Попробуйте позже.");
            } catch (Exception ex) {
                log.error("Failed to send error message", ex);
            }
        }
    }
}

