package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.Shop;
import com.plstk.loyaltybot.repository.PlanRepository;
import com.plstk.loyaltybot.repository.ShopMemberRepository;
import com.plstk.loyaltybot.repository.ShopRepository;
import com.plstk.loyaltybot.repository.SubscriptionRepository;
import com.plstk.loyaltybot.service.AuthorizationService;
import com.plstk.loyaltybot.service.CloudPaymentsService;
import com.plstk.loyaltybot.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 8 security hardening: CloudPayments webhooks must validate the {@code Content-HMAC}
 * signature over the raw request body before acting on it, and {@code confirm-payment} must stop
 * activating subscriptions client-side once a real gateway secret is configured (real payments
 * must go exclusively through the HMAC-validated webhook). Only repository interfaces are mocked;
 * {@link SubscriptionService}/{@link CloudPaymentsService}/{@link AuthorizationService} are real
 * instances backed by those mocks, matching the pattern used elsewhere in this test suite.
 */
@ExtendWith(MockitoExtension.class)
class BillingControllerTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private ShopRepository shopRepository;
    @Mock
    private ShopMemberRepository shopMemberRepository;

    private static final String SECRET = "test-cloudpayments-secret";

    private BillingController controllerWithSecret(String apiSecret) {
        SubscriptionService subscriptionService = new SubscriptionService(subscriptionRepository, planRepository);
        CloudPaymentsService cloudPaymentsService = new CloudPaymentsService(subscriptionService, planRepository);
        ReflectionTestUtils.setField(cloudPaymentsService, "apiSecret", apiSecret);
        ReflectionTestUtils.setField(cloudPaymentsService, "publicId", "pk_test");
        AuthorizationService authorizationService = new AuthorizationService(shopRepository, shopMemberRepository);
        return new BillingController(subscriptionService, cloudPaymentsService, authorizationService, planRepository);
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void cloudpaymentsPay_withValidHmac_processesPayment() throws Exception {
        BillingController controller = controllerWithSecret(SECRET);
        String body = "AccountId=shop-1&InvoiceId=BASIC_MONTHLY&TransactionId=tx-1&Amount=990";
        when(subscriptionRepository.findByShopId("shop-1")).thenReturn(Optional.empty());

        var response = controller.cloudpaymentsPay(body, hmac(SECRET, body), null);

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionRepository).findByShopId("shop-1");
    }

    @Test
    void cloudpaymentsPay_withInvalidHmac_neverTouchesSubscription() {
        BillingController controller = controllerWithSecret(SECRET);
        String body = "AccountId=shop-1&InvoiceId=BASIC_MONTHLY&TransactionId=tx-1&Amount=990";

        var response = controller.cloudpaymentsPay(body, "not-the-real-signature", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("code"));
        verify(subscriptionRepository, never()).findByShopId("shop-1");
    }

    @Test
    void cloudpaymentsCheck_withInvalidHmac_isRejectedWithCode13() {
        BillingController controller = controllerWithSecret(SECRET);
        String body = "AccountId=shop-1&InvoiceId=BASIC_MONTHLY&Amount=990&Currency=RUB";

        var response = controller.cloudpaymentsCheck(body, "wrong", null);

        assertEquals(13, response.getBody().get("code"));
        verify(planRepository, never()).findByCode("BASIC_MONTHLY");
    }

    @Test
    void cloudpaymentsPay_withNoSecretConfigured_skipsHmacCheck() throws Exception {
        // Dev/stub mode (unchanged behavior): validateHmac already treats a blank secret as "skip".
        BillingController controller = controllerWithSecret("");
        String body = "AccountId=shop-1&InvoiceId=BASIC_MONTHLY&TransactionId=tx-1&Amount=990";
        when(subscriptionRepository.findByShopId("shop-1")).thenReturn(Optional.empty());

        var response = controller.cloudpaymentsPay(body, null, null);

        assertEquals(200, response.getStatusCode().value());
        verify(subscriptionRepository).findByShopId("shop-1");
    }

    @Test
    void confirmPayment_withLiveGatewayConfigured_returnsConflictWithoutActivating() {
        BillingController controller = controllerWithSecret(SECRET);
        AdminUser owner = AdminUser.builder().id(1L).email("owner@example.com").build();
        lenient().when(shopRepository.findByShopId("shop-1"))
                .thenReturn(Optional.of(Shop.builder().id(1L).shopId("shop-1").ownerId(1L).build()));

        var response = controller.confirmPayment("shop-1", "BASIC_MONTHLY", owner);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(subscriptionRepository, never()).findByShopId("shop-1");
    }

    @Test
    void confirmPayment_withoutLiveGateway_stillAttemptsActivation() {
        // Dev/stub mode (unchanged behavior) so local development and demo shops keep working.
        BillingController controller = controllerWithSecret("");
        AdminUser owner = AdminUser.builder().id(1L).email("owner@example.com").build();
        when(shopRepository.findByShopId("shop-1"))
                .thenReturn(Optional.of(Shop.builder().id(1L).shopId("shop-1").ownerId(1L).build()));
        when(subscriptionRepository.findByShopId("shop-1")).thenReturn(Optional.empty());

        var response = controller.confirmPayment("shop-1", "BASIC_MONTHLY", owner);

        assertEquals(400, response.getStatusCode().value());
        verify(subscriptionRepository).findByShopId("shop-1");
    }
}
