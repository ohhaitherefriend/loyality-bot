package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.BotInstance;
import com.plstk.loyaltybot.entity.LoyaltyMode;
import com.plstk.loyaltybot.entity.MessengerPlatform;
import com.plstk.loyaltybot.entity.OnboardingState;
import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.service.OnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST API для онбординга.
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
@Slf4j
public class OnboardingController {
    
    private final OnboardingService onboardingService;
    
    /**
     * Начинает или возобновляет онбординг.
     * 
     * POST /api/onboarding/start
     */
    @PostMapping("/start")
    public ResponseEntity<OnboardingResponse> startOnboarding(@AuthenticationPrincipal AdminUser user) {
        OnboardingState state = onboardingService.startOnboarding(user.getId());
        return ResponseEntity.ok(toResponse(state, null, null));
    }
    
    /**
     * Получает текущее состояние онбординга.
     * 
     * GET /api/onboarding/state
     */
    @GetMapping("/state")
    public ResponseEntity<OnboardingResponse> getState(@AuthenticationPrincipal AdminUser user) {
        return onboardingService.getOnboardingState(user.getId())
            .map(state -> ResponseEntity.ok(toResponse(state, null, null)))
            .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Создаёт магазин.
     * 
     * POST /api/onboarding/create-shop
     */
    @PostMapping("/create-shop")
    public ResponseEntity<OnboardingResponse> createShop(
            @Valid @RequestBody CreateShopRequest request,
            @AuthenticationPrincipal AdminUser user) {
        
        try {
            OnboardingService.CreateShopResult result = onboardingService.createShop(
                user.getId(),
                request.name(),
                request.timezone(),
                request.templateType(),
                request.loyaltyMode(),
                request.stampsRequiredForReward(),
                request.rewardTitle(),
                request.bonusPercent()
            );
            
            return ResponseEntity.ok(toResponse(result.state(), result.shop(), null));
        } catch (Exception e) {
            log.error("Error creating shop in onboarding", e);
            return ResponseEntity.badRequest().body(OnboardingResponse.error(e.getMessage()));
        }
    }
    
    /**
     * Подключает бота.
     * 
     * POST /api/onboarding/connect-bot
     */
    @PostMapping("/connect-bot")
    public ResponseEntity<OnboardingResponse> connectBot(
            @Valid @RequestBody ConnectBotRequest request,
            @AuthenticationPrincipal AdminUser user) {
        
        try {
            MessengerPlatform platform = request.platform() != null
                    ? MessengerPlatform.valueOf(request.platform().toUpperCase())
                    : MessengerPlatform.TELEGRAM;
            
            OnboardingService.ConnectBotResult result = onboardingService.connectBot(
                user.getId(),
                request.shopId(),
                request.botToken(),
                platform
            );
            
            if (!result.success()) {
                return ResponseEntity.badRequest().body(OnboardingResponse.error(result.error()));
            }
            
            OnboardingResponse response = toResponse(result.state(), null, result.botInstance());
            // Добавляем deep links
            return ResponseEntity.ok(new OnboardingResponse(
                response.success(),
                response.step(),
                response.shopId(),
                response.shopName(),
                response.botUsername(),
                result.buyDeepLink(),
                result.adminDeepLink(),
                response.botInstanceId(),
                response.completed(),
                response.error()
            ));
        } catch (Exception e) {
            log.error("Error connecting bot in onboarding", e);
            return ResponseEntity.badRequest().body(OnboardingResponse.error(e.getMessage()));
        }
    }
    
    /**
     * Применяет шаблон настроек.
     * 
     * POST /api/onboarding/apply-template
     */
    @PostMapping("/apply-template")
    public ResponseEntity<OnboardingResponse> applyTemplate(
            @Valid @RequestBody ApplyTemplateRequest request,
            @AuthenticationPrincipal AdminUser user) {
        
        try {
            OnboardingState state = onboardingService.applyTemplate(
                user.getId(),
                request.shopId(),
                request.templateType()
            );
            
            return ResponseEntity.ok(toResponse(state, null, null));
        } catch (Exception e) {
            log.error("Error applying template in onboarding", e);
            return ResponseEntity.badRequest().body(OnboardingResponse.error(e.getMessage()));
        }
    }
    
    /**
     * Завершает онбординг.
     * 
     * POST /api/onboarding/complete
     */
    @PostMapping("/complete")
    public ResponseEntity<OnboardingResponse> complete(
            @Valid @RequestBody CompleteRequest request,
            @AuthenticationPrincipal AdminUser user) {
        
        try {
            OnboardingState state = onboardingService.completeOnboarding(
                user.getId(),
                request.shopId()
            );
            
            return ResponseEntity.ok(toResponse(state, null, null));
        } catch (Exception e) {
            log.error("Error completing onboarding", e);
            return ResponseEntity.badRequest().body(OnboardingResponse.error(e.getMessage()));
        }
    }
    
    // ========== DTOs ==========
    
    public record CreateShopRequest(
        String name,
        String timezone,
        String templateType,  // COFFEE, RETAIL, SERVICE (legacy)
        LoyaltyMode loyaltyMode,  // STAMPS, BONUS
        Integer stampsRequiredForReward,
        String rewardTitle,
        Integer bonusPercent
    ) {}
    
    public record ConnectBotRequest(
        @NotBlank(message = "shopId обязателен")
        String shopId,
        
        @NotBlank(message = "Токен бота обязателен")
        String botToken,
        
        String platform
    ) {}
    
    public record ApplyTemplateRequest(
        @NotBlank(message = "shopId обязателен")
        String shopId,
        
        String templateType
    ) {}
    
    public record CompleteRequest(
        @NotBlank(message = "shopId обязателен")
        String shopId
    ) {}
    
    public record OnboardingResponse(
        boolean success,
        String step,
        String shopId,
        String shopName,
        String botUsername,
        String buyDeepLink,
        String adminDeepLink,
        Long botInstanceId,
        boolean completed,
        String error
    ) {
        public static OnboardingResponse error(String error) {
            return new OnboardingResponse(false, null, null, null, null, null, null, null, false, error);
        }
    }
    
    // ========== Helpers ==========
    
    private OnboardingResponse toResponse(OnboardingState state, Shop shop, BotInstance bot) {
        return new OnboardingResponse(
            true,
            state.getStep().name(),
            state.getShopId(),
            shop != null ? shop.getName() : null,
            bot != null ? bot.getBotUsername() : null,
            bot != null ? bot.generateBuyDeepLink(null) : null,
            bot != null ? bot.generateAdminDeepLink() : null,
            bot != null ? bot.getId() : null,
            state.getCompleted(),
            null
        );
    }
}
