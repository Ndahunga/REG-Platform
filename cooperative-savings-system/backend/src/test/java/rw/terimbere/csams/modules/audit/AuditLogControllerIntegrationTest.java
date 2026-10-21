package rw.terimbere.csams.modules.audit;

import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

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
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.shared.auditing.AuditableAction;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditService auditService;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID otherCooperativeId;
    private UUID auditId;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(login.getResponse().getContentAsString()).path("data");
        superAdminToken = data.path("accessToken").asText();
        UUID actorId = UUID.fromString(data.path("user").path("id").asText());

        cooperativeId = createCooperative("Audit Coop " + UUID.randomUUID().toString().substring(0, 8));
        otherCooperativeId = createCooperative("Other Audit " + UUID.randomUUID().toString().substring(0, 8));

        auditService.record(
                actorId,
                cooperativeId,
                AuditableAction.UPDATE,
                "Cooperative",
                cooperativeId,
                null,
                "{\"name\":\"audit-test\"}",
                "127.0.0.1",
                "test-agent");
        auditService.record(
                actorId,
                otherCooperativeId,
                AuditableAction.UPDATE,
                "Cooperative",
                otherCooperativeId,
                null,
                "{\"name\":\"other\"}",
                "127.0.0.1",
                "test-agent");
    }

    @Test
    void listIsIsolatedByCooperativeAndGetByIdWorks() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/audit-logs")
                        .param("action", AuditableAction.UPDATE.name())
                        .param("entityType", "Cooperative")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.content[0].userName").value("System Administrator"))
                .andExpect(jsonPath("$.data.content[0].entityLabel").value(org.hamcrest.Matchers.containsString("Audit Coop")))
                .andReturn();

        var content = objectMapper
                .readTree(list.getResponse().getContentAsString())
                .path("data")
                .path("content");
        for (var node : content) {
            org.assertj.core.api.Assertions.assertThat(node.path("cooperativeId").asText())
                    .isEqualTo(cooperativeId.toString());
        }

        auditId = UUID.fromString(content.get(0).path("id").asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/audit-logs/" + auditId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(auditId.toString()))
                .andExpect(jsonPath("$.data.cooperativeId").value(cooperativeId.toString()))
                .andExpect(jsonPath("$.data.userName").value("System Administrator"))
                .andExpect(jsonPath("$.data.entityLabel").value(org.hamcrest.Matchers.containsString("Audit Coop")));

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/audit-logs/" + auditId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isNotFound());
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
