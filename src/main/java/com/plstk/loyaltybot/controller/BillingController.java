package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.Plan;
import com.plstk.loyaltybot.entity.Subscription;
import com.plstk.loyaltybot.repository.PlanRepository;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import com.plstk.loyaltybot.service.CloudPaymentsService;
import com.plstk.loyaltybot.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST API для управления подписками и биллингом.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final SubscriptionService subscriptionService;
    private final CloudPaymentsService cloudPaymentsService;
    private final ShopRepository shopRepository;
    private final ShopMemberRepository shopMemberRepository;
    private final PlanRepository planRepository;

    // ========== Authenticated endpoints ==========

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

    @PostMapping("/activate-stub")
    public ResponseEntity<SubscriptionResponse> activateStub(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "BASIC_MONTHLY") String planCode,
            @AuthenticationPrincipal AdminUser user) {

        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        try {
            subscriptionService.activateStub(shopId, planCode);
            SubscriptionService.SubscriptionInfo info = subscriptionService.getSubscriptionInfo(shopId);
            return ResponseEntity.ok(toResponse(info));
        } catch (Exception e) {
            log.error("Error activating stub subscription for shopId={}", shopId, e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/extend-trial")
    public ResponseEntity<SubscriptionResponse> extendTrial(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "7") int days,
            @AuthenticationPrincipal AdminUser user) {

        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        try {
            subscriptionService.extendTrial(shopId, days);
            SubscriptionService.SubscriptionInfo info = subscriptionService.getSubscriptionInfo(shopId);
            return ResponseEntity.ok(toResponse(info));
        } catch (Exception e) {
            log.error("Error extending trial for shopId={}", shopId, e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/plans")
    public ResponseEntity<List<PlanDto>> getPlans() {
        List<Plan> plans = planRepository.findByIsActiveTrue();

        List<PlanDto> dtos = plans.stream()
                .map(p -> new PlanDto(
                        p.getCode(), p.getName(), p.getDescription(),
                        p.getPriceAmount(), p.getCurrency(), p.getPeriodDays(), p.getIsStub()))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    /**
     * Конфигурация для CloudPayments виджета на фронтенде.
     */
    @GetMapping("/payment-config")
    public ResponseEntity<PaymentConfigResponse> getPaymentConfig(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "BASIC_MONTHLY") String planCode,
            @AuthenticationPrincipal AdminUser user) {

        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        Plan plan = planRepository.findByCode(planCode).orElse(null);
        if (plan == null) {
            return ResponseEntity.badRequest().build();
        }

        double amount = plan.getPriceAmount() / 100.0;
        String interval = plan.getPeriodDays() >= 365 ? "Month" : "Month";
        int period = plan.getPeriodDays() >= 365 ? 12 : 1;

        return ResponseEntity.ok(new PaymentConfigResponse(
                cloudPaymentsService.getPublicId(),
                amount,
                plan.getCurrency(),
                planCode,
                "Подписка Zabotik — " + plan.getName(),
                shopId,
                interval,
                period
        ));
    }

    /**
     * Подтверждение платежа после успешной оплаты через виджет.
     * Фронтенд вызывает этот endpoint в onSuccess, бэкенд активирует подписку.
     */
    @PostMapping("/confirm-payment")
    public ResponseEntity<SubscriptionResponse> confirmPayment(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "BASIC_MONTHLY") String planCode,
            @AuthenticationPrincipal AdminUser user) {

        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        boolean success = cloudPaymentsService.confirmPayment(shopId, planCode);
        if (!success) {
            return ResponseEntity.badRequest().build();
        }

        SubscriptionService.SubscriptionInfo info = subscriptionService.getSubscriptionInfo(shopId);
        return ResponseEntity.ok(toResponse(info));
    }

    // ========== CloudPayments webhook endpoints (permitAll, HMAC-validated) ==========

    @PostMapping(value = "/cloudpayments/check", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> cloudpaymentsCheck(
            @RequestParam Map<String, Object> params,
            @RequestHeader(value = "Content-HMAC", required = false) String contentHmac,
            @RequestHeader(value = "X-Content-HMAC", required = false) String xContentHmac) {

        log.info("CloudPayments CHECK webhook received");
        int code = cloudPaymentsService.handleCheck(params);
        return ResponseEntity.ok(Map.of("code", code));
    }

    @PostMapping(value = "/cloudpayments/pay", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> cloudpaymentsPay(
            @RequestParam Map<String, Object> params,
            @RequestHeader(value = "Content-HMAC", required = false) String contentHmac,
            @RequestHeader(value = "X-Content-HMAC", required = false) String xContentHmac) {

        log.info("CloudPayments PAY webhook received");
        int code = cloudPaymentsService.handlePay(params);
        return ResponseEntity.ok(Map.of("code", code));
    }

    @PostMapping(value = "/cloudpayments/fail", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> cloudpaymentsFail(
            @RequestParam Map<String, Object> params,
            @RequestHeader(value = "Content-HMAC", required = false) String contentHmac,
            @RequestHeader(value = "X-Content-HMAC", required = false) String xContentHmac) {

        log.info("CloudPayments FAIL webhook received");
        int code = cloudPaymentsService.handleFail(params);
        return ResponseEntity.ok(Map.of("code", code));
    }

    @PostMapping(value = "/cloudpayments/recurrent", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> cloudpaymentsRecurrent(
            @RequestParam Map<String, Object> params,
            @RequestHeader(value = "Content-HMAC", required = false) String contentHmac,
            @RequestHeader(value = "X-Content-HMAC", required = false) String xContentHmac) {

        log.info("CloudPayments RECURRENT webhook received");
        int code = cloudPaymentsService.handleRecurrent(params);
        return ResponseEntity.ok(Map.of("code", code));
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
            boolean freeForever,
            boolean accessGranted
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

    public record PaymentConfigResponse(
            String publicId,
            double amount,
            String currency,
            String invoiceId,
            String description,
            String accountId,
            String recurrentInterval,
            int recurrentPeriod
    ) {}

    // ========== Helpers ==========

    private boolean hasAccessToShop(AdminUser user, String shopId) {
        if (shopRepository.findByShopId(shopId)
                .map(s -> s.getOwnerId().equals(user.getId()))
                .orElse(false)) {
            return true;
        }
        return shopMemberRepository.existsByUserIdAndShopId(user.getId(), shopId);
    }

    private SubscriptionResponse toResponse(SubscriptionService.SubscriptionInfo info) {
        Subscription sub = info.subscription();

        if (sub == null) {
            return new SubscriptionResponse(
                    null, info.status(), null, 0,
                    null, null, null, null,
                    false, false
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
                info.freeForever(),
                info.accessGranted()
        );
    }
}
