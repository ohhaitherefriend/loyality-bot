package com.plstk.loyaltybot.max;

import com.plstk.loyaltybot.entity.BotInstance;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Контекст для обработки Max Update.
 * Содержит всю необходимую информацию для работы с конкретным ботом Max.
 */
@Data
@Builder
public class MaxContext {

    private final String shopId;
    private final Long botInstanceId;
    private final String botToken;
    private final String botUsername;

    /** Raw Max Update (JSON-десериализованный в Map) */
    private final Map<String, Object> update;

    /** User ID отправителя (= Max user_id) */
    private final Long chatId;

    private final BotInstance botInstance;

    public static MaxContext from(BotInstance botInstance, String decryptedToken, Map<String, Object> update) {
        Long userId = extractUserId(update);

        return MaxContext.builder()
                .shopId(botInstance.getShopId())
                .botInstanceId(botInstance.getId())
                .botToken(decryptedToken)
                .botUsername(botInstance.getBotUsername())
                .update(update)
                .chatId(userId)
                .botInstance(botInstance)
                .build();
    }

    public String getUpdateType() {
        return (String) update.get("update_type");
    }

    public boolean isMessageCreated() {
        return "message_created".equals(getUpdateType());
    }

    public boolean isMessageCallback() {
        return "message_callback".equals(getUpdateType());
    }

    public boolean isBotStarted() {
        return "bot_started".equals(getUpdateType());
    }

    @SuppressWarnings("unchecked")
    public String getMessageText() {
        Map<String, Object> message = getMessage();
        if (message == null) return null;
        Map<String, Object> body = (Map<String, Object>) message.get("body");
        if (body == null) return null;
        return (String) body.get("text");
    }

    /**
     * Возвращает mid сообщения (строковый ID)
     */
    @SuppressWarnings("unchecked")
    public String getMessageId() {
        Map<String, Object> message = getMessage();
        if (message == null) return null;
        Map<String, Object> body = (Map<String, Object>) message.get("body");
        if (body == null) return null;
        Object mid = body.get("mid");
        return mid != null ? mid.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public String getCallbackId() {
        Map<String, Object> callback = (Map<String, Object>) update.get("callback");
        if (callback == null) return null;
        return (String) callback.get("callback_id");
    }

    @SuppressWarnings("unchecked")
    public String getCallbackPayload() {
        Map<String, Object> callback = (Map<String, Object>) update.get("callback");
        if (callback == null) return null;
        return (String) callback.get("payload");
    }

    public Long getUserId() {
        return chatId;
    }

    @SuppressWarnings("unchecked")
    public String getUserFirstName() {
        Map<String, Object> user = getSenderUser();
        if (user != null) return (String) user.get("first_name");

        // Для bot_started
        Map<String, Object> startUser = (Map<String, Object>) update.get("user");
        if (startUser != null) return (String) startUser.get("first_name");

        return null;
    }

    @SuppressWarnings("unchecked")
    public String getUserLastName() {
        Map<String, Object> user = getSenderUser();
        if (user != null) return (String) user.get("last_name");

        Map<String, Object> startUser = (Map<String, Object>) update.get("user");
        if (startUser != null) return (String) startUser.get("last_name");

        return null;
    }

    @SuppressWarnings("unchecked")
    public String getUserUsername() {
        Map<String, Object> user = getSenderUser();
        if (user != null) return (String) user.get("username");

        Map<String, Object> startUser = (Map<String, Object>) update.get("user");
        if (startUser != null) return (String) startUser.get("username");

        return null;
    }

    /**
     * Проверяет, содержит ли update контактные данные (request_contact)
     */
    @SuppressWarnings("unchecked")
    public boolean hasContact() {
        Map<String, Object> message = getMessage();
        if (message == null) return false;
        Map<String, Object> body = (Map<String, Object>) message.get("body");
        if (body == null) return false;

        List<Map<String, Object>> attachments = (List<Map<String, Object>>) body.get("attachments");
        if (attachments == null) return false;

        return attachments.stream()
                .anyMatch(a -> "contact".equals(a.get("type")));
    }

    /**
     * Извлекает номер телефона из contact-вложения.
     * Max отправляет контакт в vCard формате внутри payload.vcf_info:
     * "BEGIN:VCARD\nTEL;TYPE=cell:79160131996\nEND:VCARD"
     */
    @SuppressWarnings("unchecked")
    public String getContactPhoneNumber() {
        Map<String, Object> message = getMessage();
        if (message == null) return null;
        Map<String, Object> body = (Map<String, Object>) message.get("body");
        if (body == null) return null;

        List<Map<String, Object>> attachments = (List<Map<String, Object>>) body.get("attachments");
        if (attachments == null) return null;

        for (Map<String, Object> att : attachments) {
            if ("contact".equals(att.get("type"))) {
                Map<String, Object> payload = (Map<String, Object>) att.get("payload");
                if (payload == null) continue;

                String vcfInfo = (String) payload.get("vcf_info");
                if (vcfInfo != null) {
                    return extractPhoneFromVCard(vcfInfo);
                }
            }
        }
        return null;
    }

    private static String extractPhoneFromVCard(String vcf) {
        for (String line : vcf.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith("TEL")) {
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx >= 0 && colonIdx < trimmed.length() - 1) {
                    return trimmed.substring(colonIdx + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * Извлекает параметр payload из bot_started (deep link)
     */
    public String getStartPayload() {
        if (!isBotStarted()) return null;
        return (String) update.get("payload");
    }

    // ========== Private helpers ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMessage() {
        return (Map<String, Object>) update.get("message");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSenderUser() {
        Map<String, Object> message = getMessage();
        if (message != null) {
            return (Map<String, Object>) message.get("sender");
        }
        // For callback updates
        Map<String, Object> callback = (Map<String, Object>) update.get("callback");
        if (callback != null) {
            return (Map<String, Object>) callback.get("user");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Long extractUserId(Map<String, Object> update) {
        // message_created: update.message.sender.user_id
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message != null) {
            Map<String, Object> sender = (Map<String, Object>) message.get("sender");
            if (sender != null && sender.get("user_id") != null) {
                return ((Number) sender.get("user_id")).longValue();
            }
        }

        // message_callback: update.callback.user.user_id
        Map<String, Object> callback = (Map<String, Object>) update.get("callback");
        if (callback != null) {
            Map<String, Object> user = (Map<String, Object>) callback.get("user");
            if (user != null && user.get("user_id") != null) {
                return ((Number) user.get("user_id")).longValue();
            }
        }

        // bot_started: update.user.user_id
        Map<String, Object> user = (Map<String, Object>) update.get("user");
        if (user != null && user.get("user_id") != null) {
            return ((Number) user.get("user_id")).longValue();
        }

        return null;
    }
}
