package rw.terimbere.csams.modules.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.NOW;
import static rw.terimbere.csams.testsupport.PaymentScenario.bearer;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * Bank reconciliation: a statement is uploaded, lines are matched with payments automatically or suggested, officers settle
 * the rest, and matched payments are verified from their line. Officers see it; only the Accountant, the President and
 * the Vice President change it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconciliationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    @Test
    void aStatementIsMatchedSettledAndUsedToVerifyPayments() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 2);
        p.row(NOW);
        String reference = "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        LocalDate today = LocalDate.now(PaymentScenario.KIGALI);
        MvcResult submitted = p.submitRaw(p.payer, ("{\"amount\":2000,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\","
                + "\"transactionReference\":\"%s\",\"allocations\":[%s]}").formatted(today, reference, contribution(NOW, "2000")));
        assertThat(status(submitted)).as(submitted.getResponse().getContentAsString()).isEqualTo(200);
        UUID payment = UUID.fromString(p.data(submitted).path("id").asText());

        String d = today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String csv = "Date,Narration,Debit,Credit,Balance\n"
                + d + ",MOMO deposit " + reference + " from member,,2000,2000\n"
                + d + ",Monthly ledger fee,500,,1500\n"
                + d + ",Unknown deposit,,7777,9277\n";
        Actor secretary = p.fixtures.register(p.id(), "SECRETARY");

        MvcResult bySecretary = upload(p, secretary, csv);
        assertThat(status(bySecretary)).isEqualTo(403);
        MvcResult uploaded = upload(p, p.reviewer, csv);
        assertThat(status(uploaded)).as(uploaded.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.data(uploaded).path("lineCount").asInt()).isEqualTo(3);
        MvcResult again = upload(p, p.reviewer, csv);
        assertThat(status(again)).isEqualTo(409);

        String base = p.base() + "/reconciliation";
        JsonNode overview = p.data(mockMvc.perform(get(base).header("Authorization", bearer(secretary))).andReturn());
        assertThat(overview.path("canEdit").asBoolean()).isFalse();
        assertThat(overview.path("lineCounts").path("MATCHED").asInt()).as(overview.toString()).isEqualTo(1);
        assertThat(overview.path("lineCounts").path("SUGGESTED").asInt()).isEqualTo(1);
        assertThat(overview.path("lineCounts").path("UNMATCHED").asInt()).isEqualTo(1);
        MvcResult byMember = mockMvc.perform(get(base).header("Authorization", bearer(p.payer))).andReturn();
        assertThat(status(byMember)).isEqualTo(403);

        UUID fee = line(p, base, "SUGGESTED");
        UUID unknown = line(p, base, "UNMATCHED");
        assertThat(status(postAs(secretary, base + "/lines/" + fee + "/confirm", null))).isEqualTo(403);
        MvcResult confirmed = postAs(p.scheme.president(), base + "/lines/" + fee + "/confirm", null);
        assertThat(status(confirmed)).as(confirmed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.data(confirmed).path("status").asText()).isEqualTo("EXPLAINED");

        MvcResult noNote = postAs(p.verifier, base + "/lines/" + unknown + "/explain", Map.of("category", "OTHER"));
        assertThat(status(noNote)).isEqualTo(409);
        MvcResult explained = postAs(p.verifier, base + "/lines/" + unknown + "/explain",
                Map.of("category", "NOT_SCHEME_MONEY", "note", "Deposited by mistake, returned to the sender"));
        assertThat(p.data(explained).path("status").asText()).isEqualTo("EXPLAINED");
        MvcResult undone = postAs(p.verifier, base + "/lines/" + unknown + "/undo", null);
        assertThat(p.data(undone).path("status").asText()).isEqualTo("UNMATCHED");

        // The accountant who uploaded the statement recorded the evidence; another officer verifies with it.
        MvcResult verified = postAs(p.verifier, base + "/verify-payments", null);
        assertThat(status(verified)).as(verified.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode results = p.data(verified);
        assertThat(results.get(0).path("outcome").asText()).as(results.toString()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("VERIFIED");
        UUID matchedLine = jdbc.queryForObject("select line_id from bank_statement_matches where payment_id = ?", UUID.class, payment);
        assertThat(status(postAs(p.verifier, base + "/lines/" + matchedLine + "/undo", null))).isEqualTo(409);
        assertThat(p.count("select count(*) from audit_logs where action = 'RECONCILIATION_DECISION'")).isPositive();
    }

    private MvcResult upload(PaymentScenario p, Actor who, String csv) throws Exception {
        return mockMvc.perform(multipart(p.base() + "/reconciliation/statements")
                        .file(new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("source", "BANK")
                        .header("Authorization", bearer(who)))
                .andReturn();
    }

    private UUID line(PaymentScenario p, String base, String status) throws Exception {
        JsonNode lines = p.data(mockMvc.perform(get(base + "/lines").param("status", status)
                .header("Authorization", bearer(p.reviewer))).andReturn());
        assertThat(lines.size()).as(status + " " + lines).isEqualTo(1);
        return UUID.fromString(lines.get(0).path("id").asText());
    }

    private MvcResult postAs(Actor who, String path, Object body) throws Exception {
        var request = post(path).header("Authorization", bearer(who));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }
}
