package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.*;
import com.plstk.loyaltybot.repository.*;
import com.plstk.loyaltybot.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Сервис для аутентификации и регистрации пользователей Web UI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    
    private final AdminUserRepository adminUserRepository;
    private final ShopRepository shopRepository;
    private final ShopMemberRepository shopMemberRepository;
    private final OnboardingStateRepository onboardingStateRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    
    @Value("${billing.trial-days:7}")
    private int trialDays;
    
    /**
     * Результат регистрации
     */
    public record RegisterResult(
        boolean success,
        AdminUser user,
        String token,
        String error
    ) {
        public static RegisterResult success(AdminUser user, String token) {
            return new RegisterResult(true, user, token, null);
        }
        public static RegisterResult error(String error) {
            return new RegisterResult(false, null, null, error);
        }
    }
    
    /**
     * Результат логина
     */
    public record LoginResult(
        boolean success,
        AdminUser user,
        String token,
        String error
    ) {
        public static LoginResult success(AdminUser user, String token) {
            return new LoginResult(true, user, token, null);
        }
        public static LoginResult error(String error) {
            return new LoginResult(false, null, null, error);
        }
    }
    
    /**
     * Регистрация нового пользователя
     */
    @Transactional
    public RegisterResult register(String email, String password, String name) {
        log.debug("Registration attempt for email: {}", email);
        
        // Валидация
        if (email == null || email.isBlank()) {
            return RegisterResult.error("Email обязателен");
        }
        if (password == null || password.length() < 6) {
            return RegisterResult.error("Пароль должен быть не менее 6 символов");
        }
        
        String normalizedEmail = email.toLowerCase().trim();
        
        // Проверка существующего пользователя
        if (adminUserRepository.existsByEmail(normalizedEmail)) {
            return RegisterResult.error("Пользователь с таким email уже существует");
        }
        
        // Создаём пользователя
        AdminUser user = AdminUser.builder()
            .email(normalizedEmail)
            .passwordHash(passwordEncoder.encode(password))
            .name(name)
            .isActive(true)
            .emailVerified(false)
            .build();
        
        user = adminUserRepository.save(user);
        log.info("User registered: id={}", user.getId());
        
        // Создаём OnboardingState
        OnboardingState onboarding = OnboardingState.createForUser(user.getId());
        onboardingStateRepository.save(onboarding);
        
        // Генерируем токен
        String token = jwtService.generateToken(user);
        
        return RegisterResult.success(user, token);
    }
    
    /**
     * Логин пользователя
     */
    @Transactional
    public LoginResult login(String email, String password) {
        log.debug("Login attempt for email: {}", email);
        
        if (email == null || password == null) {
            return LoginResult.error("Email и пароль обязательны");
        }
        
        String normalizedEmail = email.toLowerCase().trim();
        
        Optional<AdminUser> userOpt = adminUserRepository.findByEmail(normalizedEmail);
        
        if (userOpt.isEmpty()) {
            log.debug("Login failed: user not found");
            return LoginResult.error("Неверный email или пароль");
        }
        
        AdminUser user = userOpt.get();
        
        if (!user.getIsActive()) {
            return LoginResult.error("Аккаунт деактивирован");
        }
        
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.debug("Login failed: invalid password");
            return LoginResult.error("Неверный email или пароль");
        }
        
        // Обновляем время последнего входа
        user.setLastLoginAt(LocalDateTime.now());
        adminUserRepository.save(user);
        
        // Генерируем токен
        String token = jwtService.generateToken(user);
        
        log.info("User logged in: id={}", user.getId());
        return LoginResult.success(user, token);
    }
    
    /**
     * Получает информацию о текущем пользователе и его магазинах
     */
    public UserInfo getUserInfo(AdminUser user) {
        List<Shop> shops = shopRepository.findAllByMemberUserId(user.getId());
        
        // Также добавляем магазины, где пользователь владелец
        List<Shop> ownedShops = shopRepository.findByOwnerId(user.getId());
        for (Shop shop : ownedShops) {
            if (shops.stream().noneMatch(s -> s.getShopId().equals(shop.getShopId()))) {
                shops.add(shop);
            }
        }
        
        // Проверяем, есть ли незавершённый онбординг
        Optional<OnboardingState> onboardingOpt = onboardingStateRepository.findActiveByUserId(user.getId());
        boolean hasActiveOnboarding = onboardingOpt.isPresent() && !onboardingOpt.get().getCompleted();
        
        return new UserInfo(
            user.getId(),
            user.getEmail(),
            user.getName(),
            shops,
            hasActiveOnboarding,
            onboardingOpt.orElse(null)
        );
    }
    
    /**
     * Информация о пользователе для /api/auth/me
     */
    public record UserInfo(
        Long id,
        String email,
        String name,
        List<Shop> shops,
        boolean hasActiveOnboarding,
        OnboardingState onboardingState
    ) {}
}
