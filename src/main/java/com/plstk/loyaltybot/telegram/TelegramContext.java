package com.plstk.loyaltybot.telegram;

import com.plstk.loyaltybot.entity.BotInstance;
import lombok.Builder;
import lombok.Data;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Контекст для обработки Telegram Update.
 * Содержит всю необходимую информацию для работы с конкретным ботом.
 */
@Data
@Builder
public class TelegramContext {
    
    /**
     * ID магазина/бизнеса (для multi-tenancy)
     */
    private final String shopId;
    
    /**
     * ID инстанса бота в БД
     */
    private final Long botInstanceId;
    
    /**
     * Расшифрованный токен бота
     */
    private final String botToken;
    
    /**
     * Username бота (без @)
     */
    private final String botUsername;
    
    /**
     * Telegram Update для обработки
     */
    private final Update update;
    
    /**
     * Chat ID из Update (для удобства)
     */
    private final Long chatId;
    
    /**
     * Инстанс бота (полная сущность)
     */
    private final BotInstance botInstance;
    
    /**
     * Создаёт контекст из BotInstance и Update
     */
    public static TelegramContext from(BotInstance botInstance, String decryptedToken, Update update) {
        Long chatId = extractChatId(update);
        
        return TelegramContext.builder()
            .shopId(botInstance.getShopId())
            .botInstanceId(botInstance.getId())
            .botToken(decryptedToken)
            .botUsername(botInstance.getBotUsername())
            .update(update)
            .chatId(chatId)
            .botInstance(botInstance)
            .build();
    }
    
    /**
     * Извлекает chatId из Update
     */
    private static Long extractChatId(Update update) {
        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        } else if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getMessage().getChatId();
        } else if (update.hasEditedMessage()) {
            return update.getEditedMessage().getChatId();
        } else if (update.hasChannelPost()) {
            return update.getChannelPost().getChatId();
        } else if (update.hasMyChatMember()) {
            return update.getMyChatMember().getChat().getId();
        }
        return null;
    }
    
    /**
     * Возвращает текст сообщения (если есть)
     */
    public String getMessageText() {
        if (update.hasMessage() && update.getMessage().hasText()) {
            return update.getMessage().getText();
        }
        return null;
    }
    
    /**
     * Возвращает callback data (если есть)
     */
    public String getCallbackData() {
        if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getData();
        }
        return null;
    }
    
    /**
     * Проверяет, является ли это callback query
     */
    public boolean isCallbackQuery() {
        return update.hasCallbackQuery();
    }
    
    /**
     * Проверяет, является ли это сообщением
     */
    public boolean isMessage() {
        return update.hasMessage();
    }
    
    /**
     * Возвращает user ID отправителя
     */
    public Long getUserId() {
        if (update.hasMessage()) {
            return update.getMessage().getFrom().getId();
        } else if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getFrom().getId();
        }
        return null;
    }
    
    /**
     * Возвращает Message ID (для редактирования)
     */
    public Integer getMessageId() {
        if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getMessage().getMessageId();
        } else if (update.hasMessage()) {
            return update.getMessage().getMessageId();
        }
        return null;
    }
}

