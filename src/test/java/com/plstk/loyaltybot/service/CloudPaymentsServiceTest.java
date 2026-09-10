package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.repository.PlanRepository;
import com.plstk.loyaltybot.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8 security hardening: {@link CloudPaymentsService#validateHmac} is the gate that decides
 * whether a CloudPayments webhook call is trusted, and {@link
 * CloudPaymentsService#isLiveGatewayConfigured()} is the single signal {@code BillingController}
 * uses to decide whether {@code confirm-payment} may still activate a subscription client-side.
 */
@ExtendWith(MockitoExtension.class)
class CloudPaymentsServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PlanRepository planRepository;

    private static final String SECRET = "super-secret-cloudpayments-key";

    private CloudPaymentsService serviceWithSecret(String secret) {
        return serviceWithSecret(secret, new MockEnvironment());
    }

    private CloudPaymentsService serviceWithSecret(String secret, MockEnvironment environment) {
        SubscriptionService subscriptionService = new SubscriptionService(subscriptionRepository, planRepository);
        CloudPaymentsService service = new CloudPaymentsService(subscriptionService, planRepository, environment);
        ReflectionTestUtils.setField(service, "apiSecret", secret);
        return service;
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validateHmac_noSecretConfigured_skipsValidation() {
        CloudPaymentsService service = serviceWithSecret("");
        assertTrue(service.validateHmac("any body", null));
        assertTrue(service.validateHmac("any body", "irrelevant"));
    }

    @Test
    void validateHmac_correctSignature_returnsTrue() throws Exception {
        CloudPaymentsService service = serviceWithSecret(SECRET);
        String body = "AccountId=shop-1&Amount=990";
        assertTrue(service.validateHmac(body, hmac(SECRET, body)));
    }

    @Test
    void validateHmac_tamperedBody_returnsFalse() throws Exception {
        CloudPaymentsService service = serviceWithSecret(SECRET);
        String body = "AccountId=shop-1&Amount=990";
        String signatureForOriginal = hmac(SECRET, body);
        assertFalse(service.validateHmac(body + "&Amount=1", signatureForOriginal));
    }

    @Test
    void validateHmac_missingHeader_returnsFalseWhenSecretConfigured() {
        CloudPaymentsService service = serviceWithSecret(SECRET);
        assertFalse(service.validateHmac("AccountId=shop-1", null));
        assertFalse(service.validateHmac("AccountId=shop-1", ""));
    }

    @Test
    void isLiveGatewayConfigured_reflectsWhetherSecretIsSet() {
        assertFalse(serviceWithSecret("").isLiveGatewayConfigured());
        assertFalse(serviceWithSecret(null).isLiveGatewayConfigured());
        assertTrue(serviceWithSecret(SECRET).isLiveGatewayConfigured());
    }

    /**
     * Six-bug hardening pass (ADR-026): a missing secret is a legitimate dev/stub signal outside of
     * prod, but in the {@code prod} profile it can only be a misconfiguration - the webhook must be
     * rejected (fail closed), never silently accepted.
     */
    @Test
    void validateHmac_noSecretConfiguredInProdProfile_rejectsInsteadOfSkipping() {
        MockEnvironment prod = new MockEnvironment();
        prod.addActiveProfile("prod");
        CloudPaymentsService service = serviceWithSecret("", prod);

        assertFalse(service.validateHmac("any body", null));
        assertFalse(service.validateHmac("any body", "irrelevant"));
    }

    @Test
    void validateHmac_noSecretConfiguredOutsideProdProfile_stillSkipsValidation() {
        MockEnvironment dev = new MockEnvironment();
        dev.addActiveProfile("dev");
        CloudPaymentsService service = serviceWithSecret("", dev);

        assertTrue(service.validateHmac("any body", null));
    }
}
