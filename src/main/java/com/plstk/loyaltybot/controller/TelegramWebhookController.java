package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.BotInstance;
import com.plstk.loyaltybot.service.BotInstanceService;
import com.plstk.loyaltybot.service.SubscriptionService;
import com.plstk.loyaltybot.telegram.TelegramApiClient;
import com.plstk.loyaltybot.telegram.TelegramContext;
import com.plstk.loyaltybot.telegram.TelegramUpdateRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Optional;

/**
 * Контроллер для приёма webhook запросов от Telegram.
 * 
 * Endpoint: POST /tg/webhook/{botInstanceId}/{secret}
 * 
 * Валидирует secret, находит BotInstance, создаёт TelegramContext
 * и передаёт обработку в TelegramUpdateRouter.
 */
@RestController
@RequestMapping("/tg")
@RequiredArgsConstructor
@Slf4j
public class TelegramWebhookController {
    
    private final BotInstanceService botInstanceService;
    private final TelegramUpdateRouter updateRouter;
    private final SubscriptionService subscriptionService;
    private final TelegramApiClient telegramApiClient;
    
    /**
     * Основной endpoint для приёма Telegram Updates через webhook.
     * 
     * @param botInstanceId ID бота в БД
     * @param secret Секретный токен для валидации
     * @param update Telegram Update
     * @return 200 OK (всегда, чтобы Telegram не ретраил)
     */
    @PostMapping("/webhook/{botInstanceId}/{secret}")
    public ResponseEntity<Void> handleWebhook(
            @PathVariable Long botInstanceId,
            @PathVariable String secret,
            @RequestBody Update update) {
        
        try {
            // 1. Валидируем секрет и получаем BotInstance
            Optional<BotInstance> botOpt = botInstanceService.validateWebhook(botInstanceId, secret);
            
            if (botOpt.isEmpty()) {
                log.warn("Invalid webhook request: botInstanceId={}, secret mismatch", botInstanceId);
                // Возвращаем 200 чтобы Telegram не ретраил
                return ResponseEntity.ok().build();
            }
            
            BotInstance botInstance = botOpt.get();

            // 2. Проверяем что бот активен
            if (!botInstance.getIsActive() || botInstance.getStatus() != BotInstance.BotStatus.ACTIVE) {
                log.warn("Bot {} is not active, ignoring update", botInstanceId);
                return ResponseEntity.ok().build();
            }

            // 3. Проверяем подписку магазина
            if (!subscriptionService.isAccessGranted(botInstance.getShopId())) {
                log.info("Subscription expired for shopId={}, blocking bot {}", botInstance.getShopId(), botInstanceId);
                Long chatId = extractChatId(update);
                if (chatId != null) {
                    try {
                        String token = botInstanceService.getDecryptedToken(botInstance);
                        telegramApiClient.sendMessage(token, chatId,
                                "⏸ Бот временно приостановлен. Владельцу необходимо продлить подписку.",
                                null, null);
                    } catch (Exception ex) {
                        log.debug("Could not send pause message: {}", ex.getMessage());
                    }
                }
                return ResponseEntity.ok().build();
            }

            // 4. Создаём контекст и передаём на обработку
            TelegramContext context = botInstanceService.createContext(botInstance, update);
            
            if (context != null) {
                // 4. Асинхронно обрабатываем update
                updateRouter.route(context);
                
                // 5. Обновляем статистику бота
                botInstanceService.recordWebhook(botInstance);
            }
            
        } catch (Exception e) {
            log.error("Error processing webhook for bot {}: {}", botInstanceId, e.getMessage(), e);
            // Всё равно возвращаем 200 чтобы Telegram не ретраил
        }
        
        return ResponseEntity.ok().build();
    }
    
    /**
     * Health check endpoint для webhook
     */
    @GetMapping("/webhook/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Webhook endpoint is ready");
    }
    
    private Long extractChatId(Update update) {
        if (update.hasMessage()) return update.getMessage().getChatId();
        if (update.hasCallbackQuery()) return update.getCallbackQuery().getMessage().getChatId();
        if (update.hasEditedMessage()) return update.getEditedMessage().getChatId();
        if (update.hasMyChatMember()) return update.getMyChatMember().getChat().getId();
        return null;
    }
}

