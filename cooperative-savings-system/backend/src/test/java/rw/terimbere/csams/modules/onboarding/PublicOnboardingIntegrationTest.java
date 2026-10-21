package rw.terimbere.csams.modules.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.audit.repository.AuditLogRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.auditing.AuditableAction;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicOnboardingIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CooperativeRepository cooperativeRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void publicOnboarding_createsPresidentTrialAndIssuesWorkingTokens() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "onb_" + suffix;
        String registrationNumber = "RCA/ONB/" + suffix.toUpperCase();

        MvcResult created = mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody(username, username + "@test.local", registrationNumber, "SignupPass1!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andExpect(jsonPath("$.data.user.roles", hasItem("PRESIDENT")))
                .andExpect(jsonPath("$.data.user.roles", hasItem("MEMBER")))
                .andExpect(jsonPath("$.data.user.roles", not(hasItem("SUPER_ADMIN"))))
                .andExpect(jsonPath("$.data.user.cooperativeIds").isArray())
                .andReturn();

        JsonNode data = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        String accessToken = data.path("accessToken").asText();
        UUID userId = UUID.fromString(data.path("user").path("id").asText());
        UUID coopId = UUID.fromString(data.path("user").path("cooperativeIds").get(0).asText());

        User user = userRepository.findByIdAndDeletedFalse(userId).orElseThrow();
        assertThat(user.getRoleCodes()).contains("PRESIDENT", "MEMBER").doesNotContain("SUPER_ADMIN");

        var cooperative = cooperativeRepository.findByIdAndDeletedFalse(coopId).orElseThrow();
        assertThat(cooperative.getOnboardingState()).isEqualTo(CooperativeOnboardingState.COMPLETE);
        assertThat(cooperative.getStatus()).isEqualTo(CooperativeStatus.ACTIVE);
        assertThat(cooperative.getCurrency()).isEqualTo("RWF");

        CooperativeMembership membership =
                membershipRepository.findByCooperativeIdAndUserId(coopId, userId).orElseThrow();
        assertThat(membership.getRoleInCooperative()).isEqualTo("PRESIDENT");
        assertThat(membership.getMembershipStatus()).isEqualTo("ACTIVE");

        var subscription = subscriptionRepository.findByCooperativeId(coopId).orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(subscription.getTrialEndsAt())
                .isEqualTo(subscription.getTrialStartedAt().atZone(KIGALI).plusMonths(4).toInstant());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.cooperativeIds", hasItem(coopId.toString())));

        mockMvc.perform(get("/api/v1/cooperatives/" + coopId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("COMPLETE"));

        mockMvc.perform(get("/api/v1/cooperatives/" + coopId + "/subscription")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TRIAL"));

        assertThat(auditLogRepository.findAll())
                .anyMatch((log) -> AuditableAction.CREATE.name().equals(log.getAction())
                        && "User".equals(log.getEntityType())
                        && userId.equals(log.getEntityId())
                        && userId.equals(log.getUserId()));
        assertThat(auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                        coopId, AuditableAction.COOPERATIVE_CREATE.name()))
                .hasSize(1);
        assertThat(auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                        coopId, AuditableAction.ROLE_ASSIGN.name()))
                .hasSize(1);
        assertThat(auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                        coopId, AuditableAction.SUBSCRIPTION_INIT.name()))
                .hasSize(1);
        assertThat(auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(
                        coopId, AuditableAction.SUBSCRIPTION_TRIAL_START.name()))
                .hasSize(1);
        assertThat(created.getResponse().getContentAsString()).doesNotContain("SignupPass1!");
    }

    @Test
    void publicOnboarding_ignoresAttemptedRoleAndSubscriptionOverrides() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "ovr_" + suffix;
        String registrationNumber = "RCA/OVR/" + suffix.toUpperCase();
        String body =
                """
                {
                  "cooperative": {
                    "name":"Override Scheme %s",
                    "registrationNumber":"%s",
                    "contactEmail":"%s@test.local",
                    "contactPhone":"0781234567",
                    "financialYearStartMonth":1,
                    "monthlyContributionAmount":1000,
                    "contributionDueDay":1,
                    "operatingModel":"ACCUMULATING",
                    "registrationDate":"2024-01-15",
                    "status":"ARCHIVED",
                    "onboardingState":"AWAITING_PRESIDENT",
                    "subscriptionInitialization":"NONE",
                    "trialEndsAt":"2099-01-01T00:00:00Z"
                  },
                  "creator": {
                    "username":"%s",
                    "email":"%s@test.local",
                    "password":"SignupPass1!",
                    "firstName":"Over",
                    "lastName":"Ride",
                    "role":"SUPER_ADMIN",
                    "roles":["SUPER_ADMIN"]
                  }
                }
                """
                        .formatted(suffix, registrationNumber, suffix, username, username);

        MvcResult created = mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.roles", hasItem("PRESIDENT")))
                .andExpect(jsonPath("$.data.user.roles", not(hasItem("SUPER_ADMIN"))))
                .andReturn();

        UUID coopId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("user")
                .path("cooperativeIds")
                .get(0)
                .asText());
        var cooperative = cooperativeRepository.findByIdAndDeletedFalse(coopId).orElseThrow();
        assertThat(cooperative.getStatus()).isEqualTo(CooperativeStatus.ACTIVE);
        assertThat(cooperative.getOnboardingState()).isEqualTo(CooperativeOnboardingState.COMPLETE);
        assertThat(subscriptionRepository.findByCooperativeId(coopId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
    }

    @Test
    void publicOnboarding_duplicateUsernameRejected() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody(
                                "superadmin",
                                "new-onboard@test.local",
                                "RCA/DUPU/" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "SignupPass1!")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already exists"));
    }

    @Test
    void publicOnboarding_duplicateEmailRejected() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody(
                                "dupem_" + suffix,
                                "superadmin@terimbere.local",
                                "RCA/DUPE/" + suffix.toUpperCase(),
                                "SignupPass1!")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void publicOnboarding_duplicateRegistrationNumberRejected() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String registrationNumber = "RCA/DUPR/" + suffix.toUpperCase();
        mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody("first_" + suffix, "first_" + suffix + "@test.local", registrationNumber, "SignupPass1!")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody("second_" + suffix, "second_" + suffix + "@test.local", registrationNumber, "SignupPass1!")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Registration number already in use"));
    }

    @Test
    void publicOnboarding_invalidPasswordRejected() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody("short_" + suffix, "short_" + suffix + "@test.local", "RCA/SH/" + suffix.toUpperCase(), "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacyAuthSignup_stillCreatesMemberWithoutCooperative() throws Exception {
        String username = "su" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "password":"SignupPass1!",
                                  "firstName":"Self",
                                  "lastName":"Signup"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.roles", hasItem("MEMBER")))
                .andExpect(jsonPath("$.data.user.roles", not(hasItem("PRESIDENT"))));
        User user = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username).orElseThrow();
        assertThat(membershipRepository.findByUserIdAndMembershipStatus(user.getId(), "ACTIVE")).isEmpty();
    }

    @Test
    void superAdminCreate_stillWorksAlongsidePublicOnboarding() throws Exception {
        String superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "Flow B Coop " + UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("AWAITING_PRESIDENT"));
    }

    private static String onboardingBody(String username, String email, String registrationNumber, String password) {
        return """
                {
                  "cooperative": {
                    "name":"Public Scheme %s",
                    "registrationNumber":"%s",
                    "contactEmail":"%s",
                    "contactPhone":"0781234567",
                    "address":"Kigali",
                    "currency":"RWF",
                    "financialYearStartMonth":1,
                    "monthlyContributionAmount":5000,
                    "contributionDueDay":5,
                    "operatingModel":"ACCUMULATING",
                    "registrationDate":"2024-01-15"
                  },
                  "creator": {
                    "username":"%s",
                    "email":"%s",
                    "password":"%s",
                    "firstName":"Pat",
                    "lastName":"President",
                    "phone":"0781112233"
                  }
                }
                """
                .formatted(username, registrationNumber, email, username, email, password);
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
}
