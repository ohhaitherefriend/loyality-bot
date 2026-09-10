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
import com.plstk.loyaltybot.entity.ShopMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
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
        return controllerWithSecretAndEnvironment(apiSecret, new MockEnvironment());
    }

    private BillingController controllerWithSecretAndEnvironment(String apiSecret, MockEnvironment environment) {
        return controllerWithSecretEnvironmentAndSystemAdmins(apiSecret, environment, "");
    }

    private BillingController controllerWithSecretEnvironmentAndSystemAdmins(
            String apiSecret, MockEnvironment environment, String systemAdminEmails) {
        SubscriptionService subscriptionService = new SubscriptionService(subscriptionRepository, planRepository);
        CloudPaymentsService cloudPaymentsService =
                new CloudPaymentsService(subscriptionService, planRepository, environment);
        ReflectionTestUtils.setField(cloudPaymentsService, "apiSecret", apiSecret);
        ReflectionTestUtils.setField(cloudPaymentsService, "publicId", "pk_test");
        AuthorizationService authorizationService = new AuthorizationService(shopRepository, shopMemberRepository);
        ReflectionTestUtils.setField(authorizationService, "systemAdminEmailsRaw", systemAdminEmails);
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

    /**
     * Six-bug hardening pass (ADR-026): {@code activate-stub}/{@code extend-trial} grant billing
     * state without any real payment, so an ordinary {@code STAFF} shop member must never be able
     * to call them.
     */
    @Test
    void activateStub_asOrdinaryStaffMember_isForbidden() {
        BillingController controller = controllerWithSecret("");
        AdminUser staff = AdminUser.builder().id(2L).email("staff@example.com").build();
        lenient().when(shopRepository.findByShopId("shop-1"))
                .thenReturn(Optional.of(Shop.builder().id(1L).shopId("shop-1").ownerId(1L).build()));
        lenient().when(shopMemberRepository.findByUserIdAndShopId(2L, "shop-1"))
                .thenReturn(Optional.of(ShopMember.builder()
                        .userId(2L).shopId("shop-1").role(ShopMember.MemberRole.STAFF).build()));

        var response = controller.activateStub("shop-1", "BASIC_MONTHLY", staff);

        assertEquals(403, response.getStatusCode().value());
        verify(subscriptionRepository, never()).findByShopId("shop-1");
    }

    /**
     * Follow-up to the six-bug hardening pass (ADR-028): restricting to {@code OWNER} (ADR-026)
     * was insufficient - a shop's own owner is a platform CUSTOMER, not a platform operator, and
     * could still grant their own shop a free subscription. Reproduced by the report: with a real
     * payment gateway configured, an ordinary shop owner could still reach {@code activate-stub}.
     */
    @Test
    void activateStub_asShopOwnerWhoIsNotSystemAdmin_isForbidden() {
        BillingController controller = controllerWithSecretEnvironmentAndSystemAdmins(
                SECRET, new MockEnvironment(), "admin@platform.example");
        AdminUser owner = AdminUser.builder().id(1L).email("owner@example.com").build();
        lenient().when(shopRepository.findByShopId("shop-1"))
                .thenReturn(Optional.of(Shop.builder().id(1L).shopId("shop-1").ownerId(1L).build()));

        var response = controller.activateStub("shop-1", "BASIC_MONTHLY", owner);

        assertEquals(403, response.getStatusCode().value());
        verify(subscriptionRepository, never()).findByShopId("shop-1");
    }

    @Test
    void activateStub_asPlatformSystemAdmin_succeeds() {
        BillingController controller = controllerWithSecretEnvironmentAndSystemAdmins(
                SECRET, new MockEnvironment(), "admin@platform.example");
        AdminUser platformAdmin = AdminUser.builder().id(99L).email("admin@platform.example").build();
        com.plstk.loyaltybot.entity.Subscription existing =
                com.plstk.loyaltybot.entity.Subscription.builder().id(1L).shopId("shop-1").build();
        when(subscriptionRepository.findByShopId("shop-1")).thenReturn(Optional.of(existing));
        when(subscriptionRepository.save(existing)).thenReturn(existing);
        when(planRepository.findByCode("BASIC_MONTHLY")).thenReturn(Optional.empty());

        var response = controller.activateStub("shop-1", "BASIC_MONTHLY", platformAdmin);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void extendTrial_asOrdinaryAdminMember_isForbidden() {
        BillingController controller = controllerWithSecret("");
        AdminUser admin = AdminUser.builder().id(2L).email("admin@example.com").build();
        lenient().when(shopRepository.findByShopId("shop-1"))
                .thenReturn(Optional.of(Shop.builder().id(1L).shopId("shop-1").ownerId(1L).build()));
        lenient().when(shopMemberRepository.findByUserIdAndShopId(2L, "shop-1"))
                .thenReturn(Optional.of(ShopMember.builder()
                        .userId(2L).shopId("shop-1").role(ShopMember.MemberRole.ADMIN).build()));

        var response = controller.extendTrial("shop-1", 7, admin);

        assertEquals(403, response.getStatusCode().value());
        verify(subscriptionRepository, never()).findByShopId("shop-1");
    }

    @Test
    void extendTrial_asShopOwnerWhoIsNotSystemAdmin_isForbidden() {
        BillingController controller = controllerWithSecretEnvironmentAndSystemAdmins(
                "", new MockEnvironment(), "admin@platform.example");
        AdminUser owner = AdminUser.builder().id(1L).email("owner@example.com").build();
        lenient().when(shopRepository.findByShopId("shop-1"))
                .thenReturn(Optional.of(Shop.builder().id(1L).shopId("shop-1").ownerId(1L).build()));

        var response = controller.extendTrial("shop-1", 7, owner);

        assertEquals(403, response.getStatusCode().value());
        verify(subscriptionRepository, never()).findByShopId("shop-1");
    }

    /**
     * Follow-up to the six-bug hardening pass (ADR-028): {@code confirm-payment} must reject
     * client-side self-activation in production even when the CloudPayments secret is missing -
     * a missing secret in prod is a misconfiguration, not a legitimate dev/stub signal.
     */
    @Test
    void confirmPayment_inProdProfileWithoutSecret_returnsConflictWithoutActivating() {
        MockEnvironment prod = new MockEnvironment();
        prod.addActiveProfile("prod");
        BillingController controller = controllerWithSecretAndEnvironment("", prod);
        AdminUser owner = AdminUser.builder().id(1L).email("owner@example.com").build();
        lenient().when(shopRepository.findByShopId("shop-1"))
                .thenReturn(Optional.of(Shop.builder().id(1L).shopId("shop-1").ownerId(1L).build()));

        var response = controller.confirmPayment("shop-1", "BASIC_MONTHLY", owner);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(subscriptionRepository, never()).findByShopId("shop-1");
    }
}
