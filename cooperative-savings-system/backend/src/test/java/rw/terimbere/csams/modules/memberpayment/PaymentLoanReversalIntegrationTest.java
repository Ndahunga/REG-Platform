package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.terimbere.csams.testsupport.PaymentScenario.NEXT;
import static rw.terimbere.csams.testsupport.PaymentScenario.NOW;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.credit;
import static rw.terimbere.csams.testsupport.PaymentScenario.loanAlloc;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;

/**
 * Reversing a posted payment that includes a loan repayment: the loan owes again exactly what the repayment had taken,
 * the contribution is restored, the ledger is reversed by compensating entries (never deleted), everything happens in one
 * transaction, and the audit trail keeps both the original and the reversal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentLoanReversalIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LoanInstallmentRepository installmentRepository;

    private PaymentScenario p;

    @BeforeEach
    void setUp() throws Exception {
        p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
    }

    // ============================================================ the 80,000 payment

    @Test
    void aPaymentOf80000ContributionAndLoanIsReversedBackToTheExactPriorPosition() throws Exception {
        p.row(NOW);
        Loan loan = p.loan("100000", "5000");
        BigDecimal fundsBefore = p.availableFunds();

        UUID payment = p.posted("80000", contribution(NOW, "30000"), loanAlloc(loan.getId(), "50000"));
        Loan afterPayment = p.reload(loan);
        assertThat(p.paid(NOW)).isEqualByComparingTo("30000");
        assertThat(afterPayment.getOutstandingPrincipal()).as("45,000 of the 50,000 was principal").isEqualByComparingTo("55000");
        assertThat(afterPayment.getOutstandingInterest()).as("5,000 of it settled the interest").isEqualByComparingTo("0");
        assertThat(afterPayment.getTotalRepaidPrincipal()).isEqualByComparingTo("45000");
        assertThat(afterPayment.getTotalRepaidInterest()).isEqualByComparingTo("5000");
        assertThat(p.availableFunds()).isNotEqualByComparingTo(fundsBefore);
        long postingsBefore = p.count("select count(*) from member_payment_postings where payment_id = ?", payment);
        assertThat(postingsBefore).isEqualTo(2);

        UUID reversal = p.requestReversal(p.reviewer, payment, "Paid by mistake");
        // only the fund authority decides, and not the person who asked
        assertThat(status(p.decide(p.verifier, reversal, "approve"))).isEqualTo(403);
        assertThat(p.paid(NOW)).as("nothing moves before approval").isEqualByComparingTo("30000");
        MvcResult approved = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);

        // the contribution is restored
        assertThat(p.paid(NOW)).isEqualByComparingTo("0");
        assertThat(p.outstanding(NOW)).isEqualByComparingTo("30000");
        // the loan owes what it owed before: principal back by 45,000, interest back by 5,000, status as before
        Loan restored = p.reload(loan);
        assertThat(restored.getOutstandingPrincipal()).isEqualByComparingTo("100000");
        assertThat(restored.getOutstandingInterest()).isEqualByComparingTo("5000");
        assertThat(restored.getOutstandingPenalty()).isEqualByComparingTo("0");
        assertThat(restored.getTotalRepaidPrincipal()).isEqualByComparingTo("0");
        assertThat(restored.getTotalRepaidInterest()).isEqualByComparingTo("0");
        assertThat(restored.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        // the ledger net effect is reversed, by compensating entries
        assertThat(p.availableFunds()).as("available funds are exactly what they were").isEqualByComparingTo(fundsBefore);
        assertThat(netLedger("LOAN_PRINCIPAL_REPAYMENT", "LOAN_INTEREST_PAYMENT", "REGULAR_CONTRIBUTION", "REVERSAL"))
                .as("everything the payment credited was debited back").isEqualByComparingTo("0");
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ? and status = 'REVERSED'", p.id()))
                .as("the originals are kept, marked reversed").isGreaterThanOrEqualTo(3);
        // the original records are intact and a new immutable record says what was given back
        assertThat(p.count("select count(*) from member_payment_postings where payment_id = ?", payment)).isEqualTo(postingsBefore);
        assertThat(p.count("select count(*) from loan_repayments where loan_id = ? and reversed_at is not null", loan.getId())).isEqualTo(1);
        assertThat(p.count("select count(*) from loan_repayments where loan_id = ?", loan.getId())).as("not deleted").isEqualTo(1);
        var given = jdbc.queryForMap("select * from member_payment_loan_reversals where payment_id = ?", payment);
        assertThat((BigDecimal) given.get("principal_amount")).isEqualByComparingTo("45000");
        assertThat((BigDecimal) given.get("interest_amount")).isEqualByComparingTo("5000");
        assertThat((BigDecimal) given.get("outstanding_principal_before")).isEqualByComparingTo("55000");
        assertThat((BigDecimal) given.get("outstanding_principal_after")).isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("REVERSED");
        assertThat(p.count("select count(*) from audit_logs where entity_id = ? and action in ('PAYMENT_REVERSAL_REQUEST','PAYMENT_REVERSAL_EXECUTE')",
                reversal)).as("the request and the execution both stay on the audit trail").isEqualTo(2);
        assertThat(p.count("select count(*) from audit_logs where action = 'PAYMENT_POST' and cooperative_id = ?", p.id()))
                .as("the original posting is still on record").isGreaterThanOrEqualTo(1);

        // idempotent: approving again changes nothing and cannot give the loan back twice
        MvcResult again = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(status(again)).isEqualTo(200);
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("100000");
        assertThat(p.count("select count(*) from member_payment_loan_reversals where payment_id = ?", payment)).isEqualTo(1);
        // a reversed payment cannot be reversed again
        assertThat(status(p.act(p.reviewer, payment, "/reversals", "{\"reason\":\"again\"}"))).isEqualTo(422);
    }

    @Test
    void theReversedPaymentsAmountsReconcileAndTheLoanCanBeRepaidAgain() throws Exception {
        p.row(NOW);
        Loan loan = p.loan("20000", "0");
        UUID payment = p.posted("20000", loanAlloc(loan.getId(), "20000"));
        assertThat(p.reload(loan).getStatus()).as("fully repaid closes the loan").isEqualTo(LoanStatus.CLOSED);

        UUID reversal = p.requestReversal(p.reviewer, payment, "Wrong loan");
        assertThat(status(p.decide(p.scheme.president(), reversal, "approve"))).isEqualTo(200);
        Loan reopened = p.reload(loan);
        assertThat(reopened.getStatus()).as("a loan the repayment closed is reopened").isEqualTo(LoanStatus.ACTIVE);
        assertThat(reopened.getOutstandingPrincipal()).isEqualByComparingTo("20000");

        // the loan is repayable again, through the ordinary path
        UUID again = p.posted("5000", loanAlloc(loan.getId(), "5000"));
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("15000");
        assertThat(again).isNotEqualTo(payment);
    }

    // ============================================================ instalments

    @Test
    void anInstalmentLoanHasItsInstalmentsReopenedExactlyAsTheyWerePaid() throws Exception {
        p.row(NOW);
        Loan loan = p.loan("100000", "5000");
        loan.setEqualInstallmentAmount(new BigDecimal("52500"));
        loanRepository.saveAndFlush(loan);
        LocalDate first = LocalDate.now(PaymentScenario.KIGALI).plusMonths(1);
        LoanInstallment one = installment(loan, 1, first, "50000", "2500", "100000");
        LoanInstallment two = installment(loan, 2, first.plusMonths(1), "50000", "2500", "50000");

        UUID payment = p.posted("52500", loanAlloc(loan.getId(), "52500"));
        LoanInstallment paidOne = installmentRepository.findById(one.getId()).orElseThrow();
        assertThat(paidOne.getPrincipalPaid()).isEqualByComparingTo("50000");
        assertThat(paidOne.getInterestPaid()).isEqualByComparingTo("2500");
        assertThat(paidOne.getStatus()).isEqualTo(LoanInstallmentStatus.PAID);

        UUID reversal = p.requestReversal(p.reviewer, payment, "Mistaken repayment");
        assertThat(status(p.decide(p.scheme.president(), reversal, "approve"))).isEqualTo(200);

        LoanInstallment reopenedOne = installmentRepository.findById(one.getId()).orElseThrow();
        assertThat(reopenedOne.getPrincipalPaid()).isEqualByComparingTo("0");
        assertThat(reopenedOne.getInterestPaid()).isEqualByComparingTo("0");
        assertThat(reopenedOne.getStatus()).isEqualTo(LoanInstallmentStatus.PENDING);
        assertThat(installmentRepository.findById(two.getId()).orElseThrow().getPrincipalPaid()).isEqualByComparingTo("0");
        Loan restored = p.reload(loan);
        assertThat(restored.getOutstandingPrincipal()).isEqualByComparingTo("100000");
        assertThat(restored.getOutstandingInterest()).isEqualByComparingTo("5000");
    }

    // ============================================================ what must stay refused

    @Test
    void aRepaymentCannotBeGivenBackWhileALaterRepaymentOfTheSameLoanIsInForce() throws Exception {
        p.row(NOW);
        Loan loan = p.loan("100000", "0");
        UUID first = p.posted("40000", loanAlloc(loan.getId(), "40000"));
        UUID second = p.posted("10000", loanAlloc(loan.getId(), "10000"));

        UUID reverseFirst = p.requestReversal(p.reviewer, first, "Mistaken");
        MvcResult blocked = p.decide(p.scheme.president(), reverseFirst, "approve");
        assertThat(status(blocked)).isEqualTo(422);
        assertThat(p.code(blocked)).isEqualTo("REVERSAL_BLOCKED_LATER_REPAYMENT");
        assertThat(p.reload(loan).getOutstandingPrincipal()).as("nothing changed").isEqualByComparingTo("50000");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, first)).isEqualTo("POSTED");

        // in the right order it works, one at a time
        UUID reverseSecond = p.requestReversal(p.reviewer, second, "Mistaken too");
        assertThat(status(p.decide(p.scheme.president(), reverseSecond, "approve"))).isEqualTo(200);
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("60000");
        assertThat(status(p.decide(p.scheme.president(), reverseFirst, "approve"))).isEqualTo(200);
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("100000");
    }

    @Test
    void aWrittenOffLoanOrAPenaltyRepaymentIsNeverReversedAutomatically() throws Exception {
        p.row(NOW);
        Loan loan = p.loan("100000", "0");
        UUID payment = p.posted("10000", loanAlloc(loan.getId(), "10000"));
        jdbc.update("update loans set status = 'WRITTEN_OFF' where id = ?", loan.getId());
        UUID reversal = p.requestReversal(p.reviewer, payment, "Mistaken");
        MvcResult blocked = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(p.code(blocked)).isEqualTo("REVERSAL_BLOCKED_LOAN_STATE");

        jdbc.update("update loans set status = 'ACTIVE' where id = ?", loan.getId());
        jdbc.update("update loan_repayments set penalty_portion = 100, amount_total = amount_total + 100 where loan_id = ?", loan.getId());
        MvcResult penalty = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(p.code(penalty)).isEqualTo("REVERSAL_BLOCKED_PENALTY");
        assertThat(p.reload(loan).getOutstandingPrincipal()).as("nothing was given back").isEqualByComparingTo("90000");
    }

    // ============================================================ member credit inside the payment

    @Test
    void anUnspentCreditIsTakenBackWithThePaymentAndTheJournalAndLedgerStillAgree() throws Exception {
        UUID payment = p.posted("5000", credit("5000", "UNAPPLIED_CREDIT", null));
        assertThat(p.creditJournalBalance()).isEqualByComparingTo("5000");

        UUID reversal = p.requestReversal(p.reviewer, payment, "Sent to the wrong scheme");
        MvcResult approved = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.creditJournalBalance()).isEqualByComparingTo("0");
        assertThat(p.creditLedgerBalance()).isEqualByComparingTo("0");
        assertThat(p.count("select count(*) from member_credit_entries where cooperative_id = ? and entry_type = 'RECEIPT_REVERSED'", p.id())).isEqualTo(1);
        assertThat(p.count("select count(*) from member_credit_entries where cooperative_id = ? and entry_type = 'RECEIVED'", p.id()))
                .as("the original receipt stays").isEqualTo(1);
        JsonNode summary = p.data(p.getAs(p.poster, "/reports/model-summary"));
        assertThat(summary.path("memberCreditHeld").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void aCreditThatWasAlreadyAppliedBlocksTheWholeReversalAndNothingIsHalfUndone() throws Exception {
        p.row(NOW);
        p.row(NEXT);
        Loan loan = p.loan("100000", "0");
        UUID payment = p.posted("40000",
                contribution(NOW, "10000"), loanAlloc(loan.getId(), "20000"), credit("10000", "UNAPPLIED_CREDIT", null));
        // an officer applies the whole credit to next month's contribution
        MvcResult applied = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(p.base() + "/payments/credits/apply")
                        .header("Authorization", PaymentScenario.bearer(p.poster))
                        .header("Idempotency-Key", "spent-1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(("{\"memberUserId\":\"%s\",\"purpose\":\"UNAPPLIED_CREDIT\",\"targetType\":\"CONTRIBUTION\","
                                + "\"periodYear\":%d,\"periodMonth\":%d,\"amount\":10000}")
                                .formatted(p.payer.id(), NEXT.getYear(), NEXT.getMonthValue())))
                .andReturn();
        assertThat(status(applied)).isEqualTo(200);

        UUID reversal = p.requestReversal(p.reviewer, payment, "Mistaken");
        MvcResult blocked = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(status(blocked)).isEqualTo(422);
        assertThat(p.code(blocked)).isEqualTo("REVERSAL_BLOCKED_CREDIT_APPLIED");
        // atomic: the contribution and the loan were not touched either
        assertThat(p.paid(NOW)).isEqualByComparingTo("10000");
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("80000");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("POSTED");
        assertThat(p.count("select count(*) from member_payment_loan_reversals where payment_id = ?", payment)).isZero();
    }

    @Test
    void creditPartlyAppliedBlocksTheReversalOfItsPaymentAndLeavesEveryRecordAndTheCreditBalanceUntouched() throws Exception {
        p.row(NOW);
        p.row(NEXT);
        // a payment creates 20,000 of credit; 10,000 of it is later applied to next month's contribution
        UUID payment = p.posted("20000", credit("20000", "UNAPPLIED_CREDIT", null));
        MvcResult applied = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(p.base() + "/payments/credits/apply")
                        .header("Authorization", PaymentScenario.bearer(p.poster))
                        .header("Idempotency-Key", "partly-1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(("{\"memberUserId\":\"%s\",\"purpose\":\"UNAPPLIED_CREDIT\",\"targetType\":\"CONTRIBUTION\","
                                + "\"periodYear\":%d,\"periodMonth\":%d,\"amount\":10000}")
                                .formatted(p.payer.id(), NEXT.getYear(), NEXT.getMonthValue())))
                .andReturn();
        assertThat(status(applied)).isEqualTo(200);
        assertThat(p.creditJournalBalance()).isEqualByComparingTo("10000");
        long journal = p.count("select count(*) from member_credit_entries where cooperative_id = ?", p.id());
        long ledger = p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id());
        BigDecimal paidNext = p.paid(NEXT);

        // the original payment is found to be wrong: the reversal is refused as a whole, with a stable reason
        UUID reversal = p.requestReversal(p.reviewer, payment, "The transfer never arrived");
        MvcResult blocked = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(status(blocked)).isEqualTo(422);
        assertThat(p.code(blocked)).isEqualTo("REVERSAL_BLOCKED_CREDIT_APPLIED");

        // nothing was deleted, rewritten or driven negative
        assertThat(p.creditJournalBalance()).as("the unspent 10,000 is still held, never negative").isEqualByComparingTo("10000");
        assertThat(p.creditJournalBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(p.count("select count(*) from member_credit_entries where cooperative_id = ?", p.id())).isEqualTo(journal);
        assertThat(p.count("select count(*) from member_credit_entries where cooperative_id = ? and entry_type = 'RECEIPT_REVERSED'", p.id())).isZero();
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id())).isEqualTo(ledger);
        assertThat(p.paid(NEXT)).isEqualByComparingTo(paidNext);
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("POSTED");
        assertThat(p.creditLedgerBalance()).as("the ledger still agrees with the journal").isEqualByComparingTo(p.creditJournalBalance());
        MvcResult recon = p.getAs(p.scheme.president(), "/ledger/split-reconciliation");
        assertThat(p.data(recon).path("balanced").asBoolean()).isTrue();
    }

    @Test
    void theHundredThousandPaymentWithContributionLoanAndCreditReversesAsOne() throws Exception {
        p.row(NOW);
        Loan loan = p.loan("100000", "5000");
        BigDecimal fundsBefore = p.availableFunds();
        UUID payment = p.posted("100000",
                contribution(NOW, "30000"), loanAlloc(loan.getId(), "50000"), credit("20000", "UNAPPLIED_CREDIT", null));
        assertThat(p.creditJournalBalance()).isEqualByComparingTo("20000");

        UUID reversal = p.requestReversal(p.reviewer, payment, "The whole payment was a mistake");
        MvcResult approved = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);

        JsonNode back = p.data(approved);
        assertThat(back.path("takenBack")).hasSize(1);
        assertThat(back.path("loansGivenBack")).hasSize(1);
        assertThat(back.path("creditsTakenBack")).hasSize(1);
        assertThat(p.paid(NOW)).isEqualByComparingTo("0");
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("100000");
        assertThat(p.reload(loan).getOutstandingInterest()).isEqualByComparingTo("5000");
        assertThat(p.creditJournalBalance()).isEqualByComparingTo("0");
        assertThat(p.availableFunds()).isEqualByComparingTo(fundsBefore);
    }

    // ============================================================ helpers

    private LoanInstallment installment(Loan loan, int number, LocalDate due, String principal, String interest, String opening) {
        return installmentRepository.saveAndFlush(LoanInstallment.builder()
                .loanId(loan.getId())
                .cooperativeId(p.id())
                .installmentNumber(number)
                .dueDate(due)
                .openingPrincipalBalance(new BigDecimal(opening))
                .principalDue(new BigDecimal(principal))
                .interestDue(new BigDecimal(interest))
                .scheduledInstallmentAmount(new BigDecimal(principal).add(new BigDecimal(interest)))
                .build());
    }

    /** Net (credits less debits) of the member's ledger entries of the given types, whatever their status. */
    private BigDecimal netLedger(String... types) {
        String in = String.join("','", List.of(types));
        return jdbc.queryForObject("select coalesce(sum(credit_amount), 0) - coalesce(sum(debit_amount), 0) from financial_ledger"
                + " where cooperative_id = ? and member_user_id = ? and transaction_type in ('" + in + "')",
                BigDecimal.class, p.id(), p.payer.id());
    }
}
