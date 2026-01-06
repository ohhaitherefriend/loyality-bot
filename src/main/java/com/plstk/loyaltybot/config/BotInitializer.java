package com.plstk.loyaltybot.config;

import com.plstk.loyaltybot.bot.LoyaltyBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Component
@RequiredArgsConstructor
@Slf4j
public class BotInitializer {
    
    private final LoyaltyBot loyaltyBot;
    
    @EventListener(ContextRefreshedEvent.class)
    public void init() throws TelegramApiException {
        log.info("Initializing Telegram Bot...");
        
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
