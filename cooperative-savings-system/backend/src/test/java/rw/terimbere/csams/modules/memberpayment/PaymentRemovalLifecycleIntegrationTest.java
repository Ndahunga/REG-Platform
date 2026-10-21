package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.NOW;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

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
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * A payment is never deleted. Until anyone has started to review it the payer can withdraw it; after that an officer
 * rejects it; once its funds are verified it can only be posted or resolved; once posted it can only be reversed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentRemovalLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    private PaymentScenario scheme() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        p.row(NOW);
        return p;
    }

    private UUID submit(PaymentScenario p, String reference) throws Exception {
        String body = "{\"amount\":30000,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\",\"transactionReference\":\"%s\",\"allocations\":[%s]}"
                .formatted(LocalDate.now(PaymentScenario.KIGALI), reference, contribution(NOW, "30000"));
        MvcResult result = p.submitRaw(p.payer, body);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(p.data(result).path("id").asText());
    }

    private MvcResult withdraw(PaymentScenario p, Actor who, UUID payment) throws Exception {
        return mockMvc.perform(post(p.base() + "/payments/" + payment + "/withdraw")
                        .header("Authorization", PaymentScenario.bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Wrong amount\"}"))
                .andReturn();
    }

    private MvcResult hardDelete(PaymentScenario p, Actor who, UUID payment) throws Exception {
        return mockMvc.perform(delete(p.base() + "/payments/" + payment).header("Authorization", PaymentScenario.bearer(who))).andReturn();
    }

    private static String ref() {
        return "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    @Test
    void thePayerCanWithdrawASubmittedPaymentAndItsReferenceIsFreedForACorrectedOne() throws Exception {
        PaymentScenario p = scheme();
        String reference = ref();
        UUID payment = submit(p, reference);

        MvcResult done = withdraw(p, p.payer, payment);
        assertThat(status(done)).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.data(done).path("status").asText()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select rejected_by from member_payments where id = ?", UUID.class, payment)).isEqualTo(p.payer.id());
        assertThat(p.count("select count(*) from member_payments where id = ?", payment)).as("the record stays").isEqualTo(1);
        assertThat(p.count("select count(*) from member_payment_events where payment_id = ? and to_status = 'REJECTED'", payment)).isEqualTo(1);
        assertThat(p.count("select count(*) from audit_logs where cooperative_id = ? and action = 'PAYMENT_WITHDRAW' and entity_id = ?", p.id(), payment)).isEqualTo(1);
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id())).isZero();
        assertThat(p.paid(NOW)).isEqualByComparingTo("0");

        // the reference is free again; a corrected payment can use it
        UUID corrected = submit(p, reference);
        assertThat(corrected).isNotEqualTo(payment);
        // a withdrawn payment cannot be withdrawn twice
        assertThat(p.code(withdraw(p, p.payer, payment))).isEqualTo("PAYMENT_CANNOT_BE_WITHDRAWN");
    }

    @Test
    void nobodyButThePayerCanWithdrawAndAnotherSchemesPaymentIsNotFound() throws Exception {
        PaymentScenario p = scheme();
        PaymentScenario other = scheme();
        Actor otherMember = p.addMember(30);
        UUID payment = submit(p, ref());

        assertThat(status(withdraw(p, otherMember, payment))).as("another member").isEqualTo(404);
        assertThat(status(withdraw(p, p.reviewer, payment))).as("an officer uses reject, not withdraw").isEqualTo(404);
        assertThat(status(withdraw(other, other.payer, payment))).as("through another scheme").isEqualTo(404);
        assertThat(status(withdraw(p, other.payer, payment))).as("a member of another scheme").isEqualTo(403);
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("SUBMITTED");
    }

    @Test
    void oncePickedUpForReviewOrVerifiedOrPostedAPaymentCannotBeWithdrawnOrDeleted() throws Exception {
        PaymentScenario p = scheme();

        UUID review = submit(p, ref());
        assertThat(status(p.act(p.reviewer, review, "/start-review", null))).isEqualTo(200);
        assertThat(p.code(withdraw(p, p.payer, review))).isEqualTo("PAYMENT_CANNOT_BE_WITHDRAWN");

        UUID verified = p.verified("30000", contribution(NOW, "30000"));
        assertThat(p.code(withdraw(p, p.payer, verified))).isEqualTo("PAYMENT_CANNOT_BE_WITHDRAWN");
        MvcResult reject = p.act(p.reviewer, verified, "/reject", "{\"reason\":\"Changed my mind\"}");
        assertThat(p.code(reject)).isEqualTo("VERIFIED_PAYMENT_CANNOT_BE_REJECTED");

        UUID posted = p.posted("30000", contribution(NOW, "30000"));
        assertThat(p.code(withdraw(p, p.payer, posted))).isEqualTo("PAYMENT_CANNOT_BE_WITHDRAWN");
        // there is no way to hard delete any payment, whatever its state and whoever asks
        for (UUID payment : new UUID[] {review, verified, posted}) {
            assertThat(status(hardDelete(p, p.scheme.president(), payment))).isIn(404, 405);
            assertThat(status(hardDelete(p, p.payer, payment))).isIn(403, 404, 405);
        }
        assertThat(p.count("select count(*) from member_payments where cooperative_id = ?", p.id())).isEqualTo(3);
        assertThat(p.paid(NOW)).isEqualByComparingTo("30000");

        // a posted payment is corrected only through the reversal workflow
        UUID reversal = p.requestReversal(p.reviewer, posted, "Posted by mistake");
        assertThat(status(p.decide(p.scheme.president(), reversal, "approve"))).isEqualTo(200);
        assertThat(p.paid(NOW)).isEqualByComparingTo("0");
        assertThat(p.count("select count(*) from member_payments where id = ?", posted)).as("reversed, never deleted").isEqualTo(1);
    }
}
