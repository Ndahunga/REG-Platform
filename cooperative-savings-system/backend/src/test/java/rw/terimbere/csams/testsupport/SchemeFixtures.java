package rw.terimbere.csams.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

/** Builds Saving Schemes with members and officers through the real API, for the integration tests of Phase 4. */
public final class SchemeFixtures {

    public record Actor(UUID id, String username, String token) {}

    /** A scheme with two payers, a President (fund authority) and two accountants (contribution write). */
    public record Scheme(UUID id, Actor memberA, Actor memberB, Actor president, Actor accountantA, Actor accountantB) {}

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final String superAdminToken;

    public SchemeFixtures(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc) throws Exception {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.superAdminToken = login("superadmin", "ChangeMe@123!");
    }

    public String superAdminToken() {
        return superAdminToken;
    }

    public UUID superAdminId() {
        return jdbc.queryForObject("select id from users where username = 'superadmin'", UUID.class);
    }

    public Scheme scheme(String model) throws Exception {
        return scheme(model, 1);
    }

    public Scheme scheme(String model, int dueDay) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "P4 " + UUID.randomUUID().toString().substring(0, 8), "1000.0000", dueDay, model)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(200);
        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText());
        return new Scheme(
                id,
                register(id, "MEMBER"),
                register(id, "MEMBER"),
                register(id, "PRESIDENT"),
                register(id, "ACCOUNTANT"),
                register(id, "ACCOUNTANT"));
    }

    public Actor register(UUID scheme, String role) throws Exception {
        String username = "p4_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Phase","lastName":"Four","username":"%s","email":"%s@test.local","roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("register " + role).isEqualTo(200);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Actor(UUID.fromString(data.path("userId").asText()), username, login(username, data.path("temporaryPassword").asText()));
    }

    public String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
}
