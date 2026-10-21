package rw.terimbere.csams.modules.cooperative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.audit.entity.AuditLog;
import rw.terimbere.csams.modules.audit.repository.AuditLogRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.auditing.AuditableAction;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CooperativeOnboardingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CooperativeRepository cooperativeRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private String superAdminToken;
    private String memberToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        Role memberRole = roleRepository.findByCode("MEMBER").orElseThrow();
        User memberUser = userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse("onboard_member")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("onboard_member")
                        .email("onboard_member@test.local")
                        .passwordHash(passwordEncoder.encode("Password1!"))
                        .firstName("Onboard")
                        .lastName("Member")
                        .accountStatus(AccountStatus.ACTIVE)
                        .roles(new HashSet<>(Set.of(memberRole)))
                        .build()));
        memberUser.setPasswordHash(passwordEncoder.encode("Password1!"));
        memberUser.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(memberUser);
        memberToken = loginAccessToken("onboard_member", "Password1!");
    }

    @Test
    void createWithoutNewFields_defaultsToTrialAndAwaitingPresident() throws Exception {
        String name = "Legacy Client Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.onboardingState").value("AWAITING_PRESIDENT"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();

        UUID coopId = idFrom(created);
        mockMvc.perform(get("/api/v1/cooperatives/" + coopId + "/subscription")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TRIAL"))
                .andExpect(jsonPath("$.data.trialStartedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.trialEndsAt").isNotEmpty());

        assertThat(subscriptionRepository.countByCooperativeId(coopId)).isEqualTo(1);
        assertThat(auditActions(coopId, AuditableAction.SUBSCRIPTION_INIT)).hasSize(1);
        assertThat(auditActions(coopId, AuditableAction.SUBSCRIPTION_TRIAL_START)).hasSize(1);
        assertThat(auditActions(coopId, AuditableAction.COOPERATIVE_CREATE).get(0).getNewValues())
                .contains("AWAITING_PRESIDENT")
                .contains("START_TRIAL");
    }

    @Test
    void createWithExplicitStartTrial_initializesTrialOnce() throws Exception {
        String name = "Explicit Trial Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                name, "\"subscriptionInitialization\":\"START_TRIAL\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("AWAITING_PRESIDENT"))
                .andReturn();

        UUID coopId = idFrom(created);
        assertThat(subscriptionRepository.countByCooperativeId(coopId)).isEqualTo(1);
        assertThat(subscriptionRepository.findByCooperativeId(coopId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(auditActions(coopId, AuditableAction.SUBSCRIPTION_INIT)).hasSize(1);
        assertThat(auditActions(coopId, AuditableAction.SUBSCRIPTION_TRIAL_START)).hasSize(1);
    }

    @Test
    void createWithNone_doesNotStartTrial() throws Exception {
        String name = "None Sub Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                name, "\"subscriptionInitialization\":\"NONE\"")))
                .andExpect(status().isOk())
                .andReturn();

        UUID coopId = idFrom(created);
        mockMvc.perform(get("/api/v1/cooperatives/" + coopId + "/subscription")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"));
        assertThat(auditActions(coopId, AuditableAction.SUBSCRIPTION_TRIAL_START)).isEmpty();
        assertThat(auditActions(coopId, AuditableAction.SUBSCRIPTION_INIT)).hasSize(1);
    }

    @Test
    void createWithNewPresident_completesOnboardingAndStartsTrial() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "pres_" + suffix;
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                "President Now Coop " + suffix,
                                """
                                "subscriptionInitialization":"START_TRIAL",
                                "president":{
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "firstName":"Pat",
                                  "lastName":"President"
                                }
                                """
                                        .formatted(username, username))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("COMPLETE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();

        UUID coopId = idFrom(created);
        User president = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username).orElseThrow();
        CooperativeMembership membership =
                membershipRepository.findByCooperativeIdAndUserId(coopId, president.getId()).orElseThrow();
        assertThat(membership.getRoleInCooperative()).isEqualTo("PRESIDENT");
        assertThat(president.getRoles().stream().anyMatch(role -> "PRESIDENT".equals(role.getCode()))).isTrue();
        assertThat(subscriptionRepository.findByCooperativeId(coopId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(auditActions(coopId, AuditableAction.ROLE_ASSIGN)).hasSize(1);
        assertThat(auditActions(coopId, AuditableAction.COOPERATIVE_ONBOARDING_CHANGE)).isEmpty();
    }

    @Test
    void createWithExistingPresident_completesOnboarding() throws Exception {
        String firstName = "Host Coop " + UUID.randomUUID().toString().substring(0, 8);
        UUID hostId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(firstName)))
                .andExpect(status().isOk())
                .andReturn());

        String username = "exist_pres_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + hostId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Existing",
                                  "lastName":"Leader",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());

        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                "Existing Pres Coop " + UUID.randomUUID().toString().substring(0, 8),
                                "\"president\":{\"userId\":\"%s\"}".formatted(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("COMPLETE"))
                .andReturn();

        UUID coopId = idFrom(created);
        CooperativeMembership membership =
                membershipRepository.findByCooperativeIdAndUserId(coopId, userId).orElseThrow();
        assertThat(membership.getRoleInCooperative()).isEqualTo("PRESIDENT");
    }

    @Test
    void createWithoutPresident_staysAwaitingPresident() throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                "Defer Pres Coop " + UUID.randomUUID().toString().substring(0, 8),
                                "\"subscriptionInitialization\":\"START_TRIAL\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("AWAITING_PRESIDENT"));
    }

    @Test
    void assignPresidentLater_transitionsAwaitingToCompleteWithoutChangingStatus() throws Exception {
        String name = "Later Pres Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("AWAITING_PRESIDENT"))
                .andReturn();
        UUID coopId = idFrom(created);

        String username = "later_pres_" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/administrators")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "firstName":"Later",
                                  "lastName":"President"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleInCooperative").value("PRESIDENT"));

        mockMvc.perform(get("/api/v1/cooperatives/" + coopId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("COMPLETE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        Cooperative cooperative = cooperativeRepository.findByIdAndDeletedFalse(coopId).orElseThrow();
        assertThat(cooperative.getStatus()).isEqualTo(CooperativeStatus.ACTIVE);
        assertThat(cooperative.getOnboardingState()).isEqualTo(CooperativeOnboardingState.COMPLETE);
        assertThat(auditActions(coopId, AuditableAction.COOPERATIVE_ONBOARDING_CHANGE)).hasSize(1);
        assertThat(auditActions(coopId, AuditableAction.ROLE_ASSIGN).get(0).getNewValues())
                .contains("COMPLETE");
    }

    @Test
    void presidentAssignmentFailure_doesNotMarkComplete() throws Exception {
        String name = "Assign Fail Coop " + UUID.randomUUID().toString().substring(0, 8);
        UUID coopId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/administrators")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/cooperatives/" + coopId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingState").value("AWAITING_PRESIDENT"));
    }

    @Test
    void createWithPresidentAssignmentFailure_rollsBackCooperative() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String registrationNumber = "RCA/FAIL/" + suffix;
        String body =
                """
                {
                  "name":"Create Fail Coop %s",
                  "currency":"RWF",
                  "monthlyContributionAmount":1000,
                  "contributionDueDay":1,
                  "financialYearStartMonth":1,
                  "registrationNumber":"%s",
                  "contactEmail":"fail-%s@test.local",
                  "contactPhone":"0781234567",
                  "operatingModel":"ACCUMULATING",
                  "registrationDate":"2024-01-15",
                  "president":{"userId":"%s"}
                }
                """
                        .formatted(suffix, registrationNumber, suffix.toLowerCase(), UUID.randomUUID());

        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        assertThat(cooperativeRepository.existsByRegistrationNumberIgnoreCaseAndDeletedFalse(registrationNumber))
                .isFalse();
    }

    @Test
    void nonSuperAdmin_cannotCreateCooperative() throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody("Nope Onboard")))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignAdministrator_stillRequiresSuperAdmin() throws Exception {
        UUID coopId = idFrom(mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "Admin Auth Coop " + UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/administrators")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"blocked_%s",
                                  "email":"blocked_%s@test.local",
                                  "firstName":"No",
                                  "lastName":"Access"
                                }
                                """.formatted(
                                UUID.randomUUID().toString().substring(0, 8),
                                UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_rejectsActiveManualInitialization() throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBodyWith(
                                "Manual Coop " + UUID.randomUUID().toString().substring(0, 8),
                                "\"subscriptionInitialization\":\"ACTIVE_MANUAL\"")))
                .andExpect(status().isBadRequest());
    }

    private UUID idFrom(MvcResult result) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return UUID.fromString(data.path("id").asText());
    }

    private List<AuditLog> auditActions(UUID cooperativeId, AuditableAction action) {
        return auditLogRepository.findByCooperativeIdAndActionOrderByCreatedAtAsc(cooperativeId, action.name());
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
