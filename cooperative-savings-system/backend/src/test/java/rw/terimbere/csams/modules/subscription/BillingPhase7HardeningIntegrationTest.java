package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveClient;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveClient.HostedCheckoutSession;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveClient.VerifiedTransaction;
import rw.terimbere.csams.modules.subscription.payment.mtn.MtnCollectionStatus;
import rw.terimbere.csams.modules.subscription.payment.mtn.MtnMomoClient;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.modules.subscription.service.BillingService;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPaymentReconciliationService;
import rw.terimbere.csams.shared.exceptions.BusinessException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "app.subscription.payment.mtn.enabled=true",
            "app.subscription.payment.mtn.base-url=https://sandbox.momodeveloper.mtn.com",
            "app.subscription.payment.mtn.subscription-key=mtn-phase7-subscription-key",
            "app.subscription.payment.mtn.api-user=mtn-phase7-api-user",
            "app.subscription.payment.mtn.api-key=mtn-phase7-api-key",
            "app.subscription.payment.mtn.target-environment=sandbox",
            "app.subscription.payment.mtn.callback-url=http://localhost:8080/api/v1/public/billing/mtn/callback",
            "app.subscription.payment.flutterwave.enabled=true",
            "app.subscription.payment.flutterwave.base-url=https://api.flutterwave.com/v3",
            "app.subscription.payment.flutterwave.mode=test",
            "app.subscription.payment.flutterwave.secret-key=flw-phase7-secret-key",
            "app.subscription.payment.flutterwave.secret-hash=flw-phase7-secret-hash",
            "app.subscription.payment.flutterwave.redirect-url=http://localhost:5173/billing/payment-return",
            "app.subscription.payment.flutterwave.webhook-url=http://localhost:8080/api/v1/public/billing/flutterwave/webhook",
            "app.subscription.payment.reconciliation.enabled=false",
            "app.subscription.payment.pending-abandon-hours=48",
            "app.subscription.payment.reconciliation.min-age-minutes=0"
        })
class BillingPhase7HardeningIntegrationTest {

    private static final String FLW_HASH = "flw-phase7-secret-hash";
    private static final String FLW_SECRET = "flw-phase7-secret-key";
    private static final String MTN_KEY = "mtn-phase7-subscription-key";

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
    private SubscriptionPaymentReconciliationService reconciliationService;

    @Autowired
    private BillingService billingService;

    @MockBean
    private MtnMomoClient mtnMomoClient;

