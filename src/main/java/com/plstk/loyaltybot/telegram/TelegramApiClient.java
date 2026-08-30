package com.plstk.loyaltybot.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Клиент для работы с Telegram Bot API.
 * Все вызовы идут через HTTP, без использования telegram-bots library.
 * Поддерживает динамические токены для multi-bot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramApiClient {
    
    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot%s/%s";
    
    private final RestTemplate restTemplate;
    
    /**
     * Результат вызова getMe
     */
    public record BotInfo(Long id, String username, String firstName, boolean canJoinGroups, boolean canReadAllGroupMessages) {}
    
    /**
     * Получает информацию о боте
     */
    public BotInfo getMe(String botToken) {
        try {
            Map<String, Object> response = callApi(botToken, "getMe", null);
            
            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                return new BotInfo(
                    ((Number) result.get("id")).longValue(),
                    (String) result.get("username"),
                    (String) result.get("first_name"),
                    Boolean.TRUE.equals(result.get("can_join_groups")),
                    Boolean.TRUE.equals(result.get("can_read_all_group_messages"))
                );
            }
            
            throw new TelegramApiException("Failed to get bot info: " + response);
        } catch (RestClientException e) {
            log.error("Error calling getMe", e);
            throw new TelegramApiException("Failed to call Telegram API: " + e.getMessage(), e);
        }
    }
    
    /**
     * Устанавливает webhook для бота
     */
    public boolean setWebhook(String botToken, String url, String secretToken) {
        try {
            Map<String, Object> params = Map.of(
                "url", url,
                "secret_token", secretToken,
                "allowed_updates", List.of("message", "callback_query", "my_chat_member"),
                "drop_pending_updates", false
            );
            
            Map<String, Object> response = callApi(botToken, "setWebhook", params);
            boolean success = response != null && Boolean.TRUE.equals(response.get("ok"));
            
            if (success) {
                log.info("Webhook set successfully for URL: {}", url);
            } else {
                log.error("Failed to set webhook: {}", response);
            }
            
            return success;
        } catch (RestClientException e) {
            log.error("Error setting webhook", e);
            throw new TelegramApiException("Failed to set webhook: " + e.getMessage(), e);
        }
    }
    
    /**
     * Удаляет webhook
     */
    public boolean deleteWebhook(String botToken, boolean dropPendingUpdates) {
        try {
            Map<String, Object> params = Map.of("drop_pending_updates", dropPendingUpdates);
            Map<String, Object> response = callApi(botToken, "deleteWebhook", params);
            return response != null && Boolean.TRUE.equals(response.get("ok"));
        } catch (RestClientException e) {
            log.error("Error deleting webhook", e);
            throw new TelegramApiException("Failed to delete webhook: " + e.getMessage(), e);
        }
    }
    
    /**
     * Устанавливает команды бота
     */
    public boolean setMyCommands(String botToken, List<BotCommand> commands) {
        try {
            List<Map<String, String>> commandsList = commands.stream()
                .map(cmd -> Map.of("command", cmd.command(), "description", cmd.description()))
                .toList();
            
            Map<String, Object> params = Map.of("commands", commandsList);
            Map<String, Object> response = callApi(botToken, "setMyCommands", params);
            return response != null && Boolean.TRUE.equals(response.get("ok"));
        } catch (RestClientException e) {
            log.error("Error setting commands", e);
            throw new TelegramApiException("Failed to set commands: " + e.getMessage(), e);
        }
    }
    
    /**
     * Устанавливает описание бота
     */
    public boolean setMyDescription(String botToken, String description) {
        try {
            Map<String, Object> params = Map.of("description", description);
            Map<String, Object> response = callApi(botToken, "setMyDescription", params);
            return response != null && Boolean.TRUE.equals(response.get("ok"));
        } catch (RestClientException e) {
            log.error("Error setting description", e);
            return false; // Не критично
        }
    }
    
    /**
     * Устанавливает короткое описание бота
     */
    public boolean setMyShortDescription(String botToken, String shortDescription) {
        try {
            Map<String, Object> params = Map.of("short_description", shortDescription);
            Map<String, Object> response = callApi(botToken, "setMyShortDescription", params);
            return response != null && Boolean.TRUE.equals(response.get("ok"));
        } catch (RestClientException e) {
            log.error("Error setting short description", e);
            return false; // Не критично
        }
    }
    
    /**
     * Отправляет сообщение
     */
    public MessageResult sendMessage(String botToken, Long chatId, String text, Object replyMarkup, String parseMode) {
        try {
            var paramsBuilder = new java.util.HashMap<String, Object>();
            paramsBuilder.put("chat_id", chatId);
            paramsBuilder.put("text", text);
            
            if (parseMode != null) {
                paramsBuilder.put("parse_mode", parseMode);
            }
            
            if (replyMarkup != null) {
                paramsBuilder.put("reply_markup", replyMarkup);
            }
            
            Map<String, Object> response = callApi(botToken, "sendMessage", paramsBuilder);
            
            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                return new MessageResult(
                    true,
                    ((Number) result.get("message_id")).intValue(),
                    null
                );
            }
            
            String errorDescription = response != null ? (String) response.get("description") : "Unknown error";
            return new MessageResult(false, null, errorDescription);
        } catch (RestClientException e) {
            log.error("Error sending message to chatId={}", chatId, e);
            return new MessageResult(false, null, e.getMessage());
        }
    }
    
    /**
     * Отправляет фото по URL
     */
    public MessageResult sendPhoto(String botToken, Long chatId, String photoUrl, String caption,
                                   Object replyMarkup, String parseMode) {
        try {
            var paramsBuilder = new java.util.HashMap<String, Object>();
            paramsBuilder.put("chat_id", chatId);
            paramsBuilder.put("photo", photoUrl);

            if (caption != null) {
                paramsBuilder.put("caption", caption);
            }
            if (parseMode != null) {
                paramsBuilder.put("parse_mode", parseMode);
            }
            if (replyMarkup != null) {
                paramsBuilder.put("reply_markup", replyMarkup);
            }

            Map<String, Object> response = callApi(botToken, "sendPhoto", paramsBuilder);

            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                return new MessageResult(
                        true,
                        ((Number) result.get("message_id")).intValue(),
                        null
                );
            }

            String errorDescription = response != null ? (String) response.get("description") : "Unknown error";
            return new MessageResult(false, null, errorDescription);
        } catch (RestClientException e) {
            log.error("Error sending photo to chatId={}", chatId, e);
            return new MessageResult(false, null, e.getMessage());
        }
    }

    /**
     * Отправляет фото как multipart upload (Telegram не всегда может скачать URL с self-hosted серверов).
     */
    public MessageResult sendPhotoBytes(String botToken, Long chatId, byte[] photoBytes, String filename,
                                        String caption, Object replyMarkup, String parseMode) {
        try {
            String url = String.format(TELEGRAM_API_URL, botToken, "sendPhoto");

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("chat_id", chatId);
            body.add("photo", new ByteArrayResource(photoBytes) {
                @Override
                public String getFilename() {
                    return filename != null ? filename : "photo.jpg";
                }
            });

            if (caption != null) {
                body.add("caption", caption);
            }
            if (parseMode != null) {
                body.add("parse_mode", parseMode);
            }
            if (replyMarkup != null) {
                body.add("reply_markup", replyMarkup);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && Boolean.TRUE.equals(responseBody.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) responseBody.get("result");
                return new MessageResult(
                        true,
                        ((Number) result.get("message_id")).intValue(),
                        null
                );
            }

            String errorDescription = responseBody != null ? (String) responseBody.get("description") : "Unknown error";
            return new MessageResult(false, null, errorDescription);
        } catch (RestClientException e) {
            log.error("Error uploading photo to chatId={}", chatId, e);
            return new MessageResult(false, null, e.getMessage());
        }
    }

    /**
     * Редактирует сообщение
     */
    public boolean editMessageText(String botToken, Long chatId, Integer messageId, String text, Object replyMarkup, String parseMode) {
        try {
            var paramsBuilder = new java.util.HashMap<String, Object>();
            paramsBuilder.put("chat_id", chatId);
            paramsBuilder.put("message_id", messageId);
            paramsBuilder.put("text", text);
            
            if (parseMode != null) {
                paramsBuilder.put("parse_mode", parseMode);
            }
            
            if (replyMarkup != null) {
                paramsBuilder.put("reply_markup", replyMarkup);
            }
            
            Map<String, Object> response = callApi(botToken, "editMessageText", paramsBuilder);
            return response != null && Boolean.TRUE.equals(response.get("ok"));
        } catch (RestClientException e) {
            log.error("Error editing message", e);
            return false;
        }
    }
    
    /**
     * Отвечает на callback query
     */
    public boolean answerCallbackQuery(String botToken, String callbackQueryId, String text, boolean showAlert) {
        try {
            var params = new java.util.HashMap<String, Object>();
            params.put("callback_query_id", callbackQueryId);
            if (text != null) {
                params.put("text", text);
            }
            params.put("show_alert", showAlert);
            
            Map<String, Object> response = callApi(botToken, "answerCallbackQuery", params);
            return response != null && Boolean.TRUE.equals(response.get("ok"));
        } catch (RestClientException e) {
            log.error("Error answering callback query", e);
            return false;
        }
    }
    
    /**
     * Получает информацию о webhook
     */
    public WebhookInfo getWebhookInfo(String botToken) {
        try {
            Map<String, Object> response = callApi(botToken, "getWebhookInfo", null);
            
            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                return new WebhookInfo(
                    (String) result.get("url"),
                    Boolean.TRUE.equals(result.get("has_custom_certificate")),
                    result.get("pending_update_count") != null ? ((Number) result.get("pending_update_count")).intValue() : 0,
                    (String) result.get("last_error_message"),
                    result.get("last_error_date") != null ? ((Number) result.get("last_error_date")).longValue() : null
                );
            }
            
            return null;
        } catch (RestClientException e) {
            log.error("Error getting webhook info", e);
            return null;
        }
    }
    
    // ========== Helper Methods ==========
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> callApi(String botToken, String method, Map<String, Object> params) {
        String url = String.format(TELEGRAM_API_URL, botToken, method);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<?> entity = params != null 
            ? new HttpEntity<>(params, headers) 
            : new HttpEntity<>(headers);
        
        ResponseEntity<Map> response = restTemplate.exchange(
            url,
            params != null ? HttpMethod.POST : HttpMethod.GET,
            entity,
            Map.class
        );
        
        return response.getBody();
    }
    
    // ========== Records ==========
    
    public record BotCommand(String command, String description) {}
    
    public record MessageResult(boolean ok, Integer messageId, String error) {}
    
    public record WebhookInfo(String url, boolean hasCustomCertificate, int pendingUpdateCount, 
                              String lastErrorMessage, Long lastErrorDate) {}
    
    // ========== Exception ==========
    
    public static class TelegramApiException extends RuntimeException {
        public TelegramApiException(String message) {
            super(message);
        }
        
        public TelegramApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

