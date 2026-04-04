package com.plstk.loyaltybot.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Сервис для шифрования/расшифровки bot tokens.
 * Использует AES-256-GCM для надёжного шифрования.
 */
@Service
@Slf4j
public class TokenEncryptionService {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    
    @Value("${security.encryption.key:}")
    private String encryptionKeyBase64;
    
    @Value("${security.encryption.enabled:true}")
    private boolean encryptionEnabled;
    
    private SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();
    
    @PostConstruct
    public void init() {
        if (!encryptionEnabled) {
            log.warn("Token encryption is DISABLED! Bot tokens will be stored in plain text.");
            return;
        }
        
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isEmpty()) {
            log.warn("Encryption key not configured. Generating temporary key (will change on restart!)");
            byte[] keyBytes = new byte[32]; // 256 bits
            secureRandom.nextBytes(keyBytes);
            secretKey = new SecretKeySpec(keyBytes, "AES");
            log.warn("To persist encryption, set security.encryption.key in application.yml");
        } else {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
                if (keyBytes.length != 32) {
                    throw new IllegalArgumentException("Encryption key must be 256 bits (32 bytes)");
                }
                secretKey = new SecretKeySpec(keyBytes, "AES");
                log.info("Token encryption initialized with configured key");
            } catch (IllegalArgumentException e) {
                log.error("Invalid encryption key configuration", e);
                throw new RuntimeException("Failed to initialize encryption", e);
            }
        }
    }
    
    /**
     * Шифрует токен бота
     * 
     * @param plainToken исходный токен
     * @return зашифрованный токен в Base64
     */
    public String encrypt(String plainToken) {
        if (!encryptionEnabled) {
            return plainToken;
        }
        
        if (plainToken == null || plainToken.isEmpty()) {
            throw new IllegalArgumentException("Token cannot be null or empty");
        }
        
        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
            
            byte[] encryptedBytes = cipher.doFinal(plainToken.getBytes());
            
            // Prepend IV to encrypted data
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encryptedBytes.length);
            byteBuffer.put(iv);
            byteBuffer.put(encryptedBytes);
            
            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Failed to encrypt token", e);
            throw new EncryptionException("Failed to encrypt token", e);
        }
    }
    
    /**
     * Расшифровывает токен бота
     * 
     * @param encryptedToken зашифрованный токен в Base64
     * @return исходный токен
     */
    public String decrypt(String encryptedToken) {
        if (!encryptionEnabled) {
            return encryptedToken;
        }
        
        if (encryptedToken == null || encryptedToken.isEmpty()) {
            throw new IllegalArgumentException("Encrypted token cannot be null or empty");
        }
        
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedToken);
            
            // Extract IV
            ByteBuffer byteBuffer = ByteBuffer.wrap(decodedBytes);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            
            // Extract encrypted data
            byte[] encryptedBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(encryptedBytes);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
            
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes);
        } catch (Exception e) {
            log.error("Failed to decrypt token", e);
            throw new EncryptionException("Failed to decrypt token", e);
        }
    }
    
    /**
     * Генерирует новый ключ шифрования (для конфигурации)
     * 
     * @return Base64-encoded key
     */
    public static String generateKey() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }
    
    /**
     * Проверяет, включено ли шифрование
     */
    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }
    
    /**
     * Проверяет, можно ли расшифровать токен (валидность)
     */
    public boolean canDecrypt(String encryptedToken) {
        if (!encryptionEnabled) {
            return true;
        }
        
        try {
            decrypt(encryptedToken);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

