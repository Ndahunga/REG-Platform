package rw.terimbere.csams.modules.rotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static rw.terimbere.csams.testsupport.PaymentScenario.NEXT;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * A rotating/retained configuration nobody has relied on yet can be deleted. One that has taken effect, or under which
 * contributions were already split, is history and stays; a newer configuration replaces it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RotationConfigurationRemovalIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    private PaymentScenario hybrid() throws Exception {
        return new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "HYBRID", 30);
    }

    private JsonNode configOf(PaymentScenario p, java.time.YearMonth month) throws Exception {
        for (JsonNode c : p.data(p.getAs(p.scheme.president(), "/rotation/configurations"))) {
            if (c.path("effectiveYear").asInt() == month.getYear() && c.path("effectiveMonth").asInt() == month.getMonthValue()) {
                return c;
            }
        }
        return null;
    }

    private MvcResult remove(PaymentScenario p, Actor who, UUID id) throws Exception {
        return mockMvc.perform(delete(p.base() + "/rotation/configurations/" + id).header("Authorization", PaymentScenario.bearer(who))).andReturn();
    }

    @Test
    void aFutureConfigurationNobodyReliedOnCanBeDeletedAndTheDeletionIsAudited() throws Exception {
        PaymentScenario p = hybrid();
        java.time.YearMonth future = NEXT.plusMonths(2);
        p.configureRotation(future, 4000, 6000);
        JsonNode before = configOf(p, future);
        assertThat(before.path("canDelete").asBoolean()).isTrue();
        UUID id = UUID.fromString(before.path("id").asText());

        MvcResult done = remove(p, p.scheme.president(), id);
        assertThat(status(done)).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(configOf(p, future)).isNull();
        assertThat(p.count("select count(*) from rotation_configurations where id = ?", id)).isZero();
        assertThat(p.count("select count(*) from audit_logs where cooperative_id = ? and action = 'ROTATION_CONFIG_DELETE' and entity_id = ?", p.id(), id)).isEqualTo(1);
        // the month is free again for a different configuration
        p.configureRotation(future, 5000, 5000);
        assertThat(configOf(p, future).path("rotatingBps").asInt()).isEqualTo(5000);
    }

    @Test
    void aConfigurationUnderWhichContributionsWereSplitCannotBeDeleted() throws Exception {
        PaymentScenario p = hybrid();
        p.configureRotation(NEXT, 4000, 6000);
        p.activateCycle(NEXT, List.of(p.payer));
        p.rows(NEXT, List.of(p.payer));
        JsonNode used = configOf(p, NEXT);
        assertThat(used.path("canDelete").asBoolean()).isFalse();
        assertThat(used.path("deletionBlockedReason").asText()).isEqualTo("ROTATION_CONFIG_IN_USE");
        UUID id = UUID.fromString(used.path("id").asText());
        MvcResult refused = remove(p, p.scheme.president(), id);
        assertThat(status(refused)).isEqualTo(409);
        assertThat(p.code(refused)).isEqualTo("ROTATION_CONFIG_IN_USE");
        assertThat(p.count("select count(*) from rotation_configurations where id = ?", id)).isEqualTo(1);
    }

    @Test
    void aConfigurationWhoseMonthHasBegunIsInForceAndCannotBeDeleted() throws Exception {
        PaymentScenario p = hybrid();
        java.time.YearMonth now = java.time.YearMonth.now(PaymentScenario.KIGALI);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into rotation_configurations (id, cooperative_id, effective_year, effective_month, rotating_bps, retained_bps, created_by, created_at)"
                + " values (?, ?, ?, ?, 4000, 6000, ?, CURRENT_TIMESTAMP)", id, p.id(), now.getYear(), now.getMonthValue(), p.scheme.president().id());
        JsonNode begun = configOf(p, now);
        assertThat(begun.path("canDelete").asBoolean()).isFalse();
        assertThat(begun.path("deletionBlockedReason").asText()).isEqualTo("ROTATION_CONFIG_IN_FORCE");
        MvcResult refused = remove(p, p.scheme.president(), id);
        assertThat(status(refused)).isEqualTo(409);
        assertThat(p.code(refused)).isEqualTo("ROTATION_CONFIG_IN_FORCE");
    }

    @Test
    void onlyThoseWhoManageTheSchemeMayDeleteAndNeverAnotherSchemesConfiguration() throws Exception {
        PaymentScenario a = hybrid();
        PaymentScenario b = hybrid();
        java.time.YearMonth future = NEXT.plusMonths(2);
        a.configureRotation(future, 4000, 6000);
        b.configureRotation(future, 4000, 6000);
        UUID ofA = UUID.fromString(configOf(a, future).path("id").asText());
        UUID ofB = UUID.fromString(configOf(b, future).path("id").asText());

        assertThat(status(remove(a, a.payer, ofA))).as("a member").isEqualTo(403);
        assertThat(status(remove(a, a.reviewer, ofA))).as("an accountant").isEqualTo(403);
        assertThat(status(remove(a, a.scheme.president(), ofB))).as("another scheme's configuration through my scheme").isEqualTo(404);
        assertThat(status(remove(b, a.scheme.president(), ofB))).as("not a member of that scheme").isEqualTo(403);
        assertThat(status(remove(a, a.scheme.president(), UUID.randomUUID()))).isEqualTo(404);
        assertThat(a.count("select count(*) from rotation_configurations where id in (?, ?)", ofA, ofB)).isEqualTo(2);
    }
}