    @MockBean
    private FlutterwaveClient flutterwaveClient;

    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        reset(mtnMomoClient, flutterwaveClient);
        when(mtnMomoClient.isConfigured()).thenReturn(true);
        when(mtnMomoClient.getRequestToPayStatus(any())).thenReturn(MtnCollectionStatus.PENDING);
        when(flutterwaveClient.isConfigured()).thenReturn(true);
        when(flutterwaveClient.createHostedCheckout(any()))
                .thenReturn(new HostedCheckoutSession("https://checkout.flutterwave.com/pay/phase7"));
        org.mockito.Mockito.doNothing().when(mtnMomoClient).requestToPay(any(), any(), any(), any(), any());
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
    }

    @Test
    void mtnCallbackTemporaryOutageKeepsPending() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutMtn(scheme);
        when(mtnMomoClient.getRequestToPayStatus(any()))
                .thenThrow(new BusinessException("PAYMENT_PROVIDER_ERROR", "Could not verify"));
        mockMvc.perform(post("/api/v1/public/billing/mtn/callback/" + paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"" + paymentId + "\",\"status\":\"SUCCESSFUL\"}"))
                .andExpect(status().isOk());
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.PENDING);
        assertTrial(scheme);
    }

    @Test
    void flutterwaveWebhookTemporaryOutageKeepsPending() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutCard(scheme);
        when(flutterwaveClient.verifyByTransactionId(any())).thenReturn(null);
        when(flutterwaveClient.verifyByReference(any())).thenReturn(null);
        mockMvc.perform(post("/api/v1/public/billing/flutterwave/webhook")
                        .header("verif-hash", FLW_HASH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody(paymentId)))
                .andExpect(status().isOk());
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.PENDING);
        assertTrial(scheme);
    }

    @Test
    void confirmedFailedMapsToFailed() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutMtn(scheme);
        when(mtnMomoClient.getRequestToPayStatus(any())).thenReturn(MtnCollectionStatus.FAILED);
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.message").value(BillingService.FAILED_STATUS_MESSAGE))
                .andExpect(jsonPath("$.data.verificationUnavailable").value(false));
    }

    @Test
    void confirmedCanceledMapsToCanceled() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutCard(scheme);
        when(flutterwaveClient.verifyByReference(any()))
                .thenReturn(new VerifiedTransaction(
                        "cancelled", "ouwealth-sub-" + paymentId, "RWF", new BigDecimal("2000.0000"), "7"));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.message").value(BillingService.CANCELED_STATUS_MESSAGE));
    }

    @Test
    void lateSuccessAfterTemporaryOutageActivates() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        UUID paymentId = checkoutMtn(scheme);
        when(mtnMomoClient.getRequestToPayStatus(any()))
                .thenThrow(new BusinessException("PAYMENT_PROVIDER_ERROR", "timeout"))
                .thenReturn(MtnCollectionStatus.SUCCESSFUL);
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.verificationUnavailable").value(true))
                .andExpect(jsonPath("$.data.message").value(BillingService.VERIFY_UNAVAILABLE_MESSAGE));
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        assertThat(subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void reconciliationAndWebhookActivateOnce() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        UUID paymentId = checkoutCard(scheme);
        agePending(paymentId, Instant.now().minusSeconds(180));
        when(flutterwaveClient.verifyByReference(any()))
                .thenReturn(new VerifiedTransaction(
                        "successful", "ouwealth-sub-" + paymentId, "RWF", new BigDecimal("2000.0000"), "88"));
        reconciliationService.reconcileOne(paymentId);
        Instant ends = subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getCurrentPeriodEndsAt();
        mockMvc.perform(post("/api/v1/public/billing/flutterwave/webhook")
                        .header("verif-hash", FLW_HASH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody(paymentId)))
                .andExpect(status().isOk());
        assertThat(subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getCurrentPeriodEndsAt())
                .isEqualTo(ends);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.SUCCESS);
    }

    @Test
    void reconciliationVerifiesPendingWithoutLedgerWrite() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        long ledgerBefore = ledgerEntryRepository.countByCooperativeId(scheme.id());
        UUID paymentId = checkoutMtn(scheme);
        agePending(paymentId, Instant.now().minusSeconds(180));
        when(mtnMomoClient.getRequestToPayStatus(any())).thenReturn(MtnCollectionStatus.SUCCESSFUL);
        reconciliationService.reconcileEligiblePendingPayments();
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.SUCCESS);
        assertThat(ledgerEntryRepository.countByCooperativeId(scheme.id())).isEqualTo(ledgerBefore);
    }

    @Test
    void crossCooperativePaymentAccessDenied() throws Exception {
        Scheme a = createSchemeWithPresident();
        Scheme b = createSchemeWithPresident();
        UUID paymentB = checkoutMtn(b);
        mockMvc.perform(get("/api/v1/cooperatives/" + a.id() + "/billing/payments/" + paymentB)
                        .header("Authorization", "Bearer " + a.presidentToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/cooperatives/" + b.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + a.presidentToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanReadCrossCooperativePayment() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutMtn(scheme);
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments/" + paymentId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(paymentId.toString()));
    }

    @Test
    void maliciousTxRefDoesNotActivateWrongPayment() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutCard(scheme);
        UUID otherId = UUID.randomUUID();
        when(flutterwaveClient.verifyByTransactionId(any()))
                .thenReturn(new VerifiedTransaction(
                        "successful", "ouwealth-sub-" + otherId, "RWF", new BigDecimal("2000.0000"), "1"));
        mockMvc.perform(post("/api/v1/public/billing/flutterwave/webhook")
                        .header("verif-hash", FLW_HASH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"charge.completed","data":{"id":1,"tx_ref":"ouwealth-sub-%s","status":"successful","currency":"RWF","amount":2000}}
                                """.formatted(paymentId)))
                .andExpect(status().isOk());
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionPaymentStatus.PENDING);
        assertTrial(scheme);
    }

    @Test
    void plansReadinessExposesNoSecretsAndMsisdnAbsentFromHistory() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        UUID paymentId = checkoutMtn(scheme);
        String plansBody = mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/plans")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mtnCheckoutAvailable").value(true))
                .andExpect(jsonPath("$.data.cardCheckoutAvailable").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(plansBody).doesNotContain(MTN_KEY, FLW_SECRET, FLW_HASH, "api-key", "secret-key");

        String history = mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(paymentId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(history).doesNotContain("payerMsisdn", "payer_msisdn", "25078");
        assertThat(history.toLowerCase()).doesNotContain(MTN_KEY.toLowerCase());
    }

    @Test
    void disabledProviderReportedUnavailable() throws Exception {
        when(mtnMomoClient.isConfigured()).thenReturn(false);
        when(flutterwaveClient.isConfigured()).thenReturn(false);
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/plans")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.mtnCheckoutAvailable").value(false))
                .andExpect(jsonPath("$.data.cardCheckoutAvailable").value(false));
    }

    @Test
    void malformedMtnCallbackDoesNotExposeStack() throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/billing/mtn/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("NullPointerException", "at rw.terimbere", "stackTrace");
    }

    private UUID checkoutMtn(Scheme scheme) throws Exception {
        String body = mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0781234567"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).path("data").path("paymentId").asText());
    }

    private UUID checkoutCard(Scheme scheme) throws Exception {
        String body = mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).path("data").path("paymentId").asText());
    }

    private void agePending(UUID paymentId, Instant initiatedAt) {
        SubscriptionPayment payment = paymentRepository.findById(paymentId).orElseThrow();
        payment.setInitiatedAt(initiatedAt);
        paymentRepository.saveAndFlush(payment);
    }

    private void assertTrial(Scheme scheme) {
        assertThat(subscriptionRepository.findByCooperativeId(scheme.id()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
    }

    private void expireTrial(UUID cooperativeId) {
        CooperativeSubscription subscription =
                subscriptionRepository.findByCooperativeId(cooperativeId).orElseThrow();
        Instant ended = Instant.now().minusSeconds(60);
        subscription.setStatus(SubscriptionStatus.TRIAL);
        subscription.setTrialEndsAt(ended);
        if (subscription.getTrialStartedAt() == null) {
            subscription.setTrialStartedAt(ended.minusSeconds(86_400));
        }
        subscriptionRepository.saveAndFlush(subscription);
    }

    private String webhookBody(UUID paymentId) {
        return """
                {"event":"charge.completed","data":{"id":88,"tx_ref":"ouwealth-sub-%s","status":"successful","currency":"RWF","amount":2000}}
                """.formatted(paymentId);
    }

    private Scheme createSchemeWithPresident() throws Exception {
        String name = "P7Bill " + UUID.randomUUID().toString().substring(0, 8);
        UUID coopId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn());
        String username = "p7pres_" + UUID.randomUUID().toString().substring(0, 8);
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
}
