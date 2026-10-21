package rw.terimbere.csams.modules.subscription;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.shared.exceptions.SubscriptionInactiveException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubscriptionEntitlementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
    }

    @Test
    void trialBeforeEnd_presidentCanWrite() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        postMember(scheme.id(), scheme.presidentToken()).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TRIAL"))
                .andExpect(jsonPath("$.data.storedStatus").value("TRIAL"))
                .andExpect(jsonPath("$.data.effectiveStatus").value("TRIAL"))
                .andExpect(jsonPath("$.data.writeAllowed").value(true))
                .andExpect(jsonPath("$.data.usable").value(true));
    }

    @Test
    void trialAfterEnd_blocksWritesAllowsReads() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());

        expectInactiveWrite(postMember(scheme.id(), scheme.presidentToken()), scheme.id(), "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/loans")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(put("/api/v1/cooperatives/" + scheme.id() + "/contributions/period")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/fines")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(put("/api/v1/cooperatives/" + scheme.id() + "/settings")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Africa/Kigali\",\"locale\":\"en\"}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/investments")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/payouts/preview")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/shares/purchases")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/social-fund/contributions")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/transactions")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/special-campaigns")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                scheme.id(),
                "EXPIRED");

        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/members")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/loans")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/contributions")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id())
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storedStatus").value("TRIAL"))
                .andExpect(jsonPath("$.data.effectiveStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.data.writeAllowed").value(false));
    }

    @Test
    void expiredAllowsReportExportAndLoanPreview_blocksExternalShare() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/loan-settings")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());
        expireTrial(scheme.id());

        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/members")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/reports/types")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/reports/export")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .header("Accept", "application/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"MEMBERS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-09-16"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));

        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/loans/repayment-preview")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300000.0000,
                                  "termMonths": 5,
                                  "referenceDate": "2026-01-15"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleFinalized").value(false));

        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/reports/share-whatsapp")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"MEMBERS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-09-16",
                                  "recipientPhone":"0788123456"
                                }
                                """)),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(
                mockMvc.perform(post("/api/v1/cooperatives/"
                                + scheme.id()
                                + "/loans/"
                                + UUID.randomUUID()
                                + "/schedule/share-whatsapp")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"0788123456\"}")),
                scheme.id(),
                "EXPIRED");
        expectInactiveWrite(postMember(scheme.id(), scheme.presidentToken()), scheme.id(), "EXPIRED");
        postMember(scheme.id(), superAdminToken).andExpect(status().isOk());
    }

    @Test
    void activeWithinPeriod_writeAllowed_andExpiredPeriodBlocks() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        setSubscription(scheme.id(), SubscriptionStatus.ACTIVE, sub -> {
            sub.setBillingCycle(SubscriptionBillingCycle.MONTHLY);
            sub.setCurrentPeriodStartedAt(Instant.now().minusSeconds(60));
            sub.setCurrentPeriodEndsAt(Instant.now().plusSeconds(86_400));
        });
        postMember(scheme.id(), scheme.presidentToken()).andExpect(status().isOk());

        setSubscription(scheme.id(), SubscriptionStatus.ACTIVE, sub -> {
            sub.setCurrentPeriodEndsAt(Instant.now().minusSeconds(5));
        });
        expectInactiveWrite(postMember(scheme.id(), scheme.presidentToken()), scheme.id(), "EXPIRED");
    }

    @Test
    void pastDueGrace_thenExpiredAfterPastDueUntil() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        setSubscription(scheme.id(), SubscriptionStatus.PAST_DUE, sub -> {
            sub.setPastDueUntil(Instant.now().plusSeconds(86_400));
        });
        postMember(scheme.id(), scheme.presidentToken()).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + scheme.id() + "/subscription")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(jsonPath("$.data.effectiveStatus").value("PAST_DUE"))
                .andExpect(jsonPath("$.data.writeAllowed").value(true));

        setSubscription(scheme.id(), SubscriptionStatus.PAST_DUE, sub -> {
            sub.setPastDueUntil(Instant.now().minusSeconds(5));
        });
        expectInactiveWrite(postMember(scheme.id(), scheme.presidentToken()), scheme.id(), "EXPIRED");
    }

    @Test
    void canceledBeforeAndAfterPeriodEnd() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        setSubscription(scheme.id(), SubscriptionStatus.CANCELED, sub -> {
            sub.setCanceledAt(Instant.now().minusSeconds(60));
            sub.setCurrentPeriodEndsAt(Instant.now().plusSeconds(86_400));
        });
        postMember(scheme.id(), scheme.presidentToken()).andExpect(status().isOk());

        setSubscription(scheme.id(), SubscriptionStatus.CANCELED, sub -> {
            sub.setCurrentPeriodEndsAt(Instant.now().minusSeconds(5));
        });
        expectInactiveWrite(postMember(scheme.id(), scheme.presidentToken()), scheme.id(), "EXPIRED");
    }

    @Test
    void storedExpiredAndNone_blockWrites() throws Exception {
        Scheme expired = createSchemeWithPresident();
        setSubscription(expired.id(), SubscriptionStatus.EXPIRED, sub -> {});
        expectInactiveWrite(postMember(expired.id(), expired.presidentToken()), expired.id(), "EXPIRED");

        String noneName = "None Gate " + UUID.randomUUID().toString().substring(0, 8);
        UUID noneId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                noneName, "\"subscriptionInitialization\":\"NONE\"")))
                .andExpect(status().isOk())
                .andReturn());
        President president = assignPresident(noneId);
        expectInactiveWrite(postMember(noneId, president.token()), noneId, "NONE");
        mockMvc.perform(get("/api/v1/cooperatives/" + noneId + "/members")
                        .header("Authorization", "Bearer " + president.token()))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminBypassesExpiredCooperativeWrites() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());
        postMember(scheme.id(), superAdminToken).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/cooperatives/" + scheme.id() + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void perCooperativeEntitlement_activeA_expiredB() throws Exception {
        Scheme active = createSchemeWithPresident();
        String noneName = "Expired B " + UUID.randomUUID().toString().substring(0, 8);
        UUID expiredId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(noneName)))
                .andExpect(status().isOk())
                .andReturn());
        mockMvc.perform(post("/api/v1/cooperatives/" + expiredId + "/administrators")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(active.presidentUserId())))
                .andExpect(status().isOk());
        expireTrial(expiredId);
        String token = loginAccessToken(active.presidentUsername(), active.presidentPassword());

        postMember(active.id(), token).andExpect(status().isOk());
        expectInactiveWrite(postMember(expiredId, token), expiredId, "EXPIRED");
        mockMvc.perform(get("/api/v1/cooperatives/" + expiredId + "/members")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void authAndProfileRemainAvailableWhenSubscriptionExpired() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        expireTrial(scheme.id());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(scheme.presidentUsername(), scheme.presidentPassword())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + scheme.presidentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(scheme.presidentUsername()));

        mockMvc.perform(patch("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + scheme.presidentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s",
                                  "email":"%s",
                                  "firstName":"Pat",
                                  "lastName":"President"
                                }
                                """.formatted(scheme.presidentUsername(), scheme.presidentUsername() + "@test.local")))
                .andExpect(status().isOk());
    }

    @Test
    void membershipPermissionStillEnforcedOnUsableSubscription() throws Exception {
        Scheme scheme = createSchemeWithPresident();
        MvcResult member = postMember(scheme.id(), scheme.presidentToken()).andExpect(status().isOk()).andReturn();
        String memberUsername = objectMapper
                .readTree(member.getResponse().getContentAsString())
                .path("data")
                .path("username")
                .asText();
        String memberPassword = objectMapper
                .readTree(member.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();
        String memberToken = loginAccessToken(memberUsername, memberPassword);

        mockMvc.perform(post("/api/v1/cooperatives/" + scheme.id() + "/members")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Nope",
                                  "lastName":"Member",
                                  "username":"blocked_%s",
                                  "email":"blocked_%s@test.local"
                                }
                                """.formatted(
                                UUID.randomUUID().toString().substring(0, 8),
                                UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", not(402)))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    private void expectInactiveWrite(org.springframework.test.web.servlet.ResultActions actions, UUID cooperativeId, String status)
            throws Exception {
        actions.andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(SubscriptionInactiveException.CODE))
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.details.cooperativeId").value(cooperativeId.toString()))
                .andExpect(jsonPath("$.details.subscriptionStatus").value(status));
    }

    private org.springframework.test.web.servlet.ResultActions postMember(UUID cooperativeId, String token)
            throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "firstName":"Jane",
                          "lastName":"Doe",
                          "username":"mem_%s",
                          "email":"mem_%s@test.local",
                          "roleInCooperative":"MEMBER"
                        }
                        """.formatted(suffix, suffix)));
    }

    private Scheme createSchemeWithPresident() throws Exception {
        String name = "Entitlement " + UUID.randomUUID().toString().substring(0, 8);
        UUID coopId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn());
        President president = assignPresident(coopId);
        return new Scheme(coopId, president.userId(), president.username(), president.password(), president.token());
    }

    private President assignPresident(UUID coopId) throws Exception {
        String username = "pres_" + UUID.randomUUID().toString().substring(0, 8);
        String password = "President1!";
        MvcResult assigned = mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/administrators")
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
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper
                .readTree(assigned.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        return new President(userId, username, password, loginAccessToken(username, password));
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

    private record Scheme(UUID id, UUID presidentUserId, String presidentUsername, String presidentPassword, String presidentToken) {}

    private record President(UUID userId, String username, String password, String token) {}
}
