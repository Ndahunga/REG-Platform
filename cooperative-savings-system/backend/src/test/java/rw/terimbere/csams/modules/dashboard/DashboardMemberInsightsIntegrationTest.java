package rw.terimbere.csams.modules.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardMemberInsightsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private String memberUsername;
    private String memberPassword;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Member Insights Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn();
        cooperativeId = UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        memberUsername = "mins_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Plain",
                                  "lastName":"Member",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """.formatted(memberUsername, memberUsername)))
                .andExpect(status().isOk())
                .andReturn();
        var memberData = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        UUID memberUserId = UUID.fromString(memberData.path("userId").asText());
        memberPassword = memberData.path("temporaryPassword").asText();
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 1);
    }

    @Test
    void memberInsights_emptyCooperative_returnsEmptyListsForLeadership() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/member-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topContributors").isArray())
                .andExpect(jsonPath("$.data.topContributors").isEmpty())
                .andExpect(jsonPath("$.data.fineFollowUp").isArray())
                .andExpect(jsonPath("$.data.fineFollowUp").isEmpty())
                .andExpect(jsonPath("$.data.overdueLoans").isArray())
                .andExpect(jsonPath("$.data.overdueLoans").isEmpty())
                .andExpect(jsonPath("$.data.period.start").exists())
                .andExpect(jsonPath("$.data.period.end").exists());
    }

    @Test
    void memberInsights_memberRoleForbidden() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/member-insights")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberInsights_unauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/member-insights"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void memberInsights_nonMemberForbidden() throws Exception {
        String outsiderMember = "outmm_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult other = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "Other MI " + UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isOk())
                .andReturn();
        UUID otherCoop = UUID.fromString(objectMapper
                .readTree(other.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        MvcResult memberReg = mockMvc.perform(post("/api/v1/cooperatives/" + otherCoop + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Other",
                                  "lastName":"Member",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """.formatted(outsiderMember, outsiderMember)))
                .andExpect(status().isOk())
                .andReturn();
        String tempPassword = objectMapper
                .readTree(memberReg.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();
        String outsiderToken = loginAccessToken(outsiderMember, tempPassword);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/member-insights")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
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
