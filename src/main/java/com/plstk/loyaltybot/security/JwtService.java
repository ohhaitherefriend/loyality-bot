package com.plstk.loyaltybot.security;

import com.plstk.loyaltybot.entity.AdminUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Сервис для работы с JWT токенами.
 */
@Service
@Slf4j
public class JwtService {
    
    private static final int MIN_SECRET_LENGTH = 32;
    
    @Value("${security.jwt.secret:}")
    private String jwtSecret;
    
    @Value("${security.jwt.expiration-hours:24}")
    private int expirationHours;
    
    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "JWT secret is not configured. Set security.jwt.secret or JWT_SECRET environment variable.");
        }
        if (jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                "JWT secret must be at least " + MIN_SECRET_LENGTH + " characters for HS256.");
        }
    }
    
    /**
     * Генерирует JWT токен для пользователя
     */
    public String generateToken(AdminUser user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + (long) expirationHours * 60 * 60 * 1000);
        
        return Jwts.builder()
            .subject(user.getId().toString())
            .claim("email", user.getEmail())
            .claim("name", user.getName())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(getSigningKey())
            .compact();
    }
    
    /**
     * Извлекает user ID из токена
     */
    public Long extractUserId(String token) {
        Claims claims = extractAllClaims(token);
        return Long.parseLong(claims.getSubject());
    }
    
    /**
     * Извлекает email из токена
     */
    public String extractEmail(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("email", String.class);
    }
    
    /**
     * Проверяет валидность токена
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return !isTokenExpired(claims);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }
    
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
    
    private boolean isTokenExpired(Claims claims) {
        return claims.getExpiration().before(new Date());
    }
    
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
