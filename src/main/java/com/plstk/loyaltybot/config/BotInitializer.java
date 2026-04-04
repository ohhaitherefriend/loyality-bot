package com.plstk.loyaltybot.config;

import com.plstk.loyaltybot.bot.LoyaltyBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Инициализатор для single-tenant режима (Long Polling).
 * Активируется только если указан telegram.bot.token.
 * 
 * Для multi-tenant режима (BYOB) этот класс не используется -
 * вместо него боты работают через webhook.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.token", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class BotInitializer {
    
    private final LoyaltyBot loyaltyBot;
    
    @Value("${telegram.bot.token:}")
    private String botToken;
    
    @EventListener(ContextRefreshedEvent.class)
    public void init() throws TelegramApiException {
        // Пропускаем если токен пустой
        if (botToken == null || botToken.trim().isEmpty()) {
            log.info("🔄 Single-tenant bot disabled (no token). Running in multi-tenant (BYOB) mode.");
            return;
        }
        
        log.info("Initializing Telegram Bot (single-tenant mode)...");
        
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(loyaltyBot);
            log.info("✅ Telegram Bot registered successfully! Username: {}", loyaltyBot.getBotUsername());
            log.info("🤖 Bot is ready to receive messages!");
        } catch (TelegramApiException e) {
            log.error("❌ Failed to register Telegram Bot: {}", e.getMessage(), e);
            throw e;
        }
    }
}
