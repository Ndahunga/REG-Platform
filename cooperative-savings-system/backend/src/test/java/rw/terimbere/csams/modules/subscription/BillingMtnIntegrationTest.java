package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.audit.entity.AuditLog;
import rw.terimbere.csams.modules.audit.repository.AuditLogRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.payment.mtn.MtnCollectionStatus;
import rw.terimbere.csams.modules.subscription.payment.mtn.MtnMomoClient;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.modules.subscription.service.SubscriptionCalendar;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.PaymentIntegrationUnavailableException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "app.subscription.payment.mtn.enabled=true",
            "app.subscription.payment.mtn.base-url=https://sandbox.momodeveloper.mtn.com",
            "app.subscription.payment.mtn.subscription-key=mtn-test-subscription-key-secret",
            "app.subscription.payment.mtn.api-user=mtn-test-api-user",
            "app.subscription.payment.mtn.api-key=mtn-test-api-key-secret",
            "app.subscription.payment.mtn.target-environment=sandbox"
        })
class BillingMtnIntegrationTest {

    private static final String SECRET_A = "mtn-test-subscription-key-secret";
    private static final String SECRET_B = "mtn-test-api-key-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionPaymentRepository paymentRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SubscriptionPricing pricing;

    @MockBean
    private MtnMomoClient mtnMomoClient;

    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        reset(mtnMomoClient);
        when(mtnMomoClient.isConfigured()).thenReturn(true);
        when(mtnMomoClient.getRequestToPayStatus(any())).thenReturn(MtnCollectionStatus.PENDING);
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
    }

    @Test
    void mtnCheckoutCreatesPendingPaymentUsingServerPricing() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        long ledgerBefore = ledgerEntryRepository.countByCooperativeId(scheme.id());
        String body = mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "billingCycle":"MONTHLY",
                                  "paymentChannel":"MTN_MOMO",
                                  "payerPhoneNumber":"0781234567",
                                  "amount":1,
                                  "price":99,
                                  "discount":50
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").exists())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.billingCycle").value("MONTHLY"))
                .andExpect(jsonPath("$.data.paymentChannel").value("MTN_MOMO"))
                .andExpect(jsonPath("$.data.amount").value(2000.0))
                .andExpect(jsonPath("$.data.currency").value("RWF"))
                .andExpect(jsonPath("$.data.message").value("Payment request sent. Approve the payment on your phone."))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(SECRET_A, SECRET_B, "access_token", "api-key");

        UUID paymentId = UUID.fromString(objectMapper.readTree(body).path("data").path("paymentId").asText());
        SubscriptionPayment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getAmount()).isEqualByComparingTo(pricing.monthly().charge());
        assertThat(payment.getStatus()).isEqualTo(SubscriptionPaymentStatus.PENDING);
        assertThat(payment.getProvider()).isEqualTo("MTN_MOMO");
        assertThat(payment.getExternalReference()).isEqualTo(paymentId.toString());
        assertThat(payment.getIdempotencyKey()).isEqualTo("mtn-momo:" + paymentId);

        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
        assertThat(ledgerEntryRepository.countByCooperativeId(scheme.id())).isEqualTo(ledgerBefore);
        assertNoSecretsInAudit(scheme.id());
    }

    @Test
    void annualCheckoutUsesServerAmountAndNormalizesPlusPhone() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"ANNUAL","paymentChannel":"MTN_MOMO","payerPhoneNumber":"+250781234567"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(18000.0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void invalidPhoneIsRejected() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO","payerPhoneNumber":"123"}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id())).isEmpty();
    }

    @Test
    void providerInitiationFailureMarksPaymentFailedWithoutActivating() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        doThrow(new BusinessException("PAYMENT_PROVIDER_ERROR", "Payment request could not be sent to MTN Mobile Money"))
                .when(mtnMomoClient)
                .requestToPay(any(), any(), any(), any(), any());
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0781234567"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.message").value("Payment was not completed."));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
        List<SubscriptionPayment> payments = paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(SubscriptionPaymentStatus.FAILED);
    }

    @Test
    void repeatedCheckoutReusesPendingAttempt() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        String first = checkout(scheme, "MONTHLY");
        UUID firstId = UUID.fromString(objectMapper.readTree(first).path("data").path("paymentId").asText());
        String second = checkout(scheme, "MONTHLY");
        UUID secondId = UUID.fromString(objectMapper.readTree(second).path("data").path("paymentId").asText());
        assertThat(secondId).isEqualTo(firstId);
        assertThat(paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id())).hasSize(1);
    }

    @Test
    void differentMtnPhoneDoesNotReusePendingAttempt() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        String first = checkout(scheme, "MONTHLY");
        UUID firstId = UUID.fromString(objectMapper.readTree(first).path("data").path("paymentId").asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0789999999"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        List<SubscriptionPayment> payments = paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id());
        assertThat(payments).hasSize(2);
        SubscriptionPayment original = paymentRepository.findById(firstId).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(SubscriptionPaymentStatus.CANCELED);
        assertThat(original.getPayerMsisdn()).isEqualTo("250781234567");
        SubscriptionPayment retry = payments.stream()
                .filter(payment -> !payment.getId().equals(firstId))
                .findFirst()
                .orElseThrow();
        assertThat(retry.getStatus()).isEqualTo(SubscriptionPaymentStatus.PENDING);
        assertThat(retry.getPayerMsisdn()).isEqualTo("250789999999");
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
    }

    @Test
    void staleMtnPendingCanRetry() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID firstId = UUID.fromString(objectMapper.readTree(checkout(scheme, "MONTHLY")).path("data").path("paymentId").asText());
        SubscriptionPayment stale = paymentRepository.findById(firstId).orElseThrow();
        stale.setInitiatedAt(Instant.now().minusSeconds(16 * 60));
        paymentRepository.saveAndFlush(stale);
        UUID secondId = UUID.fromString(objectMapper.readTree(checkout(scheme, "MONTHLY")).path("data").path("paymentId").asText());
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(paymentRepository.findById(firstId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.CANCELED);
        assertThat(paymentRepository.findById(secondId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.PENDING);
    }

    @Test
    void providerSuccessActivatesMonthlyFromExpired() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        UUID paymentId = UUID.fromString(objectMapper.readTree(checkout(scheme, "MONTHLY")).path("data").path("paymentId").asText());
        when(mtnMomoClient.getRequestToPayStatus(paymentId)).thenReturn(MtnCollectionStatus.SUCCESSFUL);

        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.amount").value(2000.0));

        CooperativeSubscription subscription = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getBillingCycle()).isEqualTo(SubscriptionBillingCycle.MONTHLY);
        assertThat(subscription.getCurrentPeriodStartedAt()).isNotNull();
        assertThat(subscription.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(
                        subscription.getCurrentPeriodStartedAt(), 1, pricing.zoneId()));
        long ledger = ledgerEntryRepository.countByCooperativeId(scheme.id());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.content[0].status").value("SUCCESS"));
        assertThat(ledgerEntryRepository.countByCooperativeId(scheme.id())).isEqualTo(ledger);
        assertNoSecretsInAudit(scheme.id());
    }

    @Test
    void annualSuccessDuringTrialStartsAtTrialEnd() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        CooperativeSubscription before = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow();
        Instant trialEnd = before.getTrialEndsAt();
        UUID paymentId = UUID.fromString(objectMapper.readTree(checkout(scheme, "ANNUAL")).path("data").path("paymentId").asText());
        when(mtnMomoClient.getRequestToPayStatus(paymentId)).thenReturn(MtnCollectionStatus.SUCCESSFUL);
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        CooperativeSubscription after = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow();
        assertThat(after.getCurrentPeriodStartedAt()).isEqualTo(trialEnd);
        assertThat(after.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(trialEnd, 12, pricing.zoneId()));
        assertThat(after.getTrialEndsAt()).isEqualTo(trialEnd);
    }

    @Test
    void earlyRenewalExtendsFromCurrentPeriodEnd() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        Instant periodEnd = Instant.parse("2027-03-20T00:00:00Z");
        setSubscription(scheme.id(), SubscriptionStatus.ACTIVE, sub -> {
            sub.setBillingCycle(SubscriptionBillingCycle.ANNUAL);
            sub.setCurrentPeriodStartedAt(Instant.parse("2026-03-20T00:00:00Z"));
            sub.setCurrentPeriodEndsAt(periodEnd);
        });
        UUID paymentId = UUID.fromString(objectMapper.readTree(checkout(scheme, "ANNUAL")).path("data").path("paymentId").asText());
        when(mtnMomoClient.getRequestToPayStatus(paymentId)).thenReturn(MtnCollectionStatus.SUCCESSFUL);
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        CooperativeSubscription after = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow();
        assertThat(after.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(periodEnd, 12, pricing.zoneId()));
        assertThat(after.getCurrentPeriodStartedAt()).isEqualTo(Instant.parse("2026-03-20T00:00:00Z"));
    }

    @Test
    void duplicateCallbackDoesNotDoubleExtend() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        UUID paymentId = UUID.fromString(objectMapper.readTree(checkout(scheme, "MONTHLY")).path("data").path("paymentId").asText());
        when(mtnMomoClient.getRequestToPayStatus(paymentId)).thenReturn(MtnCollectionStatus.SUCCESSFUL);
        String callback = """
                {"externalId":"%s","status":"SUCCESSFUL"}
                """.formatted(paymentId);
        mockMvc.perform(post("/api/v1/public/billing/mtn/callback/" + paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callback))
                .andExpect(status().isOk());
        Instant ends = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getCurrentPeriodEndsAt();
        mockMvc.perform(post("/api/v1/public/billing/mtn/callback/" + paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callback))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        assertThat(subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getCurrentPeriodEndsAt())
                .isEqualTo(ends);
    }

    @Test
    void webhookAndPollRaceDoesNotDoubleExtend() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        UUID paymentId = UUID.fromString(objectMapper.readTree(checkout(scheme, "MONTHLY")).path("data").path("paymentId").asText());
        when(mtnMomoClient.getRequestToPayStatus(paymentId)).thenReturn(MtnCollectionStatus.SUCCESSFUL);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var callback = pool.submit(() -> mockMvc.perform(post("/api/v1/public/billing/mtn/callback/" + paymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"externalId\":\"" + paymentId + "\",\"status\":\"SUCCESSFUL\"}"))
                    .andExpect(status().isOk()));
            var poll = pool.submit(() -> mockMvc.perform(
                            get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                                    .header("Authorization", "Bearer " + scheme.presidentToken()))
                    .andExpect(status().isOk()));
            start.countDown();
            callback.get(20, TimeUnit.SECONDS);
            poll.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        CooperativeSubscription subscription = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        Instant expected = SubscriptionCalendar.plusCalendarMonths(
                subscription.getCurrentPeriodStartedAt(), 1, pricing.zoneId());
        assertThat(subscription.getCurrentPeriodEndsAt()).isEqualTo(expected);
    }

    @Test
    void memberCannotCheckoutEvenWithPhone() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        Member member = addMember(scheme.id(), "MEMBER");
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0781234567"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredCooperativeAndSuperAdminCanInitiateMtnCheckout() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0781234567"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"ANNUAL","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0781234567"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.amount").value(18000.0));
    }

    @Test
    void cardStillDoesNotFakeSuccessWhenMtnIsEnabled() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(PaymentIntegrationUnavailableException.CODE));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
    }

    @Test
    void paymentHistoryKeepsFailedAttempts() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        doThrow(new BusinessException("PAYMENT_PROVIDER_ERROR", "Payment request could not be sent to MTN Mobile Money"))
                .when(mtnMomoClient)
                .requestToPay(any(), any(), any(), any(), any());
        checkout(scheme, "MONTHLY");
        reset(mtnMomoClient);
        when(mtnMomoClient.isConfigured()).thenReturn(true);
        when(mtnMomoClient.getRequestToPayStatus(any())).thenReturn(MtnCollectionStatus.PENDING);
        checkout(scheme, "MONTHLY");
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));
        assertThat(paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id()).stream()
                        .map(SubscriptionPayment::getStatus)
                        .toList())
                .contains(SubscriptionPaymentStatus.FAILED, SubscriptionPaymentStatus.PENDING);
    }

    private String checkout(Scheme scheme, String cycle) throws Exception {
        return mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"%s","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0781234567"}
                                """.formatted(cycle)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void assertNoSecretsInAudit(UUID cooperativeId) {
        for (AuditableAction action : List.of(
                AuditableAction.SUBSCRIPTION_PAYMENT_INITIATED,
                AuditableAction.SUBSCRIPTION_PAYMENT_SUCCESS,
                AuditableAction.SUBSCRIPTION_PAYMENT_FAILED,
                AuditableAction.SUBSCRIPTION_ACTIVATED,
                AuditableAction.SUBSCRIPTION_RENEWED)) {
            for (AuditLog log : auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                    cooperativeId, action.name())) {
                String previous = log.getPreviousValues() == null ? "" : log.getPreviousValues();
                String next = log.getNewValues() == null ? "" : log.getNewValues();
                assertThat(previous).doesNotContain(SECRET_A, SECRET_B);
                assertThat(next).doesNotContain(SECRET_A, SECRET_B, "access_token");
            }
        }
    }

    private Member addMember(UUID cooperativeId, String role) throws Exception {
        String username = role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Test",
                                  "lastName":"%s",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s"
                                }
                                """.formatted(role, username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        String password = objectMapper
                .readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();
        return new Member(loginAccessToken(username, password));
    }

    private Scheme createSchemeWithPresident() throws Exception {
        String name = "MtnBill " + UUID.randomUUID().toString().substring(0, 8);
        UUID coopId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn());
        String username = "mtnpres_" + UUID.randomUUID().toString().substring(0, 8);
        String password = "President1!";
        mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/administrators")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "firstName":"Pat",
                                  "lastName":"President",
                                  "temporaryPassword":"%s"
                                }
                                """.formatted(username, username, password)))
                .andExpect(status().isOk());
        return new Scheme(coopId, loginAccessToken(username, password));
    }

    private void expireTrial(UUID cooperativeId) {
        setSubscription(cooperativeId, SubscriptionStatus.TRIAL, sub -> {
            Instant ended = Instant.now().minusSeconds(60);
            sub.setTrialEndsAt(ended);
            if (sub.getTrialStartedAt() == null) {
                sub.setTrialStartedAt(ended.minusSeconds(86_400));
            }
        });
    }

    private void setSubscription(
            UUID cooperativeId, SubscriptionStatus status, java.util.function.Consumer<CooperativeSubscription> mutator) {
        CooperativeSubscription subscription =
                subscriptionRepository.findByCooperativeId(cooperativeId).orElseThrow();
        subscription.setStatus(status);
        mutator.accept(subscription);
        subscriptionRepository.saveAndFlush(subscription);
    }

    private UUID idFrom(MvcResult result) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return UUID.fromString(data.path("id").asText());
    }

    private String loginAccessToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private record Scheme(UUID id, String presidentToken) {}

    private record Member(String token) {}
}
