package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.OnboardingState;
import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API для аутентификации.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {
    
    private final AuthService authService;
    
    /**
     * Регистрация нового пользователя.
     * 
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.RegisterResult result = authService.register(
            request.email(),
            request.password(),
            request.name()
        );
        
        if (!result.success()) {
            return ResponseEntity.badRequest()
                .body(AuthResponse.error(result.error()));
        }
        
        return ResponseEntity.ok(AuthResponse.success(
            result.token(),
            toUserDto(result.user()),
            List.of(),
            true
        ));
    }
    
    /**
     * Логин пользователя.
     * 
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(
            request.email(),
            request.password()
        );
        
        if (!result.success()) {
            return ResponseEntity.badRequest()
                .body(AuthResponse.error(result.error()));
        }
        
        // Получаем информацию о магазинах
        AuthService.UserInfo userInfo = authService.getUserInfo(result.user());
        
        return ResponseEntity.ok(AuthResponse.success(
            result.token(),
            toUserDto(result.user()),
            userInfo.shops().stream().map(this::toShopDto).toList(),
            userInfo.hasActiveOnboarding()
        ));
    }
    
    /**
     * Получение информации о текущем пользователе.
     * 
     * GET /api/auth/me
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AdminUser user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        
        AuthService.UserInfo userInfo = authService.getUserInfo(user);
        
        return ResponseEntity.ok(new MeResponse(
            toUserDto(user),
            userInfo.shops().stream().map(this::toShopDto).toList(),
            userInfo.hasActiveOnboarding(),
            userInfo.onboardingState() != null ? toOnboardingDto(userInfo.onboardingState()) : null
        ));
    }
    
    // ========== DTOs ==========
    
    public record RegisterRequest(
        @NotBlank(message = "Email обязателен")
        @Email(message = "Некорректный email")
        String email,
        
        @NotBlank(message = "Пароль обязателен")
        @Size(min = 6, message = "Пароль должен быть не менее 6 символов")
        String password,
        
        String name
    ) {}
    
    public record LoginRequest(
        @NotBlank(message = "Email обязателен")
        @Email(message = "Некорректный email")
        String email,
        
        @NotBlank(message = "Пароль обязателен")
        String password
    ) {}
    
    public record AuthResponse(
        boolean success,
        String token,
        UserDto user,
        List<ShopDto> shops,
        boolean needsOnboarding,
        String error
    ) {
        public static AuthResponse success(String token, UserDto user, List<ShopDto> shops, boolean needsOnboarding) {
            return new AuthResponse(true, token, user, shops, needsOnboarding, null);
        }
        public static AuthResponse error(String error) {
            return new AuthResponse(false, null, null, null, false, error);
        }
    }
    
    public record MeResponse(
        UserDto user,
        List<ShopDto> shops,
        boolean needsOnboarding,
        OnboardingDto onboarding
    ) {}
    
    public record UserDto(
        Long id,
        String email,
        String name
    ) {}
    
    public record ShopDto(
        Long id,
        String shopId,
        String name,
        String timezone
    ) {}
    
    public record OnboardingDto(
        Long id,
        String step,
        String shopId,
        boolean completed
    ) {}
    
    // ========== Helpers ==========
    
    private UserDto toUserDto(AdminUser user) {
        return new UserDto(user.getId(), user.getEmail(), user.getName());
    }
    
    private ShopDto toShopDto(Shop shop) {
        return new ShopDto(shop.getId(), shop.getShopId(), shop.getName(), shop.getTimezone());
    }
    
    private OnboardingDto toOnboardingDto(OnboardingState state) {
        return new OnboardingDto(
            state.getId(),
            state.getStep().name(),
            state.getShopId(),
            state.getCompleted()
        );
    }
}
