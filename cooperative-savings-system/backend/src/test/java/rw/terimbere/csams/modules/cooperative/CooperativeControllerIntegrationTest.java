package rw.terimbere.csams.modules.cooperative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
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
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CooperativeControllerIntegrationTest {

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
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private User memberUser;
    private String memberToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        Role memberRole = roleRepository.findByCode("MEMBER").orElseThrow();
        memberUser = userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse("coopmember_a")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("coopmember_a")
                        .email("coopmember_a@test.local")
                        .passwordHash(passwordEncoder.encode("Password1!"))
                        .firstName("Coop")
                        .lastName("Member")
                        .accountStatus(AccountStatus.ACTIVE)
                        .roles(new HashSet<>(Set.of(memberRole)))
                        .build()));
        memberUser.setPasswordHash(passwordEncoder.encode("Password1!"));
        memberUser.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(memberUser);
        memberToken = loginAccessToken("coopmember_a", "Password1!");
    }

    @Test
    void createListGetUpdateStatus_asSuperAdmin() throws Exception {
        String uniqueName = "Alpha Coop " + UUID.randomUUID().toString().substring(0, 8);

        MvcResult createResult = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(uniqueName, "5000", 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value(uniqueName))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.onboardingState").value("AWAITING_PRESIDENT"))
                .andExpect(jsonPath("$.data.currency").value("RWF"))
                .andReturn();

        UUID coopId = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("q", uniqueName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(coopId.toString()));

        mockMvc.perform(get("/api/v1/cooperatives/" + coopId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(coopId.toString()))
                .andExpect(jsonPath("$.data.name").value(uniqueName));

        mockMvc.perform(get("/api/v1/cooperatives/mine")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(patch("/api/v1/cooperatives/" + coopId + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        mockMvc.perform(patch("/api/v1/cooperatives/" + coopId + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
    }

    @Test
    void nonMember_forbiddenFromOtherCooperative() throws Exception {
        String uniqueName = "Isolated Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult createResult = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(uniqueName)))
                .andExpect(status().isOk())
                .andReturn();

        UUID coopB = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        // Member has no membership → forbidden
        mockMvc.perform(get("/api/v1/cooperatives/" + coopB)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        // Create coop A and membership for member
        MvcResult createA = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "Member Coop " + UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andReturn();
        UUID coopA = UUID.fromString(objectMapper
                .readTree(createA.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        if (membershipRepository.findByCooperativeIdAndUserId(coopA, memberUser.getId()).isEmpty()) {
            membershipRepository.save(CooperativeMembership.builder()
                    .userId(memberUser.getId())
                    .cooperativeId(coopA)
                    .membershipStatus("ACTIVE")
                    .roleInCooperative("MEMBER")
                    .build());
        }

        // Refresh token so JWT includes coop A (isolation still enforced for coop B)
        String refreshedMemberToken = loginAccessToken("coopmember_a", "Password1!");

        mockMvc.perform(get("/api/v1/cooperatives/" + coopA)
                        .header("Authorization", "Bearer " + refreshedMemberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(coopA.toString()));

        mockMvc.perform(get("/api/v1/cooperatives/" + coopB)
                        .header("Authorization", "Bearer " + refreshedMemberToken))
                .andExpect(status().isForbidden());

        MvcResult mine = mockMvc.perform(get("/api/v1/cooperatives/mine")
                        .header("Authorization", "Bearer " + refreshedMemberToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode mineData = objectMapper.readTree(mine.getResponse().getContentAsString()).path("data");
        assertThat(mineData.isArray()).isTrue();
        boolean containsA = false;
        boolean containsB = false;
        for (JsonNode node : mineData) {
            if (coopA.toString().equals(node.path("id").asText())) {
                containsA = true;
            }
            if (coopB.toString().equals(node.path("id").asText())) {
                containsB = true;
            }
        }
        assertThat(containsA).isTrue();
        assertThat(containsB).isFalse();
    }

    @Test
    void create_requiresSuperAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody("Nope")))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_rejectsInvalidPhoneAndForcesRwf() throws Exception {
        String uniqueName = "Bad Phone Coop " + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"%s",
                                  "currency":"USD",
                                  "monthlyContributionAmount":1000,
                                  "contributionDueDay":5,
                                  "financialYearStartMonth":1,
                                  "registrationNumber":"RCA/TEST/%s",
                                  "contactEmail":"badphone@test.local",
                                  "contactPhone":"12345",
                                  "operatingModel":"ACCUMULATING",
                                  "registrationDate":"2024-01-15"
                                }
                                """
                                .formatted(uniqueName, UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isBadRequest());

        String okName = "Forced RWF Coop " + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"%s",
                                  "currency":"USD",
                                  "monthlyContributionAmount":1000,
                                  "contributionDueDay":5,
                                  "financialYearStartMonth":1,
                                  "registrationNumber":"RCA/TEST/%s",
                                  "contactEmail":"rwf@test.local",
                                  "contactPhone":"0789998877",
                                  "operatingModel":"ACCUMULATING",
                                  "registrationDate":"2024-01-15"
                                }
                                """
                                .formatted(okName, UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("RWF"));
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
