package rw.terimbere.csams.modules.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.NOW;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;

/**
 * Deleting for good: a member and all their records (President / Vice President), a whole scheme (platform
 * administrators). Also checks the statement and officer reports export.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermanentDeletionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    @Test
    void aPresidentDeletesAMemberAndAllTheirRecords() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 2);
        p.row(NOW);
        p.postedFor(p.payer, "2000", contribution(NOW, "2000"));
        p.loan("10000", "600");
        UUID scheme = p.id();
        UUID member = p.payer.id();
        assertThat(count("select count(*) from contributions where cooperative_id = ? and member_user_id = ?", scheme, member)).isPositive();

        String path = p.base() + "/members/" + member + "/full-deletion";
        MvcResult preview = mockMvc.perform(get(path).header("Authorization", PaymentScenario.bearer(p.scheme.president()))).andReturn();
        assertThat(status(preview)).as(preview.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(preview.getResponse().getContentAsString()).path("data");
        assertThat(data.path("canPurge").asBoolean()).as(data.toString()).isTrue();
        assertThat(data.path("rowsTotal").asLong()).isPositive();

        MvcResult wrongName = purge(p, path, "Uploaded with false information", "someone-else");
        assertThat(status(wrongName)).isEqualTo(409);
        MvcResult noReason = purge(p, path, "short", p.payer.username());
        assertThat(status(noReason)).isEqualTo(409);

        MvcResult done = purge(p, path, "Uploaded with false information", p.payer.username());
        assertThat(status(done)).as(done.getResponse().getContentAsString()).isEqualTo(200);
        for (String table : new String[] {"contributions", "loans", "member_payments", "financial_ledger"}) {
            assertThat(count("select count(*) from " + table + " where cooperative_id = ? and member_user_id = ?", scheme, member))
                    .as(table).isZero();
        }
        assertThat(count("select count(*) from cooperative_memberships where cooperative_id = ? and user_id = ?", scheme, member)).isZero();
        assertThat(count("select count(*) from data_deletion_records where kind = 'MEMBER' and subject_user_id = ?", member)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'MEMBER_PURGE' and entity_id = ?", member)).isEqualTo(1);
        // The officers who handled the payment are still members; the scheme still stands.
        assertThat(count("select count(*) from cooperative_memberships where cooperative_id = ?", scheme)).isPositive();
    }

    @Test
    void aMemberWhoRecordedOthersRecordsAsAnOfficerIsNotPurged() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 1);
        p.row(NOW);
        p.postedFor(p.payer, "1000", contribution(NOW, "1000"));
        MvcResult preview = mockMvc.perform(get(p.base() + "/members/" + p.reviewer.id() + "/full-deletion")
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president())))
                .andReturn();
        JsonNode data = objectMapper.readTree(preview.getResponse().getContentAsString()).path("data");
        assertThat(data.path("canPurge").asBoolean()).isFalse();
        assertThat(data.path("blockedReason").asText()).isEqualTo("MEMBER_ACTED_AS_OFFICER");
        MvcResult refused = purge(p, p.base() + "/members/" + p.reviewer.id() + "/full-deletion", "Trying anyway please", p.reviewer.username());
        assertThat(status(refused)).isEqualTo(409);
        assertThat(count("select count(*) from member_payments where cooperative_id = ?", p.id())).isEqualTo(1);
    }

    @Test
    void onlyAPlatformAdministratorDeletesASchemeForGood_andOtherSchemesAreUntouched() throws Exception {
        PaymentScenario doomed = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 1);
        doomed.row(NOW);
        doomed.postedFor(doomed.payer, "1000", contribution(NOW, "1000"));
        doomed.loan("5000", "300");
        PaymentScenario other = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 1);
        other.row(NOW);
        other.postedFor(other.payer, "1000", contribution(NOW, "1000"));
        UUID id = doomed.id();
        String name = jdbc.queryForObject("select name from cooperatives where id = ?", String.class, id);
        String path = "/api/v1/cooperatives/" + id + "/permanent-deletion";

        MvcResult byPresident = mockMvc.perform(post(path)
                        .header("Authorization", PaymentScenario.bearer(doomed.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("confirmName", name, "reason", "Created by mistake twice"))))
                .andReturn();
        assertThat(status(byPresident)).isEqualTo(403);

        String admin = "Bearer " + doomed.fixtures.superAdminToken();
        MvcResult preview = mockMvc.perform(get(path).header("Authorization", admin)).andReturn();
        assertThat(status(preview)).as(preview.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(objectMapper.readTree(preview.getResponse().getContentAsString()).path("data").path("rowsTotal").asLong()).isPositive();

        MvcResult wrongName = mockMvc.perform(post(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("confirmName", "not it", "reason", "Created by mistake twice"))))
                .andReturn();
        assertThat(status(wrongName)).isEqualTo(409);
        MvcResult done = mockMvc.perform(post(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("confirmName", name, "reason", "Created by mistake twice"))))
                .andReturn();
        assertThat(status(done)).as(done.getResponse().getContentAsString()).isEqualTo(200);

        assertThat(count("select count(*) from cooperatives where id = ?", id)).isZero();
        for (String table : new String[] {"contributions", "loans", "member_payments", "financial_ledger", "cooperative_memberships"}) {
            assertThat(count("select count(*) from " + table + " where cooperative_id = ?", id)).as(table).isZero();
        }
        assertThat(count("select count(*) from users where id = ?", doomed.payer.id())).as("an account of no other scheme goes").isZero();
        assertThat(count("select count(*) from data_deletion_records where kind = 'SCHEME' and scheme_id = ?", id)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'SCHEME_DELETE' and entity_id = ?", id)).isEqualTo(1);
        assertThat(count("select count(*) from contributions where cooperative_id = ?", other.id())).isPositive();
        assertThat(count("select count(*) from member_payments where cooperative_id = ?", other.id())).isEqualTo(1);
    }

    @Test
    void theStatementAndOfficerReportsExportAsPdfAndExcel() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 1);
        p.row(NOW);
        p.postedFor(p.payer, "1000", contribution(NOW, "1000"));
        p.loan("5000", "300");
        String from = LocalDate.now(PaymentScenario.KIGALI).withDayOfYear(1).toString();
        String to = LocalDate.now(PaymentScenario.KIGALI).toString();
        String admin = "Bearer " + p.fixtures.superAdminToken();
        for (String type : new String[] {"MEMBER_STATEMENT", "RECONCILIATION", "COLLECTION_BY_MONTH", "ROTATION_SCHEDULE",
                "MEMBER_POSITIONS", "ARREARS_AND_PENALTIES", "LOAN_BOOK", "INCOME_AND_EXPENSES", "YEAR_END_CLOSE"}) {
            for (String format : new String[] {"PDF", "EXCEL"}) {
                MvcResult r = mockMvc.perform(post(p.base() + "/reports/export")
                                .header("Authorization", admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(java.util.Map.of(
                                        "reportType", type, "fromDate", from, "toDate", to, "format", format,
                                        "memberUserId", p.payer.id().toString()))))
                        .andReturn();
                assertThat(status(r)).as(type + " " + format + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
                assertThat(r.getResponse().getContentAsByteArray().length).as(type).isGreaterThan(500);
                assertThat(r.getResponse().getContentType()).contains("PDF".equals(format) ? "pdf" : "spreadsheetml");
            }
        }
        // A member exports their own statement.
        MvcResult mine = mockMvc.perform(post(p.base() + "/reports/export")
                        .header("Authorization", PaymentScenario.bearer(p.payer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "reportType", "MEMBER_STATEMENT", "fromDate", from, "toDate", to))))
                .andReturn();
        assertThat(status(mine)).as(mine.getResponse().getContentAsString()).isEqualTo(200);
    }

    private MvcResult purge(PaymentScenario p, String path, String reason, String username) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("reason", reason, "confirmUsername", username))))
                .andReturn();
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
