package rw.terimbere.csams.modules.settings;

import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CooperativeSettingsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String superAdminToken;
    private UUID cooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        cooperativeId = createCooperative("Settings Coop " + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void getCreatesDefaultsAndPutUpdates() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("Africa/Kigali"))
                .andExpect(jsonPath("$.data.locale").value("en"))
                .andExpect(jsonPath("$.data.notifyContributions").value(true))
                .andExpect(jsonPath("$.data.notifyLoans").value(true));

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timezone": "Africa/Nairobi",
                                  "locale": "rw",
                                  "notifyContributions": false,
                                  "notifyLoans": true,
                                  "notifyFines": false,
                                  "notifyPayouts": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("Africa/Nairobi"))
                .andExpect(jsonPath("$.data.locale").value("rw"))
                .andExpect(jsonPath("$.data.notifyContributions").value(false))
                .andExpect(jsonPath("$.data.notifyFines").value(false));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("Africa/Nairobi"))
                .andExpect(jsonPath("$.data.notifyContributions").value(false));
    }

    private UUID createCooperative(String name) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
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
