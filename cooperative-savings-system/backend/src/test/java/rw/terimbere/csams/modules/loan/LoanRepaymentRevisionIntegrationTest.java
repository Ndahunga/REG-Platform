package rw.terimbere.csams.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.bearer;
import static rw.terimbere.csams.testsupport.PaymentScenario.loanAlloc;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
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
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/** A repayment typed wrong (53,000 for 530,000) is revised: the wrong one stays on record, reversed, with the reason. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanRepaymentRevisionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    @Test
    void aMistypedRepaymentIsRevisedAndBothStayOnRecord() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 1);
        Loan loan = p.loan("500000", "50000");
        String base = p.base() + "/loans/" + loan.getId() + "/repayments";
        String today = LocalDate.now(PaymentScenario.KIGALI).toString();

        MvcResult recorded = postAs(p.reviewer, base, Map.of("amount", "53000", "paymentDate", today, "paymentReference", "Proof on WhatsApp"));
        assertThat(status(recorded)).as(recorded.getResponse().getContentAsString()).isEqualTo(200);
        UUID wrong = UUID.fromString(p.data(recorded).path("id").asText());

        assertThat(status(postAs(p.payer, base + "/" + wrong + "/revise", Map.of("amount", "530000", "reason", "Typed 53,000 instead of 530,000"))))
                .isEqualTo(403);
        assertThat(status(postAs(p.reviewer, base + "/" + wrong + "/revise", Map.of("amount", "530000", "reason", "no"))))
                .isEqualTo(400);

        MvcResult revised = postAs(p.verifier, base + "/" + wrong + "/revise", Map.of("amount", "530000", "reason", "Typed 53,000 instead of 530,000"));
        assertThat(status(revised)).as(revised.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode r = p.data(revised);
        assertThat(r.path("amountTotal").decimalValue()).isEqualByComparingTo("530000");
        assertThat(r.path("interestPortion").decimalValue()).isEqualByComparingTo("50000");
        assertThat(r.path("principalPortion").decimalValue()).isEqualByComparingTo("480000");
        assertThat(r.path("revisionOfId").asText()).isEqualTo(wrong.toString());
        assertThat(r.path("revisionReason").asText()).isEqualTo("Typed 53,000 instead of 530,000");
        assertThat(r.path("paymentReference").asText()).isEqualTo("Proof on WhatsApp");

        Loan after = p.reload(loan);
        assertThat(after.getOutstandingPrincipal()).isEqualByComparingTo("20000");
        assertThat(after.getOutstandingInterest()).isEqualByComparingTo("0");

        JsonNode list = p.data(mockMvc.perform(get(base).header("Authorization", bearer(p.reviewer))).andReturn());
        assertThat(list.size()).isEqualTo(2);
        JsonNode old = list.get(0).path("id").asText().equals(wrong.toString()) ? list.get(0) : list.get(1);
        assertThat(old.path("reversed").asBoolean()).isTrue();
        assertThat(old.path("revisedById").asText()).isEqualTo(r.path("id").asText());
        assertThat(old.path("revisable").asBoolean()).isFalse();

        assertThat(status(postAs(p.verifier, base + "/" + wrong + "/revise", Map.of("amount", "1000", "reason", "Second try on the same"))))
                .isEqualTo(422);
        assertThat(p.count("select count(*) from audit_logs where action = 'LOAN_REPAYMENT_REVISION' and cooperative_id = ?", p.id()))
                .isEqualTo(1);
        // the ledger keeps the wrong credits with their reversals, and credits the corrected repayment
        java.math.BigDecimal corrected = jdbc.queryForObject("select coalesce(sum(credit_amount),0) from financial_ledger"
                + " where source_entity_id = ?", java.math.BigDecimal.class, UUID.fromString(r.path("id").asText()));
        assertThat(corrected).isEqualByComparingTo("530000");
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ? and transaction_type = 'REVERSAL'", p.id()))
                .isEqualTo(2);
    }

    @Test
    void aRepaymentPostedFromAMemberPaymentIsCorrectedByReversingThePayment() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 1);
        Loan loan = p.loan("10000", "600");
        p.posted("1000", loanAlloc(loan.getId(), "1000"));
        UUID repayment = jdbc.queryForObject("select id from loan_repayments where loan_id = ?", UUID.class, loan.getId());
        MvcResult refused = postAs(p.verifier, p.base() + "/loans/" + loan.getId() + "/repayments/" + repayment + "/revise",
                Map.of("amount", "2000", "reason", "Wrong amount entered"));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(p.code(refused)).isEqualTo("REPAYMENT_FROM_PAYMENT");
    }

    private MvcResult postAs(Actor who, String path, Object body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }
}
