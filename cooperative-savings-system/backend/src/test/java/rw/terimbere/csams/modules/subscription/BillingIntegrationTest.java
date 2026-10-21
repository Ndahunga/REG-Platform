package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.repository.SubscriptionPaymentRepository;
import rw.terimbere.csams.shared.exceptions.PaymentIntegrationUnavailableException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingIntegrationTest {

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

    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
    }

    @Test
    void plansMatchCentralPricingCatalog() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/plans")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("RWF"))
                .andExpect(jsonPath("$.data.trialMonths").value(4))
                .andExpect(jsonPath("$.data.plans[0].billingCycle").value("MONTHLY"))
                .andExpect(jsonPath("$.data.plans[0].listPrice").value(2000.0))
                .andExpect(jsonPath("$.data.plans[0].amount").value(2000.0))
                .andExpect(jsonPath("$.data.plans[0].discountPercent").value(0))
                .andExpect(jsonPath("$.data.plans[0].savings").value(0))
                .andExpect(jsonPath("$.data.plans[0].periodMonths").value(1))
                .andExpect(jsonPath("$.data.plans[1].billingCycle").value("ANNUAL"))
                .andExpect(jsonPath("$.data.plans[1].listPrice").value(24000.0))
                .andExpect(jsonPath("$.data.plans[1].amount").value(18000.0))
                .andExpect(jsonPath("$.data.plans[1].discountPercent").value(25))
                .andExpect(jsonPath("$.data.plans[1].savings").value(6000.0))
                .andExpect(jsonPath("$.data.plans[1].periodMonths").value(12));
    }

    @Test
    void subscriptionStatusReadableForTrialActiveExpiredAndNone() throws Exception {
        Scheme trial = createSchemeWithPresident();
        mockMvc.perform(get("/api/v1/cooperatives/" + trial.id() + "/subscription")
                        .header("Authorization", "Bearer " + trial.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));

        setSubscription(trial.id(), SubscriptionStatus.ACTIVE, sub -> {
            sub.setBillingCycle(SubscriptionBillingCycle.MONTHLY);
            sub.setCurrentPeriodStartedAt(Instant.now().minusSeconds(60));
            sub.setCurrentPeriodEndsAt(Instant.now().plusSeconds(86_400));
        });
        mockMvc.perform(get("/api/v1/cooperatives/" + trial.id() + "/subscription")
                        .header("Authorization", "Bearer " + trial.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveStatus").value("ACTIVE"));

        expireTrial(trial.id());
        mockMvc.perform(get("/api/v1/cooperatives/" + trial.id() + "/subscription")
                        .header("Authorization", "Bearer " + trial.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveStatus").value("EXPIRED"));

        String noneName = "None Bill " + UUID.randomUUID().toString().substring(0, 8);
        UUID noneId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                noneName, "\"subscriptionInitialization\":\"NONE\"")))
                .andExpect(status().isOk())
                .andReturn());
        mockMvc.perform(get("/api/v1/cooperatives/" + noneId + "/subscription")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveStatus").value("NONE"));
    }

    @Test
    void paymentHistoryIsScopedAndVisibleToSuperAdmin() throws Exception {
        Scheme schemeA = createSchemeWithPresident();
        Scheme schemeB = createSchemeWithPresident();
        CooperativeSubscription subA = subscriptionRepository.findByCooperativeId(schemeA.id()).orElseThrow();
        SubscriptionPayment payment = paymentRepository.saveAndFlush(SubscriptionPayment.builder()
                .subscriptionId(subA.getId())
                .cooperativeId(schemeA.id())
                .billingCycle(SubscriptionBillingCycle.ANNUAL)
                .paymentChannel(SubscriptionPaymentChannel.MTN_MOMO)
                .status(SubscriptionPaymentStatus.SUCCESS)
                .currency("RWF")
                .amount(new BigDecimal("18000.0000"))
                .provider("placeholder")
                .externalReference("SAFE-REF-1")
                .initiatedAt(Instant.now())
                .paidAt(Instant.now())
                .build());

        mockMvc.perform(get("/api/v1/cooperatives/" + schemeA.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + schemeA.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].billingCycle").value("ANNUAL"))
                .andExpect(jsonPath("$.data.content[0].paymentChannel").value("MTN_MOMO"))
                .andExpect(jsonPath("$.data.content[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].amount").value(18000.0))
                .andExpect(jsonPath("$.data.content[0].externalReference").value("SAFE-REF-1"))
                .andExpect(jsonPath("$.data.content[0].idempotencyKey").doesNotExist());

        mockMvc.perform(get("/api/v1/cooperatives/" + schemeA.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + schemeB.presidentToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/cooperatives/" + schemeA.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(payment.getId().toString()));
    }

    @Test
    void memberCanReadBillingStatusButCannotCheckout() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        Member member = addMember(scheme.id(), "MEMBER");
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/plans")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void expiredCooperativeCanStillAccessBillingApisWithoutActivating() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        long ledgerBefore = ledgerEntryRepository.countByCooperativeId(scheme.id());
        long paymentsBefore = paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id()).size();

        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/plans")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/billing/payments")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"ANNUAL","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(PaymentIntegrationUnavailableException.CODE))
                .andExpect(jsonPath("$.status").value(501))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.data.writeAllowed").value(false));
        assertThat(ledgerEntryRepository.countByCooperativeId(scheme.id())).isEqualTo(ledgerBefore);
        assertThat(paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id()).size())
                .isEqualTo(paymentsBefore);
    }

    @Test
    void mtnCheckoutIsUnavailableWhenProviderDisabled() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        long paymentsBefore = paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id()).size();
        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"MTN_MOMO","payerPhoneNumber":"0781234567"}
                                """))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(PaymentIntegrationUnavailableException.CODE));
        assertThat(paymentRepository.findByCooperativeIdOrderByCreatedAtDesc(scheme.id()).size())
                .isEqualTo(paymentsBefore);
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"));
    }

    @Test
    void accountantCanCheckoutContract_secretaryCannot() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        Member accountant = addMember(scheme.id(), "ACCOUNTANT");
        Member secretary = addMember(scheme.id(), "SECRETARY");

        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + accountant.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(PaymentIntegrationUnavailableException.CODE));

        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/billing/checkout")
                        .header("Authorization", "Bearer " + secretary.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingCycle":"MONTHLY","paymentChannel":"CARD"}
                                """))
                .andExpect(status().isForbidden());
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
        String name = "Billing " + UUID.randomUUID().toString().substring(0, 8);
        UUID coopId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn());
        String username = "bpres_" + UUID.randomUUID().toString().substring(0, 8);
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
