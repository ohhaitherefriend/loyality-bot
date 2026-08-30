package com.plstk.loyaltybot.service.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.CommerceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class TelegramInitDataValidator {

    private static final String WEB_APP_DATA = "WebAppData";

    private final CommerceProperties commerceProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ValidatedTelegramUser validate(String rawInitData, String botToken) {
        if (!StringUtils.hasText(rawInitData)) {
            throw new TelegramInitDataException("Missing Telegram init data");
        }
        if (!StringUtils.hasText(botToken)) {
            throw new TelegramInitDataException("Bot token is not configured");
        }

        Map<String, String> fields = parseQueryString(rawInitData);
        String receivedHash = fields.remove("hash");
        if (!StringUtils.hasText(receivedHash)) {
            throw new TelegramInitDataException("Missing hash in init data");
        }

        String dataCheckString = buildDataCheckString(fields);
        String calculatedHash = calculateHash(botToken, dataCheckString);
        if (!calculatedHash.equalsIgnoreCase(receivedHash)) {
            throw new TelegramInitDataException("Invalid init data signature");
        }

        String authDateRaw = fields.get("auth_date");
        if (!StringUtils.hasText(authDateRaw)) {
            throw new TelegramInitDataException("Missing auth_date in init data");
        }

        long authDate;
        try {
            authDate = Long.parseLong(authDateRaw);
        } catch (NumberFormatException e) {
            throw new TelegramInitDataException("Invalid auth_date in init data");
        }

        long maxAge = commerceProperties.getMiniApp().getInitDataMaxAgeSeconds();
        long now = Instant.now().getEpochSecond();
        if (authDate > now + 60) {
            throw new TelegramInitDataException("auth_date is in the future");
        }
        if (now - authDate > maxAge) {
            throw new TelegramInitDataException("init data expired");
        }

        String userJson = fields.get("user");
        if (!StringUtils.hasText(userJson)) {
            throw new TelegramInitDataException("Missing user in init data");
        }

        try {
            JsonNode userNode = objectMapper.readTree(userJson);
            long id = userNode.get("id").asLong();
            String firstName = userNode.hasNonNull("first_name") ? userNode.get("first_name").asText() : null;
            String lastName = userNode.hasNonNull("last_name") ? userNode.get("last_name").asText() : null;
            String username = userNode.hasNonNull("username") ? userNode.get("username").asText() : null;
            return new ValidatedTelegramUser(id, firstName, lastName, username, authDate);
        } catch (Exception e) {
            throw new TelegramInitDataException("Invalid user payload in init data");
        }
    }

    static Map<String, String> parseQueryString(String rawInitData) {
        Map<String, String> result = new TreeMap<>();
        String[] pairs = rawInitData.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            if (idx < 0) {
                result.put(decode(pair), "");
            } else {
                result.put(decode(pair.substring(0, idx)), decode(pair.substring(idx + 1)));
            }
        }
        return result;
    }

    static String buildDataCheckString(Map<String, String> fields) {
        List<String> keys = new ArrayList<>(fields.keySet());
        Collections.sort(keys);
        List<String> lines = new ArrayList<>();
        for (String key : keys) {
            lines.add(key + "=" + fields.get(key));
        }
        return String.join("\n", lines);
    }

    static String calculateHash(String botToken, String dataCheckString) {
        try {
            Mac secretMac = Mac.getInstance("HmacSHA256");
            secretMac.init(new SecretKeySpec(WEB_APP_DATA.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] secretKey = secretMac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

            Mac dataMac = Mac.getInstance("HmacSHA256");
            dataMac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] hash = dataMac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new TelegramInitDataException("Failed to calculate init data hash");
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public record ValidatedTelegramUser(
            long id,
            String firstName,
            String lastName,
            String username,
            long authDate
    ) {}

    public static class TelegramInitDataException extends RuntimeException {
        public TelegramInitDataException(String message) {
            super(message);
        }
    }
}
