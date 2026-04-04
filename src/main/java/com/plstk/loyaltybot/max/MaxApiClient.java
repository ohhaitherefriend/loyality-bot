package com.plstk.loyaltybot.max;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Клиент для работы с Max Bot API (platform-api.max.ru).
 * Авторизация через заголовок Authorization.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MaxApiClient {

    private static final String MAX_API_URL = "https://platform-api.max.ru";

    private final RestTemplate restTemplate;

    public record BotInfo(Long userId, String username, String firstName, boolean isBot) {}

    public record MessageResult(boolean ok, String messageId, String error) {}

    /**
     * GET /me — информация о боте
     */
    public BotInfo getMe(String accessToken) {
        try {
            Map<String, Object> response = callGet(accessToken, "/me");
            if (response != null && response.containsKey("user_id")) {
                return new BotInfo(
                        ((Number) response.get("user_id")).longValue(),
                        (String) response.get("username"),
                        (String) response.get("first_name"),
                        Boolean.TRUE.equals(response.get("is_bot"))
                );
            }
            throw new MaxApiException("Failed to get bot info: " + response);
        } catch (RestClientException e) {
            log.error("Error calling Max getMe", e);
            throw new MaxApiException("Failed to call Max API: " + e.getMessage(), e);
        }
    }

    /**
     * POST /subscriptions — подписка на webhook
     */
    public boolean subscribe(String accessToken, String url, List<String> updateTypes, String secret) {
        try {
            var body = new java.util.HashMap<String, Object>();
            body.put("url", url);
            if (updateTypes != null && !updateTypes.isEmpty()) {
                body.put("update_types", updateTypes);
            }
            if (secret != null && !secret.isEmpty()) {
                body.put("secret", secret);
            }

            Map<String, Object> response = callPost(accessToken, "/subscriptions", body);
            boolean success = response != null && Boolean.TRUE.equals(response.get("success"));

            if (success) {
                log.info("Max webhook subscription set for URL: {}", url);
            } else {
                log.error("Failed to subscribe Max webhook: {}", response);
            }
            return success;
        } catch (RestClientException e) {
            log.error("Error subscribing Max webhook", e);
            throw new MaxApiException("Failed to subscribe: " + e.getMessage(), e);
        }
    }

    /**
     * DELETE /subscriptions — отписка от webhook
     */
    public boolean unsubscribe(String accessToken, String url) {
        try {
            String fullUrl = UriComponentsBuilder.fromUriString(MAX_API_URL + "/subscriptions")
                    .queryParam("url", url)
                    .toUriString();

            HttpHeaders headers = createHeaders(accessToken);
            HttpEntity<?> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(fullUrl, HttpMethod.DELETE, entity, Map.class);
            Map<String, Object> body = response.getBody();
            return body != null && Boolean.TRUE.equals(body.get("success"));
        } catch (RestClientException e) {
            log.error("Error unsubscribing Max webhook", e);
            return false;
        }
    }

    /**
     * POST /messages?user_id={userId} — отправка сообщения пользователю
     */
    public MessageResult sendMessage(String accessToken, Long userId, String text,
                                     List<Object> attachments, String format) {
        try {
            String url = UriComponentsBuilder.fromUriString(MAX_API_URL + "/messages")
                    .queryParam("user_id", userId)
                    .toUriString();

            var body = new java.util.HashMap<String, Object>();
            if (text != null) {
                body.put("text", text);
            }
            if (attachments != null && !attachments.isEmpty()) {
                body.put("attachments", attachments);
            }
            if (format != null) {
                body.put("format", format);
            }

            Map<String, Object> response = callPostRaw(accessToken, url, body);

            if (response != null && response.containsKey("message")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = (Map<String, Object>) response.get("message");
                Object bodyObj = msg.get("body");
                String mid = null;
                if (bodyObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msgBody = (Map<String, Object>) bodyObj;
                    mid = msgBody.get("mid") != null ? msgBody.get("mid").toString() : null;
                }
                if (mid == null && msg.get("body") instanceof Map) {
                    mid = "ok";
                }
                return new MessageResult(true, mid, null);
            }

            String errorMsg = response != null ? String.valueOf(response.get("message")) : "Unknown error";
            return new MessageResult(false, null, errorMsg);
        } catch (RestClientException e) {
            log.error("Error sending Max message to userId={}", userId, e);
            return new MessageResult(false, null, e.getMessage());
        }
    }

    /**
     * PUT /messages?message_id={messageId} — редактирование сообщения
     */
    public boolean editMessage(String accessToken, String messageId, String text,
                               List<Object> attachments, String format) {
        try {
            String url = UriComponentsBuilder.fromUriString(MAX_API_URL + "/messages")
                    .queryParam("message_id", messageId)
                    .toUriString();

            var body = new java.util.HashMap<String, Object>();
            if (text != null) {
                body.put("text", text);
            }
            if (attachments != null) {
                body.put("attachments", attachments);
            }
            if (format != null) {
                body.put("format", format);
            }

            HttpHeaders headers = createHeaders(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<?> entity = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            Map<String, Object> result = response.getBody();
            return result != null && Boolean.TRUE.equals(result.get("success"));
        } catch (RestClientException e) {
            log.error("Error editing Max message {}", messageId, e);
            return false;
        }
    }

    /**
     * POST /answers?callback_id={callbackId} — ответ на callback
     */
    public boolean answerCallback(String accessToken, String callbackId,
                                  Map<String, Object> message, String notification) {
        try {
            String url = UriComponentsBuilder.fromUriString(MAX_API_URL + "/answers")
                    .queryParam("callback_id", callbackId)
                    .toUriString();

            var body = new java.util.HashMap<String, Object>();
            if (message != null) {
                body.put("message", message);
            }
            if (notification != null) {
                body.put("notification", notification);
            }

            Map<String, Object> response = callPostRaw(accessToken, url, body);
            return response != null && Boolean.TRUE.equals(response.get("success"));
        } catch (RestClientException e) {
            log.error("Error answering Max callback {}", callbackId, e);
            return false;
        }
    }

    // ========== Helper Methods ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> callGet(String accessToken, String path) {
        String url = MAX_API_URL + path;
        HttpHeaders headers = createHeaders(accessToken);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callPost(String accessToken, String path, Map<String, Object> params) {
        String url = MAX_API_URL + path;
        return callPostRaw(accessToken, url, params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callPostRaw(String accessToken, String url, Map<String, Object> params) {
        HttpHeaders headers = createHeaders(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<?> entity = params != null
                ? new HttpEntity<>(params, headers)
                : new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        return response.getBody();
    }

    private HttpHeaders createHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", accessToken);
        return headers;
    }

    // ========== Exception ==========

    public static class MaxApiException extends RuntimeException {
        public MaxApiException(String message) {
            super(message);
        }

        public MaxApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
