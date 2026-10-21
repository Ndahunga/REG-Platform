package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveClient;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveClient.HostedCheckoutRequest;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveClient.HostedCheckoutSession;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveClient.VerifiedTransaction;
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
            "app.subscription.payment.flutterwave.enabled=true",
            "app.subscription.payment.flutterwave.base-url=https://api.flutterwave.com/v3",
            "app.subscription.payment.flutterwave.mode=test",
            "app.subscription.payment.flutterwave.secret-key=flw-test-secret-key",
            "app.subscription.payment.flutterwave.secret-hash=flw-test-secret-hash",
            "app.subscription.payment.flutterwave.redirect-url=http://localhost:5173/billing/payment-return",
            "app.subscription.payment.flutterwave.webhook-url=http://localhost:8080/api/v1/public/billing/flutterwave/webhook",
            "app.subscription.payment.pending-reuse-minutes=15",
            "app.subscription.payment.reconciliation.enabled=false"
        })
class BillingFlutterwaveIntegrationTest {

    private static final String SECRET_KEY = "flw-test-secret-key";
    private static final String SECRET_HASH = "flw-test-secret-hash";
    private static final String CHECKOUT_URL = "https://checkout.flutterwave.com/pay/ouwealth-test";

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
    private FlutterwaveClient flutterwaveClient;

    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        reset(flutterwaveClient);
        when(flutterwaveClient.isConfigured()).thenReturn(true);
        when(flutterwaveClient.createHostedCheckout(any())).thenReturn(new HostedCheckoutSession(CHECKOUT_URL));
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
    }

    @Test
    void cardCheckoutCreatesPendingMonthlyPaymentAndReturnsHostedUrl() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        long ledgerBefore = ledgerEntryRepository.countByCooperativeId(scheme.id());
        String body = mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "billingCycle":"MONTHLY",
                                  "paymentChannel":"CARD",
                                  "amount":1,
                                  "cardNumber":"4111111111111111",
                                  "cvv":"123",
                                  "expiry":"12/29"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.billingCycle").value("MONTHLY"))
                .andExpect(jsonPath("$.data.paymentChannel").value("CARD"))
                .andExpect(jsonPath("$.data.amount").value(2000.0))
                .andExpect(jsonPath("$.data.currency").value("RWF"))
                .andExpect(jsonPath("$.data.checkoutUrl").value(CHECKOUT_URL))
                .andExpect(jsonPath("$.data.message").value("Continue to secure card payment."))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(SECRET_KEY, SECRET_HASH, "4111111111111111", "cvv");

        UUID paymentId = UUID.fromString(objectMapper.readTree(body).path("data").path("paymentId").asText());
        SubscriptionPayment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getAmount()).isEqualByComparingTo(pricing.monthly().charge());
        assertThat(payment.getProvider()).isEqualTo("FLUTTERWAVE");
        assertThat(payment.getExternalReference()).isEqualTo("ouwealth-sub-" + paymentId);
        assertThat(payment.getCheckoutUrl()).isEqualTo(CHECKOUT_URL);
        assertThat(payment.getIdempotencyKey()).isEqualTo("flutterwave:" + paymentId);

        ArgumentCaptor<HostedCheckoutRequest> captor = ArgumentCaptor.forClass(HostedCheckoutRequest.class);
        verify(flutterwaveClient).createHostedCheckout(captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("2000.0000");
        assertThat(captor.getValue().currency()).isEqualTo("RWF");
        assertThat(captor.getValue().txRef()).isEqualTo("ouwealth-sub-" + paymentId);
        assertThat(captor.getValue().customerEmail()).isNotBlank();

        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
        assertThat(ledgerEntryRepository.countByCooperativeId(scheme.id())).isEqualTo(ledgerBefore);
        assertNoSecretsInAudit(scheme.id());
    }

    @Test
    void annualCardCheckoutUsesServerAmount() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"ANNUAL","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(18000.0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void hostedCheckoutFailureDoesNotActivate() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        when(flutterwaveClient.createHostedCheckout(any()))
                .thenThrow(new BusinessException("PAYMENT_PROVIDER_ERROR", "Card checkout could not be started"));
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
    }

    @Test
    void redirectOrPendingVerifyDoesNotActivate() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutPayment(scheme, "MONTHLY");
        when(flutterwaveClient.verifyByReference(any()))
                .thenReturn(new VerifiedTransaction("pending", "ouwealth-sub-" + paymentId, "RWF", new BigDecimal("2000.0000"), "1"));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
    }

    @Test
    void verifiedSuccessActivatesExpiredMonthlyWithoutLedgerWrite() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        long ledgerBefore = ledgerEntryRepository.countByCooperativeId(scheme.id());
        UUID paymentId = checkoutPayment(scheme, "MONTHLY");
        stubSuccessfulVerify(paymentId, "2000.0000");
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        CooperativeSubscription subscription = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getBillingCycle()).isEqualTo(SubscriptionBillingCycle.MONTHLY);
        assertThat(subscription.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(
                        subscription.getCurrentPeriodStartedAt(), 1, pricing.zoneId()));
        assertThat(ledgerEntryRepository.countByCooperativeId(scheme.id())).isEqualTo(ledgerBefore);
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.content[0].paymentChannel").value("CARD"))
                .andExpect(jsonPath("$.data.content[0].status").value("SUCCESS"));
        assertNoSecretsInAudit(scheme.id());
    }

    @Test
    void txRefMismatchDoesNotActivate() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutPayment(scheme, "MONTHLY");
        when(flutterwaveClient.verifyByReference(any()))
                .thenReturn(new VerifiedTransaction(
                        "successful", "ouwealth-sub-" + UUID.randomUUID(), "RWF", new BigDecimal("2000.0000"), "9"));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertTrial(scheme);
    }

    @Test
    void amountMismatchDoesNotActivate() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutPayment(scheme, "MONTHLY");
        when(flutterwaveClient.verifyByReference(any()))
                .thenReturn(new VerifiedTransaction(
                        "successful", "ouwealth-sub-" + paymentId, "RWF", new BigDecimal("1.0000"), "9"));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertTrial(scheme);
    }

    @Test
    void currencyMismatchDoesNotActivate() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutPayment(scheme, "MONTHLY");
        when(flutterwaveClient.verifyByReference(any()))
                .thenReturn(new VerifiedTransaction(
                        "successful", "ouwealth-sub-" + paymentId, "USD", new BigDecimal("2000.0000"), "9"));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertTrial(scheme);
    }

    @Test
    void invalidWebhookSignatureDoesNotActivate() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutPayment(scheme, "MONTHLY");
        stubSuccessfulVerify(paymentId, "2000.0000");
        mockMvc.perform(post("/api/v1/public/billing/flutterwave/webhook")
                        .header("verif-hash", "wrong-hash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody(paymentId, 2000)))
                .andExpect(status().isUnauthorized());
        assertTrial(scheme);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.PENDING);
    }

    @Test
    void duplicateWebhookExtendsOnce() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        UUID paymentId = checkoutPayment(scheme, "MONTHLY");
        stubSuccessfulVerify(paymentId, "2000.0000");
        mockMvc.perform(post("/api/v1/public/billing/flutterwave/webhook")
                        .header("verif-hash", SECRET_HASH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody(paymentId, 1)))
                .andExpect(status().isOk());
        Instant ends = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getCurrentPeriodEndsAt();
        mockMvc.perform(post("/api/v1/public/billing/flutterwave/webhook")
                        .header("verif-hash", SECRET_HASH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody(paymentId, 1)))
                .andExpect(status().isOk());
        assertThat(subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getCurrentPeriodEndsAt())
                .isEqualTo(ends);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.SUCCESS);
    }

    @Test
    void webhookAndReturnPageRaceExtendsOnce() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        UUID paymentId = checkoutPayment(scheme, "MONTHLY");
        stubSuccessfulVerify(paymentId, "2000.0000");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var webhook = pool.submit(() -> mockMvc.perform(post("/api/v1/public/billing/flutterwave/webhook")
                            .header("verif-hash", SECRET_HASH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(webhookBody(paymentId, 2000)))
                    .andExpect(status().isOk()));
            var poll = pool.submit(() -> mockMvc.perform(
                            get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                                    .header("Authorization", "Bearer " + scheme.presidentToken()))
                    .andExpect(status().isOk()));
            webhook.get(20, TimeUnit.SECONDS);
            poll.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        CooperativeSubscription subscription = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(
                        subscription.getCurrentPeriodStartedAt(), 1, pricing.zoneId()));
    }

    @Test
    void trialCardPaymentPreservesRemainingTrial() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        Instant trialEnd = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getTrialEndsAt();
        UUID paymentId = checkoutPayment(scheme, "ANNUAL");
        stubSuccessfulVerify(paymentId, "18000.0000");
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
        UUID paymentId = checkoutPayment(scheme, "ANNUAL");
        stubSuccessfulVerify(paymentId, "18000.0000");
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        CooperativeSubscription after = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow();
        assertThat(after.getCurrentPeriodEndsAt())
                .isEqualTo(SubscriptionCalendar.plusCalendarMonths(periodEnd, 12, pricing.zoneId()));
    }

    @Test
    void memberCannotStartCardCheckout() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        Member member = addMember(scheme.id(), "MEMBER");
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminAndExpiredCooperativeCanPayByCard() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"ANNUAL","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(18000.0));
    }

    @Test
    void plansAdvertiseCardAvailability() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/plans")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cardCheckoutAvailable").value(true));
    }

    @Test
    void doubleClickReusesSameCardCheckout() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID first = checkoutPayment(scheme, "MONTHLY");
        UUID second = checkoutPayment(scheme, "MONTHLY");
        assertThat(second).isEqualTo(first);
        verify(flutterwaveClient, times(1)).createHostedCheckout(any());
        assertThat(paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id())).hasSize(1);
    }

    @Test
    void staleCardPendingCanRetry() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID first = checkoutPayment(scheme, "MONTHLY");
        SubscriptionPayment stale = paymentRepository.findById(first).orElseThrow();
        stale.setInitiatedAt(Instant.now().minusSeconds(16 * 60));
        paymentRepository.saveAndFlush(stale);
        UUID second = checkoutPayment(scheme, "MONTHLY");
        assertThat(second).isNotEqualTo(first);
        assertThat(paymentRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.CANCELED);
        verify(flutterwaveClient, times(2)).createHostedCheckout(any());
    }

    @Test
    void mtnStillUnavailableWhenOnlyFlutterwaveIsEnabled() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0781234567"}
                                """))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(PaymentIntegrationUnavailableException.CODE));
    }

    private void stubSuccessfulVerify(UUID paymentId, String amount) {
        VerifiedTransaction verified = new VerifiedTransaction(
                "successful", "ouwealth-sub-" + paymentId, "RWF", new BigDecimal(amount), "991");
        when(flutterwaveClient.verifyByReference("ouwealth-sub-" + paymentId)).thenReturn(verified);
        when(flutterwaveClient.verifyByTransactionId(any())).thenReturn(verified);
    }

    private UUID checkoutPayment(Scheme scheme, String cycle) throws Exception {
        String body = mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"%s","paymentChannel":"CARD"}
                                """.formatted(cycle)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).path("data").path("paymentId").asText());
    }

    private String webhookBody(UUID paymentId, int amount) {
        return """
                {"event":"charge.completed","data":{"id":991,"tx_ref":"ouwealth-sub-%s","status":"successful","amount":%s,"currency":"RWF"}}
                """.formatted(paymentId, amount);
    }

    private void assertTrial(Scheme scheme) throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
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
                assertThat(previous).doesNotContain(SECRET_KEY, SECRET_HASH, "4111111111111111");
                assertThat(next).doesNotContain(SECRET_KEY, SECRET_HASH, "verif-hash");
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
        String name = "FlwBill " + UUID.randomUUID().toString().substring(0, 8);
        UUID coopId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn());
        String username = "flwpres_" + UUID.randomUUID().toString().substring(0, 8);
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
