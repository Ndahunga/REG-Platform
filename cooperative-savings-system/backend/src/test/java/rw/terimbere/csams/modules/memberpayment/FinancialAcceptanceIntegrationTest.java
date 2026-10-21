package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.terimbere.csams.testsupport.PaymentScenario.NEXT;
import static rw.terimbere.csams.testsupport.PaymentScenario.NOW;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.loanAlloc;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * The accounting of a member's money with the figures the scheme actually uses: what is due, what was paid, what each
 * part of a payment became, and what is refused. Each scenario asserts the exact amounts, and that nothing moves unless
 * the money was verified.
 *
 * <p>Companion suites hold the other acceptance figures: {@code RotationPoolAccountingIntegrationTest} (ten members of
 * 30,000, scheduled 300,000, collected 270,000, shortfall 30,000), {@code HybridLedgerSplitIntegrationTest} (30,000 at
 * 40/60; five payments giving 60,000 rotating, 90,000 retained, 150,000 in all), {@code PaymentLoanReversalIntegrationTest}
 * (the 80,000 payment reversed), {@code ReceiptAnalysisIntegrationTest} (receipt statuses) and
 * {@code MigrationStrategyGuardIntegrationTest} (historical overlap).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinancialAcceptanceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    // ============================================================ five members due 30,000, four have paid

    @Test
    void fiveMembersDue30000AndFourHavingPaidShow120000PaidAnd30000Outstanding() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        for (int i = 1; i < 5; i++) {
            p.addMember(30);
        }
        p.rows(NEXT, p.allMembers());
        for (int i = 0; i < 4; i++) {
            p.postedFor(p.allMembers().get(i), "30000", contribution(NEXT, "30000"));
        }

        JsonNode summary = p.data(p.getAs(p.reviewer, "/reports/model-summary"));
        assertThat(summary.path("expectedContributions").decimalValue()).isEqualByComparingTo("150000");
        assertThat(summary.path("postedCollections").decimalValue()).isEqualByComparingTo("120000");
        assertThat(summary.path("outstandingContributions").decimalValue()).isEqualByComparingTo("30000");
        assertThat(jdbc.queryForObject("select sum(paid_amount) from contributions where cooperative_id = ?", BigDecimal.class, p.id()))
                .isEqualByComparingTo("120000");
        assertThat(jdbc.queryForObject("select sum(outstanding_amount) from contributions where cooperative_id = ?", BigDecimal.class, p.id()))
                .isEqualByComparingTo("30000");
        assertThat(p.count("select count(*) from contributions where cooperative_id = ? and status = 'PAID'", p.id())).isEqualTo(4);
        assertThat(p.count("select count(*) from contributions where cooperative_id = ? and status = 'PENDING'", p.id())).isEqualTo(1);
    }

    // ============================================================ the combined payment of 80,000

    @Test
    void aCombinedPaymentOf80000PostsContributionPrincipalAndInterestTogetherAndReversesToTheExactPriorState() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        p.row(NOW);
        Loan loan = p.loan("100000", "5000");
        BigDecimal fundsBefore = p.availableFunds();

        UUID payment = p.posted("80000", contribution(NOW, "30000"), loanAlloc(loan.getId(), "50000"));

        assertThat(p.paid(NOW)).isEqualByComparingTo("30000");
        Loan after = p.reload(loan);
        assertThat(after.getOutstandingPrincipal()).isEqualByComparingTo("55000");
        assertThat(after.getOutstandingInterest()).isEqualByComparingTo("0");
        assertThat(ledgerCredits(p, "REGULAR_CONTRIBUTION")).isEqualByComparingTo("30000");
        assertThat(ledgerCredits(p, "LOAN_PRINCIPAL_REPAYMENT")).isEqualByComparingTo("45000");
        assertThat(ledgerCredits(p, "LOAN_INTEREST_PAYMENT")).isEqualByComparingTo("5000");
        assertThat(ledgerCredits(p, "REGULAR_CONTRIBUTION")
                        .add(ledgerCredits(p, "LOAN_PRINCIPAL_REPAYMENT"))
                        .add(ledgerCredits(p, "LOAN_INTEREST_PAYMENT")))
                .as("every franc of the 80,000 is in the ledger once").isEqualByComparingTo("80000");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("POSTED");
        assertThat(p.count("select count(*) from member_payment_postings where payment_id = ?", payment)).isEqualTo(2);

        UUID reversal = p.requestReversal(p.reviewer, payment, "Paid by mistake");
        assertThat(status(p.decide(p.scheme.president(), reversal, "approve"))).isEqualTo(200);
        assertThat(p.paid(NOW)).isEqualByComparingTo("0");
        Loan restored = p.reload(loan);
        assertThat(restored.getOutstandingPrincipal()).isEqualByComparingTo("100000");
        assertThat(restored.getOutstandingInterest()).isEqualByComparingTo("5000");
        assertThat(p.availableFunds()).isEqualByComparingTo(fundsBefore);
    }

    // ============================================================ partial: 60,000 against 80,000 owed

    @Test
    void sixtyThousandAgainstEightyThousandOwedClearsTheContributionAndPartlyRepaysTheLoan() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        p.row(NOW);
        Loan loan = p.loan("100000", "5000");

        p.posted("60000", contribution(NOW, "30000"), loanAlloc(loan.getId(), "30000"));

        assertThat(p.paid(NOW)).isEqualByComparingTo("30000");
        assertThat(p.outstanding(NOW)).isEqualByComparingTo("0");
        Loan after = p.reload(loan);
        assertThat(after.getOutstandingInterest()).as("interest is settled first").isEqualByComparingTo("0");
        assertThat(after.getOutstandingPrincipal()).isEqualByComparingTo("75000");
        assertThat(after.getTotalRepaidPrincipal()).isEqualByComparingTo("25000");
        assertThat(after.getTotalRepaidInterest()).isEqualByComparingTo("5000");
    }

    @Test
    void aPaymentWhoseAllocationsDoNotAddUpToTheAmountIsRefusedAndCreatesNothing() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        p.row(NOW);
        Loan loan = p.loan("100000", "5000");
        MvcResult refused = p.submitRaw(p.payer, PaymentScenario.body("60000", contribution(NOW, "30000"), loanAlloc(loan.getId(), "50000")));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(p.code(refused)).isEqualTo("ALLOCATION_TOTAL_MISMATCH");
        assertThat(p.count("select count(*) from member_payments where cooperative_id = ?", p.id())).isZero();
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id())).isZero();
    }

    // ============================================================ duplicate reference

    @Test
    void theSameTransactionReferenceCannotBeUsedTwiceEvenWithDifferentSpellingOrByAnotherMember() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        Actor other = p.addMember(30);
        p.rows(NEXT, p.allMembers());
        String reference = "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

        MvcResult first = p.submitRaw(p.payer, withReference(reference, "30000", contribution(NEXT, "30000")));
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(200);

        for (String variant : new String[] {reference, reference.toLowerCase(), " " + reference + " "}) {
            MvcResult duplicate = p.submitRaw(other, withReference(variant, "30000", contribution(NEXT, "30000")));
            assertThat(status(duplicate)).as(variant).isEqualTo(409);
            assertThat(p.code(duplicate)).isEqualTo("DUPLICATE_PAYMENT_REFERENCE");
        }
        assertThat(p.count("select count(*) from member_payments where cooperative_id = ?", p.id())).isEqualTo(1);
    }

    // ============================================================ nothing posts without verified settlement

    @Test
    void aPaymentThatWasNeverVerifiedCannotBePostedAndNothingMoves() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        p.row(NOW);
        Loan loan = p.loan("100000", "5000");
        MvcResult submitted = p.submitRaw(p.payer, PaymentScenario.body("80000", contribution(NOW, "30000"), loanAlloc(loan.getId(), "50000")));
        UUID id = UUID.fromString(p.data(submitted).path("id").asText());
        assertThat(status(p.act(p.reviewer, id, "/start-review", null))).isEqualTo(200);

        MvcResult post = p.act(p.poster, id, "/post", null);
        assertThat(status(post)).isEqualTo(422);
        assertThat(p.code(post)).isEqualTo("PAYMENT_NOT_VERIFIED");
        assertNothingMoved(p, id, loan);
    }

    // ============================================================ approval is atomic

    @Test
    void ifOnePartCannotBePostedNoPartIsAndThePaymentCanBePostedOnceTheCauseIsGone() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        p.row(NOW);
        Loan loan = p.loan("100000", "5000");
        UUID id = p.verified("80000", contribution(NOW, "30000"), loanAlloc(loan.getId(), "50000"));

        // the loan stops being repayable between verification and posting
        jdbc.update("update loans set status = 'CLOSED' where id = ?", loan.getId());
        MvcResult failed = p.act(p.poster, id, "/post", null);
        assertThat(status(failed)).isBetween(400, 499);
        assertThat(p.paid(NOW)).as("the contribution part was not posted either").isEqualByComparingTo("0");
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id())).isZero();
        assertThat(p.count("select count(*) from member_payment_postings where payment_id = ?", id)).isZero();
        assertThat(p.count("select count(*) from loan_repayments where loan_id = ?", loan.getId())).isZero();
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("100000");

        jdbc.update("update loans set status = 'ACTIVE' where id = ?", loan.getId());
        MvcResult posted = p.act(p.poster, id, "/post", null);
        assertThat(status(posted)).as(posted.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.paid(NOW)).isEqualByComparingTo("30000");
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("55000");

        // posting again changes nothing: the payment is not applied twice
        long postings = p.count("select count(*) from member_payment_postings where payment_id = ?", id);
        MvcResult again = p.act(p.poster, id, "/post", null);
        assertThat(status(again)).isIn(200, 409, 422);
        assertThat(p.count("select count(*) from member_payment_postings where payment_id = ?", id)).isEqualTo(postings);
        assertThat(p.paid(NOW)).isEqualByComparingTo("30000");
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("55000");
    }

    // ------------------------------------------------------------------ helpers

    private static String withReference(String reference, String amount, String... allocations) {
        return "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\",\"transactionReference\":\"%s\",\"allocations\":[%s]}"
                .formatted(amount, LocalDate.now(PaymentScenario.KIGALI), reference, String.join(",", allocations));
    }

    private BigDecimal ledgerCredits(PaymentScenario p, String type) {
        return jdbc.queryForObject(
                "select coalesce(sum(credit_amount), 0) from financial_ledger where cooperative_id = ? and transaction_type = ? and status = 'APPROVED'",
                BigDecimal.class, p.id(), type);
    }

    private void assertNothingMoved(PaymentScenario p, UUID payment, Loan loan) {
        assertThat(p.paid(NOW)).isEqualByComparingTo("0");
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("100000");
        assertThat(p.reload(loan).getOutstandingInterest()).isEqualByComparingTo("5000");
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id())).isZero();
        assertThat(p.count("select count(*) from member_payment_postings where payment_id = ?", payment)).isZero();
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isNotEqualTo("POSTED");
    }
}
