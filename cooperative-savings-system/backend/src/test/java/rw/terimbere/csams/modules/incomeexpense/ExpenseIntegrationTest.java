package rw.terimbere.csams.modules.incomeexpense;

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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * Scheme expenses: every expense carries its document and the amount on it is checked; bank charges come from the
 * statement and, once approved, reconcile their statement line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpenseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    @Test
    void anExpenseNeedsItsDocumentAndTheAmountOnItIsChecked() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 2);
        p.row(NOW);
        p.postedFor(p.payer, "2000", contribution(NOW, "2000"));
        String today = LocalDate.now(PaymentScenario.KIGALI).minusDays(1).toString();
        byte[] bill = pdf("Hotel des Mille Collines", "Meeting refreshments", "TOTAL RWF 1,500");

        MvcResult noFile = mockMvc.perform(form(p, p.reviewer, null, "MEETING", "1500", today, "Committee meeting", null)).andReturn();
        assertThat(status(noFile)).isEqualTo(400);

        MvcResult wrong = mockMvc.perform(form(p, p.reviewer, bill, "MEETING", "1800", today, "Committee meeting", null)).andReturn();
        assertThat(status(wrong)).isEqualTo(422);
        assertThat(p.code(wrong)).isEqualTo("EVIDENCE_AMOUNT_MISMATCH");

        MvcResult explained = mockMvc.perform(form(p, p.reviewer, bill, "MEETING", "1800", today, "Committee meeting",
                "300 paid in cash for water, no receipt")).andReturn();
        assertThat(status(explained)).as(explained.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.data(explained).path("evidenceCheck").asText()).isEqualTo("MISMATCH");

        MvcResult ok = mockMvc.perform(form(p, p.reviewer, bill, "MEALS_AND_REFRESHMENTS", "1500", today, "Committee meeting", null)).andReturn();
        assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode e = p.data(ok);
        assertThat(e.path("evidenceCheck").asText()).isEqualTo("MATCH");
        assertThat(e.path("status").asText()).isEqualTo("PENDING");
        assertThat(e.path("evidenceKey").asText()).isNotBlank();

        assertThat(status(mockMvc.perform(form(p, p.payer, bill, "MEETING", "1500", today, "Mine", null)).andReturn())).isEqualTo(403);

        JsonNode overview = p.data(mockMvc.perform(get(p.base() + "/expenses").header("Authorization", bearer(p.reviewer))).andReturn());
        assertThat(overview.path("canRecord").asBoolean()).isTrue();
        assertThat(overview.path("pendingCount").asInt()).isEqualTo(2);
    }

    @Test
    void bankChargesComeFromTheStatementAndReconcileItsLineWhenApproved() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 2);
        p.row(NOW);
        p.postedFor(p.payer, "2000", contribution(NOW, "2000"));
        String d = LocalDate.now(PaymentScenario.KIGALI).minusDays(1).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String csv = "Date,Narration,Debit,Credit,Balance\n" + d + ",Monthly ledger fee,500,,1500\n" + d + ",Cash withdrawal,300,,1200\n";
        MvcResult uploaded = mockMvc.perform(multipart(p.base() + "/reconciliation/statements")
                        .file(new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("source", "BANK")
                        .header("Authorization", bearer(p.reviewer)))
                .andReturn();
        assertThat(status(uploaded)).as(uploaded.getResponse().getContentAsString()).isEqualTo(200);

        JsonNode lines = p.data(mockMvc.perform(get(p.base() + "/expenses/bank-charge-lines").header("Authorization", bearer(p.reviewer))).andReturn());
        assertThat(lines.size()).as(lines.toString()).isEqualTo(1);
        UUID line = UUID.fromString(lines.get(0).path("lineId").asText());

        MvcResult recorded = mockMvc.perform(post(p.base() + "/expenses/bank-charges").header("Authorization", bearer(p.reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lineIds", List.of(line)))))
                .andReturn();
        assertThat(status(recorded)).as(recorded.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode charge = p.data(recorded).get(0);
        assertThat(charge.path("expenseType").asText()).isEqualTo("BANK_CHARGE");
        assertThat(charge.path("evidenceCheck").asText()).isEqualTo("STATEMENT_LINE");
        assertThat(p.data(mockMvc.perform(get(p.base() + "/expenses/bank-charge-lines").header("Authorization", bearer(p.reviewer))).andReturn()).size())
                .isZero();

        MvcResult approved = mockMvc.perform(post(p.base() + "/transactions/" + charge.path("id").asText() + "/approve")
                        .header("Authorization", bearer(p.scheme.president())))
                .andReturn();
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from bank_statement_lines where id = ?", String.class, line)).isEqualTo("CONFIRMED");
        assertThat(p.count("select count(*) from bank_statement_matches where line_id = ? and ledger_entry_id is not null", line)).isEqualTo(1);
    }

    private MockMultipartHttpServletRequestBuilder form(PaymentScenario p, Actor who, byte[] file, String type, String amount,
            String date, String description, String explanation) {
        MockMultipartHttpServletRequestBuilder b = multipart(p.base() + "/expenses");
        if (file != null) {
            b.file(new MockMultipartFile("file", "bill.pdf", "application/pdf", file));
        }
        b.param("expenseType", type).param("amount", amount).param("date", date).param("description", description);
        if (explanation != null) {
            b.param("explanation", explanation);
        }
        b.header("Authorization", bearer(who));
        return b;
    }

    private static byte[] pdf(String... rows) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                float y = 720;
                for (String row : rows) {
                    cs.beginText();
                    cs.newLineAtOffset(40, y);
                    cs.showText(row);
                    cs.endText();
                    y -= 20;
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
