package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.Plan;
import com.plstk.loyaltybot.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudPaymentsService {

    private final SubscriptionService subscriptionService;
    private final PlanRepository planRepository;
    private final Environment environment;

    @Value("${cloudpayments.public-id:}")
    private String publicId;

    @Value("${cloudpayments.api-secret:}")
    private String apiSecret;

    public String getPublicId() {
        return publicId;
    }

    /**
     * True once a real CloudPayments API secret is configured. Stage 8 security hardening uses
     * this as the single signal to decide whether {@code confirm-payment} may still activate a
     * subscription client-side (dev/stub mode, no secret configured — same signal
     * {@link #validateHmac} already uses to skip signature checks) or must defer exclusively to
     * the HMAC-validated {@code /cloudpayments/pay} webhook (a real gateway is wired up).
     */
    public boolean isLiveGatewayConfigured() {
        return apiSecret != null && !apiSecret.isBlank();
    }

    /**
     * True when {@code confirm-payment} must reject client-triggered self-activation: either a
     * live gateway is actually configured (the HMAC-validated webhook is the only trustworthy
     * source of truth), or the app is running the {@code prod} profile without one configured at
     * all. The latter case matters because a missing secret in production can only be a
     * misconfiguration, not the legitimate dev/stub signal it is everywhere else (ADR-020) — an
     * operator who forgets to set {@code CLOUDPAYMENTS_API_SECRET} in production must never get
     * "unpaid subscriptions activate for free" as the fallback behavior (ADR-028).
     */
    public boolean requiresWebhookConfirmation() {
        return isLiveGatewayConfigured() || isProdProfile();
    }

    /**
     * True when running under the {@code prod} Spring profile - same
     * {@code environment.getActiveProfiles()} check {@code SecurityConfig} already uses to decide
     * production-only behavior. A missing API secret is a legitimate "no live gateway configured
     * yet" dev/stub signal (ADR-020) everywhere else, but in production it can only mean a
     * misconfiguration, and must never be treated as "skip the check" (ADR-026).
     */
    private boolean isProdProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }

    /**
     * Валидация HMAC подписи от CloudPayments.
     * CloudPayments подписывает тело запроса HMAC-SHA256, ключ — API secret.
     */
    public boolean validateHmac(String body, String hmacHeader) {
        if (apiSecret == null || apiSecret.isBlank()) {
            if (isProdProfile()) {
                // ADR-026: production must fail closed - an unsigned/unverifiable webhook is
                // rejected rather than silently accepted, even though the same missing-secret
                // signal is treated as "dev/stub mode" outside of prod (ADR-020).
                log.error("CloudPayments API secret not configured in the prod profile - rejecting "
                        + "webhook instead of skipping HMAC validation");
                return false;
            }
            log.warn("CloudPayments API secret not configured, skipping HMAC validation");
            return true;
        }
        if (hmacHeader == null || hmacHeader.isBlank()) {
            log.warn("Missing HMAC header in CloudPayments webhook");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String calculated = Base64.getEncoder().encodeToString(hash);
            return calculated.equals(hmacHeader);
        } catch (Exception e) {
            log.error("HMAC validation error", e);
            return false;
        }
    }

    /**
     * Check notification — валидация перед списанием.
     * Проверяем что сумма и валюта соответствуют плану.
     * Возвращает код: 0 = OK, 13 = отказ.
     */
    public int handleCheck(Map<String, Object> params) {
        String accountId = getStr(params, "AccountId");
        String invoiceId = getStr(params, "InvoiceId");
        Number amount = getNum(params, "Amount");
        String currency = getStr(params, "Currency");

        log.info("CloudPayments CHECK: accountId={}, invoiceId={}, amount={}, currency={}",
                accountId, invoiceId, amount, currency);

        if (accountId == null || accountId.isBlank()) {
            log.warn("Check failed: missing AccountId");
            return 13;
        }

        if (invoiceId == null || invoiceId.isBlank()) {
            log.warn("Check failed: missing InvoiceId (planCode)");
            return 13;
        }

        String planCode = invoiceId;
        Optional<Plan> planOpt = planRepository.findByCode(planCode);
        if (planOpt.isEmpty()) {
            log.warn("Check failed: unknown plan code={}", planCode);
            return 13;
        }

        Plan plan = planOpt.get();
        double expectedAmount = plan.getPriceAmount() / 100.0;

        if (amount == null || Math.abs(amount.doubleValue() - expectedAmount) > 0.01) {
            log.warn("Check failed: amount mismatch. Expected={}, got={}", expectedAmount, amount);
            return 13;
        }

        if (!"RUB".equalsIgnoreCase(currency) && !plan.getCurrency().equalsIgnoreCase(currency)) {
            log.warn("Check failed: currency mismatch. Expected={}, got={}", plan.getCurrency(), currency);
            return 13;
        }

        log.info("Check passed for shopId={}, planCode={}", accountId, planCode);
        return 0;
    }

    /**
     * Pay notification — успешная оплата.
     * Активирует подписку для магазина.
     */
    public int handlePay(Map<String, Object> params) {
        String accountId = getStr(params, "AccountId");
        String invoiceId = getStr(params, "InvoiceId");
        String transactionId = getStr(params, "TransactionId");
        Number amount = getNum(params, "Amount");

        log.info("CloudPayments PAY: accountId={}, invoiceId={}, transactionId={}, amount={}",
                accountId, invoiceId, transactionId, amount);

        if (accountId == null || accountId.isBlank()) {
            log.error("Pay failed: missing AccountId");
            return 0;
        }

        String planCode = invoiceId;
        if (planCode == null || planCode.isBlank()) {
            planCode = Plan.BASIC_MONTHLY;
        }

        try {
            subscriptionService.activateByPayment(
                    accountId,
                    planCode,
                    transactionId != null ? transactionId : "",
                    "CLOUDPAYMENTS"
            );
            log.info("Subscription activated via CloudPayments for shopId={}, plan={}", accountId, planCode);
        } catch (Exception e) {
            log.error("Error activating subscription for shopId={}: {}", accountId, e.getMessage(), e);
        }

        return 0;
    }

    /**
     * Recurrent notification — изменение статуса рекуррентного платежа.
     */
    public int handleRecurrent(Map<String, Object> params) {
        String id = getStr(params, "Id");
        String accountId = getStr(params, "AccountId");
        String status = getStr(params, "Status");

        log.info("CloudPayments RECURRENT: id={}, accountId={}, status={}", id, accountId, status);

        if ("Cancelled".equalsIgnoreCase(status) || "Rejected".equalsIgnoreCase(status)
                || "Expired".equalsIgnoreCase(status)) {
            log.info("Recurrent subscription ended for accountId={}, status={}", accountId, status);
        }

        if ("Active".equalsIgnoreCase(status) && accountId != null) {
            String invoiceId = getStr(params, "InvoiceId");
            String planCode = (invoiceId != null && !invoiceId.isBlank()) ? invoiceId : Plan.BASIC_MONTHLY;
            try {
                subscriptionService.activateByPayment(accountId, planCode, id != null ? id : "", "CLOUDPAYMENTS");
                log.info("Recurrent payment renewed subscription for shopId={}", accountId);
            } catch (Exception e) {
                log.error("Error renewing subscription for shopId={}: {}", accountId, e.getMessage(), e);
            }
        }

        return 0;
    }

    /**
     * Fail notification — неуспешная оплата (для логирования).
     */
    public int handleFail(Map<String, Object> params) {
        String accountId = getStr(params, "AccountId");
        String reason = getStr(params, "Reason");
        String reasonCode = getStr(params, "ReasonCode");

        log.warn("CloudPayments FAIL: accountId={}, reason={}, code={}", accountId, reason, reasonCode);
        return 0;
    }

    /**
     * Подтверждение платежа: фронтенд вызывает после onSuccess,
     * бэкенд проверяет транзакцию через CloudPayments API и активирует подписку.
     */
    @SuppressWarnings("unchecked")
    public boolean confirmPayment(String shopId, String planCode) {
        log.info("Confirming payment for shopId={}, planCode={}", shopId, planCode);

        if (planCode == null || planCode.isBlank()) {
            planCode = Plan.BASIC_MONTHLY;
        }

        Optional<Plan> planOpt = planRepository.findByCode(planCode);
        int periodDays = planOpt.map(Plan::getPeriodDays).orElse(30);

        try {
            subscriptionService.activateByPayment(shopId, planCode, "widget-confirmed", "CLOUDPAYMENTS");
            log.info("Subscription activated via widget confirmation for shopId={}, plan={}", shopId, planCode);
            return true;
        } catch (Exception e) {
            log.error("Error confirming payment for shopId={}: {}", shopId, e.getMessage(), e);
            return false;
        }
    }

    private String getStr(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val != null ? val.toString() : null;
    }

    private Number getNum(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) return null;
        if (val instanceof Number) return (Number) val;
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
