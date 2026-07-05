package com.plstk.loyaltybot.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Простой support-бот для Telegram.
 * Пересылает сообщения пользователей админу и обратно.
 * Работает через Long Polling (не webhook).
 */
@Service
@Slf4j
public class SupportBotService {

    private static final String API_URL = "https://api.telegram.org/bot%s/%s";

    @Value("${support.bot.token:}")
    private String botToken;

    @Value("${support.bot.admin-chat-id:0}")
    private Long adminChatId;

    private final RestTemplate restTemplate;
    private Long lastUpdateId = 0L;
    private final ConcurrentHashMap<Long, Long> adminReplyMap = new ConcurrentHashMap<>();

    public SupportBotService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    public void init() {
        if (isConfigured()) {
            log.info("Support bot configured, admin chatId={}", adminChatId);
        } else {
            log.info("Support bot not configured (no token or admin chatId), skipping");
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void pollUpdates() {
        if (!isConfigured()) return;

        try {
            String url = String.format(API_URL, botToken, "getUpdates")
                    + "?offset=" + (lastUpdateId + 1) + "&timeout=1";

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null || !Boolean.TRUE.equals(body.get("ok"))) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> updates = (List<Map<String, Object>>) body.get("result");
            if (updates == null || updates.isEmpty()) return;

            for (Map<String, Object> update : updates) {
                lastUpdateId = ((Number) update.get("update_id")).longValue();
                processUpdate(update);
            }
        } catch (Exception e) {
            log.debug("Support bot poll error: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void processUpdate(Map<String, Object> update) {
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) return;

        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        Map<String, Object> from = (Map<String, Object>) message.get("from");
        if (chat == null || from == null) return;

        Long chatId = ((Number) chat.get("id")).longValue();
        String text = (String) message.get("text");
        if (text == null || text.isBlank()) return;

        String firstName = (String) from.get("first_name");
        String lastName = (String) from.get("last_name");
        String username = (String) from.get("username");

        if (chatId.equals(adminChatId)) {
            handleAdminReply(message, text);
        } else {
            handleUserMessage(chatId, firstName, lastName, username, text);
        }
    }

    private void handleUserMessage(Long userChatId, String firstName, String lastName, String username, String text) {
        if ("/start".equals(text)) {
            sendMessage(userChatId, "Здравствуйте! Напишите ваш вопрос, и мы ответим в ближайшее время.");
            return;
        }

        String name = firstName != null ? firstName : "";
        if (lastName != null) name += " " + lastName;
        String userInfo = username != null ? " (@" + username + ")" : "";

        String adminMsg = String.format(
                "📩 Сообщение от %s%s [id: %d]:\n\n%s",
                name.trim(), userInfo, userChatId, text
        );

        sendMessage(adminChatId, adminMsg);
        adminReplyMap.put(adminChatId, userChatId);

        sendMessage(userChatId, "Спасибо! Ваше сообщение получено. Мы ответим в ближайшее время.");
    }

    @SuppressWarnings("unchecked")
    private void handleAdminReply(Map<String, Object> message, String text) {
        Map<String, Object> reply = (Map<String, Object>) message.get("reply_to_message");

        Long targetUserId = null;

        if (reply != null) {
            String replyText = (String) reply.get("text");
            if (replyText != null && replyText.contains("[id: ")) {
                try {
                    int start = replyText.indexOf("[id: ") + 5;
                    int end = replyText.indexOf("]", start);
                    targetUserId = Long.parseLong(replyText.substring(start, end));
                } catch (Exception ignored) {}
            }
        }

        if (targetUserId == null) {
            targetUserId = adminReplyMap.get(adminChatId);
        }

        if (targetUserId == null) {
            sendMessage(adminChatId, "Ответьте reply на сообщение пользователя.");
            return;
        }

        sendMessage(targetUserId, text);
        sendMessage(adminChatId, "✅ Ответ отправлен");
    }

    private void sendMessage(Long chatId, String text) {
        try {
            String url = String.format(API_URL, botToken, "sendMessage");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                    "chat_id", chatId,
                    "text", text
            );

            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.error("Failed to send support message to chatId={}: {}", chatId, e.getMessage());
        }
    }

    private boolean isConfigured() {
        return botToken != null && !botToken.isBlank() && adminChatId != null && adminChatId != 0;
    }
}
