package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.Plan;
import com.plstk.loyaltybot.entity.ShopMember.MemberRole;
import com.plstk.loyaltybot.entity.Subscription;
import com.plstk.loyaltybot.repository.PlanRepository;
import com.plstk.loyaltybot.service.AuthorizationService;
import com.plstk.loyaltybot.service.CloudPaymentsService;
import com.plstk.loyaltybot.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
    private final AuthorizationService authorizationService;
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

    /**
     * Stub activation grants a subscription without any real payment, so it must never be reachable
     * by an ordinary shop member the way plain {@code hasAccess} would allow - restricted to the
     * shop owner, same floor as {@code /graduate} for supplier-import automation (ADR-026).
     */
    @PostMapping("/activate-stub")
    public ResponseEntity<SubscriptionResponse> activateStub(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "BASIC_MONTHLY") String planCode,
            @AuthenticationPrincipal AdminUser user) {

        if (!authorizationService.hasRole(user, shopId, MemberRole.OWNER)) {
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

    /** Same OWNER floor as {@link #activateStub} - free trial time is a billing bypass too (ADR-026). */
    @PostMapping("/extend-trial")
    public ResponseEntity<SubscriptionResponse> extendTrial(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "7") int days,
            @AuthenticationPrincipal AdminUser user) {

        if (!authorizationService.hasRole(user, shopId, MemberRole.OWNER)) {
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
     *
     * <p>Stage 8 security hardening: once a real CloudPayments secret is configured
     * ({@link CloudPaymentsService#isLiveGatewayConfigured()}), this client-triggered call can no
     * longer activate a subscription on its own — real payments must be confirmed exclusively by
     * the HMAC-validated {@code /cloudpayments/pay} webhook below, which the CloudPayments backend
     * calls after actually verifying the charge. Without a configured secret (dev/stub mode, same
     * signal {@link CloudPaymentsService#validateHmac} uses), this keeps its previous behavior so
     * local development and demo shops are unaffected.
     */
    @PostMapping("/confirm-payment")
    public ResponseEntity<?> confirmPayment(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "BASIC_MONTHLY") String planCode,
            @AuthenticationPrincipal AdminUser user) {

        if (!hasAccessToShop(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        if (cloudPaymentsService.isLiveGatewayConfigured()) {
            log.warn("Rejected client-triggered confirm-payment for shopId={} - live gateway "
                    + "configured, must wait for the HMAC-validated webhook", shopId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                    "AWAITING_WEBHOOK_CONFIRMATION",
                    "Payment must be confirmed by the payment provider's webhook"));
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
            @RequestBody String rawBody,
            @RequestHeader(value = "Content-HMAC", required = false) String contentHmac,
            @RequestHeader(value = "X-Content-HMAC", required = false) String xContentHmac) {

        log.info("CloudPayments CHECK webhook received");
        if (!cloudPaymentsService.validateHmac(rawBody, firstNonBlank(contentHmac, xContentHmac))) {
            log.warn("CloudPayments CHECK webhook rejected: invalid HMAC signature");
            return ResponseEntity.ok(Map.of("code", 13));
        }
        int code = cloudPaymentsService.handleCheck(parseFormBody(rawBody));
        return ResponseEntity.ok(Map.of("code", code));
    }

    @PostMapping(value = "/cloudpayments/pay", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> cloudpaymentsPay(
            @RequestBody String rawBody,
            @RequestHeader(value = "Content-HMAC", required = false) String contentHmac,
            @RequestHeader(value = "X-Content-HMAC", required = false) String xContentHmac) {

        log.info("CloudPayments PAY webhook received");
        if (!cloudPaymentsService.validateHmac(rawBody, firstNonBlank(contentHmac, xContentHmac))) {
            log.warn("CloudPayments PAY webhook rejected: invalid HMAC signature - subscription NOT activated");
            return ResponseEntity.ok(Map.of("code", 0));
        }
        int code = cloudPaymentsService.handlePay(parseFormBody(rawBody));
        return ResponseEntity.ok(Map.of("code", code));
    }

    @PostMapping(value = "/cloudpayments/fail", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> cloudpaymentsFail(
            @RequestBody String rawBody,
            @RequestHeader(value = "Content-HMAC", required = false) String contentHmac,
            @RequestHeader(value = "X-Content-HMAC", required = false) String xContentHmac) {

        log.info("CloudPayments FAIL webhook received");
        if (!cloudPaymentsService.validateHmac(rawBody, firstNonBlank(contentHmac, xContentHmac))) {
            log.warn("CloudPayments FAIL webhook rejected: invalid HMAC signature");
            return ResponseEntity.ok(Map.of("code", 0));
        }
        int code = cloudPaymentsService.handleFail(parseFormBody(rawBody));
        return ResponseEntity.ok(Map.of("code", code));
    }

    @PostMapping(value = "/cloudpayments/recurrent", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> cloudpaymentsRecurrent(
            @RequestBody String rawBody,
            @RequestHeader(value = "Content-HMAC", required = false) String contentHmac,
            @RequestHeader(value = "X-Content-HMAC", required = false) String xContentHmac) {

        log.info("CloudPayments RECURRENT webhook received");
        if (!cloudPaymentsService.validateHmac(rawBody, firstNonBlank(contentHmac, xContentHmac))) {
            log.warn("CloudPayments RECURRENT webhook rejected: invalid HMAC signature - subscription NOT renewed");
            return ResponseEntity.ok(Map.of("code", 0));
        }
        int code = cloudPaymentsService.handleRecurrent(parseFormBody(rawBody));
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

    public record ErrorResponse(String code, String message) {}

    // ========== Helpers ==========

    private boolean hasAccessToShop(AdminUser user, String shopId) {
        return authorizationService.hasAccess(user, shopId);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /**
     * CloudPayments HMAC is computed over the exact raw {@code application/x-www-form-urlencoded}
     * request body, so the body must be read as a plain string (not through Spring's
     * {@code @RequestParam Map} binding, which would consume/reformat it) before being handed to
     * {@link CloudPaymentsService#validateHmac}. Once validated, this parses it into the same
     * {@code Map<String, Object>} shape the {@code handle*} methods already expect.
     */
    private static Map<String, Object> parseFormBody(String rawBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rawBody == null || rawBody.isBlank()) {
            return result;
        }
        for (String pair : rawBody.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String rawKey = idx >= 0 ? pair.substring(0, idx) : pair;
            String rawValue = idx >= 0 ? pair.substring(idx + 1) : "";
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
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
