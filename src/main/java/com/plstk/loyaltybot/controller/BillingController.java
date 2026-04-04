package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.Plan;
import com.plstk.loyaltybot.entity.Subscription;
import com.plstk.loyaltybot.repository.PlanRepository;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import com.plstk.loyaltybot.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST API для управления подписками и биллингом.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {
    
    private final SubscriptionService subscriptionService;
    private final ShopRepository shopRepository;
    private final ShopMemberRepository shopMemberRepository;
    private final PlanRepository planRepository;
    
    /**
     * Получает информацию о подписке магазина.
     * 
     * GET /api/billing/subscription?shopId=...
     */
    @GetMapping("/subscription")
    public ResponseEntity<SubscriptionResponse> getSubscription(
            @RequestParam String shopId,
            @AuthenticationPrincipal AdminUser user) {
        
        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        
        SubscriptionService.SubscriptionInfo info = subscriptionService.getSubscriptionInfo(shopId);
        
        return ResponseEntity.ok(toResponse(info));
    }
    
    /**
     * Активирует подписку (заглушка — без реальной оплаты).
     * 
     * POST /api/billing/activate-stub?shopId=...&planCode=...
     */
    @PostMapping("/activate-stub")
    public ResponseEntity<SubscriptionResponse> activateStub(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "BASIC_MONTHLY") String planCode,
            @AuthenticationPrincipal AdminUser user) {
        
        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        
        try {
            Subscription subscription = subscriptionService.activateStub(shopId, planCode);
            SubscriptionService.SubscriptionInfo info = subscriptionService.getSubscriptionInfo(shopId);
            
            return ResponseEntity.ok(toResponse(info));
        } catch (Exception e) {
            log.error("Error activating stub subscription for shopId={}", shopId, e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Продлевает trial период (для саппорта/тестирования).
     * 
     * POST /api/billing/extend-trial?shopId=...&days=...
     */
    @PostMapping("/extend-trial")
    public ResponseEntity<SubscriptionResponse> extendTrial(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "7") int days,
            @AuthenticationPrincipal AdminUser user) {
        
        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        
        try {
            Subscription subscription = subscriptionService.extendTrial(shopId, days);
            SubscriptionService.SubscriptionInfo info = subscriptionService.getSubscriptionInfo(shopId);
            
            return ResponseEntity.ok(toResponse(info));
        } catch (Exception e) {
            log.error("Error extending trial for shopId={}", shopId, e);
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Получает список доступных планов.
     * 
     * GET /api/billing/plans
     */
    @GetMapping("/plans")
    public ResponseEntity<List<PlanDto>> getPlans() {
        List<Plan> plans = planRepository.findByIsActiveTrue();
        
        List<PlanDto> dtos = plans.stream()
            .map(p -> new PlanDto(
                p.getCode(),
                p.getName(),
                p.getDescription(),
                p.getPriceAmount(),
                p.getCurrency(),
                p.getPeriodDays(),
                p.getIsStub()
            ))
            .toList();
        
        return ResponseEntity.ok(dtos);
    }
    
    // ========== DTOs ==========
    
    public record SubscriptionResponse(
        String shopId,
        String status,
        String planCode,
        long daysLeft,
        LocalDateTime trialStartAt,
        LocalDateTime trialEndAt,
        LocalDateTime currentPeriodStartAt,
        LocalDateTime currentPeriodEndAt,
        String billingEnforcementMode
    ) {}
    
    public record PlanDto(
        String code,
        String name,
        String description,
        int priceAmount,
        String currency,
        int periodDays,
        boolean isStub
    ) {}
    
    // ========== Helpers ==========
    
    private boolean hasAccessToShop(AdminUser user, String shopId) {
        // Владелец
        if (shopRepository.findByShopId(shopId)
                .map(s -> s.getOwnerId().equals(user.getId()))
                .orElse(false)) {
            return true;
        }
        
        // Член команды
        return shopMemberRepository.existsByUserIdAndShopId(user.getId(), shopId);
    }
    
    private SubscriptionResponse toResponse(SubscriptionService.SubscriptionInfo info) {
        Subscription sub = info.subscription();
        
        if (sub == null) {
            return new SubscriptionResponse(
                null,
                info.status(),
                null,
                0,
                null,
                null,
                null,
                null,
                info.billingEnforcementMode()
            );
        }
        
        return new SubscriptionResponse(
            sub.getShopId(),
            sub.getStatus().name(),
            sub.getPlanCode(),
            info.daysLeft(),
            sub.getTrialStartAt(),
            sub.getTrialEndAt(),
            sub.getCurrentPeriodStartAt(),
            sub.getCurrentPeriodEndAt(),
            info.billingEnforcementMode()
        );
    }
}
