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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardInsightsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String superAdminToken;
    private UUID cooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Insights Coop " + UUID.randomUUID().toString().substring(0, 8);
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
    }

    @Test
    void insights_emptyCooperative_returnsZeros() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contributions.currentMonth").value(0.0))
                .andExpect(jsonPath("$.data.loans.issuedCountCurrentMonth").value(0))
                .andExpect(jsonPath("$.data.loans.issuedAmountCurrentMonth").value(0.0))
                .andExpect(jsonPath("$.data.loans.repaidCurrentMonth").value(0.0))
                .andExpect(jsonPath("$.data.loans.outstandingPrincipal").value(0.0))
                .andExpect(jsonPath("$.data.fines.issuedCountCurrentMonth").value(0))
                .andExpect(jsonPath("$.data.fines.collectedCurrentMonth").value(0.0))
                .andExpect(jsonPath("$.data.currency").value("RWF"));
    }

    @Test
    void insights_unauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/insights"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void insights_nonMemberForbidden() throws Exception {
        String outsiderUser = "outsider_" + UUID.randomUUID().toString().substring(0, 8);
        // Register via a second cooperative so the user is authenticated but not a member here.
        MvcResult other = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody("Other " + UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isOk())
                .andReturn();
        UUID otherCoop = UUID.fromString(objectMapper
                .readTree(other.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + otherCoop + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Out",
                                  "lastName":"Sider",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """.formatted(outsiderUser, outsiderUser)))
                .andExpect(status().isOk())
                .andReturn();
        String tempPassword = objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();
        String outsiderToken = loginAccessToken(outsiderUser, tempPassword);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/insights")
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
