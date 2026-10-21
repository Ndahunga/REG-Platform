package rw.terimbere.csams.modules.rotation.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * Phase 5: one Hybrid Saving Scheme driven through its whole financial life by the real API, with the secure strict
 * verification path on (statement line, independent verification, a third officer posts). After every meaningful
 * transaction the books are reconciled: cash, liabilities, retained capital, classified and unclassified money, loans,
 * payouts and entitlements must agree, and no amount may be counted twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinancialLifecycleIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final YearMonth NOW = YearMonth.now(KIGALI);
    private static final YearMonth NEXT = NOW.plusMonths(1);
    private static final YearMonth AFTER = NOW.plusMonths(2);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;
    @Autowired private ShareValuationService shareValuationService;
    @Autowired private PaymentVerificationCheckService checkService;

    private SchemeFixtures fixtures;
    private Scheme s;
    private Actor accC;
    private Actor recipient2;
    private int referenceSeed;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
        strict(true);
        s = fixtures.scheme("HYBRID");
        accC = fixtures.register(s.id(), "ACCOUNTANT");
        recipient2 = fixtures.register(s.id(), "MEMBER");
        jdbc.update("update cooperative_memberships set share_count = 2 where cooperative_id = ? and user_id = ?", s.id(), s.memberA().id());
    }

    @AfterEach
    void tearDown() {
        strict(false);
    }

    @Test
    void theWholeLifecycleKeepsEveryBalanceConsistentAndCountsNothingTwice() throws Exception {
        // ---- registration, model selection and the rotation schedule
        assertThat(jdbc.queryForObject("select operating_model from cooperatives where id = ?", String.class, s.id())).isEqualTo("HYBRID");
        configureRotation(5000, 5000);
        activateCycle(List.of(s.memberB().id(), recipient2.id()));
        saveLine(NEXT, "0"); // monthly obligations: expected 2000 each, 50/50 split fixed at creation
        saveLine(AFTER, "0");
        assertThat(jdbc.queryForObject("select count(*) from contributions where cooperative_id = ? and rotation_period_id is not null", Long.class, s.id()))
                .as("both obligations are bound to their original period and recipient").isEqualTo(2);
        UUID period1 = periodOf(NEXT);
        UUID period2 = periodOf(AFTER);
        Books zero = checkpoint("after registration");
        assertThat(zero.cash).isEqualByComparingTo("0");

        // ---- contribution submitted, confirmed by an imported statement line, verified and posted by three officers
        UUID p1 = confirmedPayment("1500", contribution(NEXT, "1500"));
        postPayment(accC, p1);
        Books afterP1 = checkpoint("after the first verified payment");
        assertThat(afterP1.cash).isEqualByComparingTo("1500");
        assertThat(afterP1.owed).as("rotating half of 1500, verified").isEqualByComparingTo("750");
        assertThat(afterP1.retained).isEqualByComparingTo("750");
        assertThat(afterP1.navValue).as("cash minus what is held for the recipient, once").isEqualByComparingTo("750");

        // ---- a mixed contribution and loan payment, split by the loan engine
        Loan loan = loan(s.memberA().id(), "3500");
        UUID p2 = confirmedPayment("3500", contribution(NEXT, "500"), loanLine(loan.getId(), "3000"));
        postPayment(accC, p2);
        Books afterP2 = checkpoint("after the mixed payment");
        assertThat(afterP2.cash).as("2000 collected, less the 500 of principal still lent out").isEqualByComparingTo("1500");
        assertThat(afterP2.owed).as("the obligation is now fully paid: rotating 1000").isEqualByComparingTo("1000");
        assertThat(afterP2.retained).isEqualByComparingTo("1000");
        assertThat(loanRepository.findById(loan.getId()).orElseThrow().getOutstandingPrincipal()).isEqualByComparingTo("500");
        assertThat(afterP2.loans).isEqualByComparingTo("500");
        assertThat(afterP2.navValue).as("1500 cash + 500 owed by the borrower - 1000 held").isEqualByComparingTo("1000");
        // ---- a contribution-only reversal on the second period, taking back exactly what that payment contributed
        UUID p3 = confirmedPayment("1000", contribution(AFTER, "1000"));
        postPayment(accC, p3);
        Books afterP3 = checkpoint("after a payment on the second period");
        assertThat(afterP3.cash).isEqualByComparingTo("2500");
        assertThat(afterP3.retained).isEqualByComparingTo("1500");
        assertThat(afterP3.owed).as("the rotating half of 1000 is owed to the second recipient").isEqualByComparingTo("1500");
        UUID reversal = UUID.fromString(data(act(s.accountantA(), p3, "/reversals", "{\"reason\":\"Paid for the wrong month\"}")).path("id").asText());
        assertThat(status(reversalAction(s.accountantB(), reversal, "/approve"))).as("not the fund authority").isEqualTo(403);
        assertThat(status(reversalAction(s.president(), reversal, "/approve"))).isEqualTo(200);
        Books afterReversal = checkpoint("after the reversal");
        assertThat(afterReversal.cash).isEqualByComparingTo("1500");
        assertThat(afterReversal.retained).isEqualByComparingTo("1000");
        assertThat(paid(AFTER)).isEqualByComparingTo("0");
        assertThat(afterReversal.owed).isEqualByComparingTo("1000");

        // ---- a legacy (unverified) contribution is held, then reconciled by an independent authority: no new cash
        assertThat(status(saveLine(AFTER, "1000"))).isEqualTo(200);
        Books legacy = checkpoint("after a legacy contribution");
        assertThat(legacy.cash).isEqualByComparingTo("2500");
        assertThat(legacy.unreconciled).as("held for the recipient, not payable").isEqualByComparingTo("500");
        assertThat(legacy.owed).isEqualByComparingTo("1000");
        assertThat(dec(entitlement(period2), "payableEntitlement")).isEqualByComparingTo("0");

        UUID p4 = confirmedPayment("1000", contribution(AFTER, "1000")); // paid 2000: only this part is verified
        postPayment(accC, p4);
        Books afterP4 = checkpoint("after a verified payment on the same obligation");
        assertThat(afterP4.cash).isEqualByComparingTo("3500");
        assertThat(afterP4.unreconciled).isEqualByComparingTo("500");
        assertThat(dec(entitlement(period2), "outstandingEntitlement")).isEqualByComparingTo("500");

        UUID reconciliation = UUID.fromString(data(mockMvc.perform(post(base() + "/rotation/reconciliation/requests")
                        .header("Authorization", bearer(s.accountantA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contributionId\":\"%s\",\"amount\":1000,\"method\":\"CASH_RECEIPT_CONFIRMED\",\"basis\":\"Counted at the meeting\"}"
                                .formatted(contributionId(AFTER)))).andReturn()).path("id").asText());
        long ledgerBefore = ledgerRows();
        assertThat(status(mockMvc.perform(post(base() + "/rotation/reconciliation/requests/" + reconciliation + "/approve")
                .header("Authorization", bearer(s.president()))).andReturn())).isEqualTo(200);
        Books reconciled = checkpoint("after the legacy contribution was reconciled");
        assertThat(ledgerRows()).as("reconciliation creates no ledger entry").isEqualTo(ledgerBefore);
        assertThat(reconciled.cash).as("no new cash: reconciliation only confirms what was already received").isEqualByComparingTo(afterP4.cash);
        assertThat(reconciled.unreconciled).isEqualByComparingTo("0");
        assertThat(reconciled.owed).as("the same money moved from held to owed: the liability is unchanged").isEqualByComparingTo("2000");
        assertThat(reconciled.liabilities).isEqualByComparingTo(afterP4.liabilities);

        // ---- payouts: partial, then the rest, then nothing more
        UUID payout1 = requestPayout(period1, "600");
        approve(payout1);
        confirm(payout1, "BK-1");
        Books afterPayout1 = checkpoint("after a partial payout");
        assertThat(afterPayout1.cash).isEqualByComparingTo(reconciled.cash.subtract(new BigDecimal("600")));
        assertThat(afterPayout1.owed).isEqualByComparingTo("1400");
        assertThat(afterPayout1.paidOut).isEqualByComparingTo("600");
        assertThat(afterPayout1.navValue).as("paying a recipient lowers cash and liability together: members' value is unchanged")
                .isEqualByComparingTo(reconciled.navValue);

        MvcResult tooMuch = requestPayoutRaw(period1, "500");
        assertThat(code(tooMuch)).isEqualTo("PAYOUT_EXCEEDS_ENTITLEMENT");
        UUID payout2 = requestPayout(period1, "400");
        approve(payout2);
        confirm(payout2, "BK-2");
        UUID payout3 = requestPayout(period2, null);
        approve(payout3);
        confirm(payout3, "BK-3");
        Books afterAll = checkpoint("after every entitlement was paid");
        assertThat(afterAll.owed).isEqualByComparingTo("0");
        assertThat(afterAll.paidOut).isEqualByComparingTo("2000");
        assertThat(code(requestPayoutRaw(period2, null))).isEqualTo("NO_ENTITLEMENT");
        assertThat(afterAll.navValue).isEqualByComparingTo(reconciled.navValue);

        // ---- the history of what each officer did is separated: nobody verified, posted or approved their own work
        assertThat(jdbc.queryForObject("select count(*) from member_payments where cooperative_id = ? and (verified_by = posted_by or submitted_by = verified_by)",
                Long.class, s.id())).isZero();
    }

    @Test
    void anImportedHistoryReconcilesInTheBooksAndAddsNoRotationEligibility() throws Exception {
        Scheme fresh = fixtures.scheme("HYBRID");
        // contributions, a fine payment, income and expense: every row has authoritative records
        String a = fresh.memberA().username();
        List<String[]> withLoan = List.of(
                new String[] {"CONTRIBUTION", "2022-01-10", a, "4000", "C1", "2022", "1", "", "", ""},
                new String[] {"LOAN_DISBURSEMENT", "2022-02-01", a, "1000", "L1", "", "", "LN1", "", ""},
                new String[] {"MEMBER_PAYOUT", "2022-09-01", a, "100", "PO", "", "", "", "", ""});
        // loan and payout rows have no authoritative record to update, so the whole file is refused
        JsonNode invalid = importFile(fresh, List.of(
                new String[] {"SETTING", "", "", "", "OPENING_BALANCE", "0"},
                new String[] {"SETTING", "", "", "", "DECLARED_CLOSING_BALANCE", "3000"}), withLoan);
        assertThat(invalid.path("canApprove").asBoolean()).isFalse();
        assertThat(invalid.path("errorCount").asInt()).isEqualTo(2);
        List<String[]> valid = List.of(
                new String[] {"CONTRIBUTION", "2022-01-10", a, "4000", "C1", "2022", "1", "", "", ""},
                new String[] {"FINE_PAYMENT", "2022-06-01", a, "200", "F1", "", "", "", "", ""},
                new String[] {"OTHER_INCOME", "2022-07-01", "", "300", "OI", "", "", "", "", ""},
                new String[] {"GENERAL_EXPENSE", "2022-08-01", "", "400", "EX", "", "", "", "", ""});
        // 4000 + 200 + 300 - 400 = 4100
        List<String[]> corrected = List.of(
                new String[] {"SETTING", "", "", "", "OPENING_BALANCE", "0"},
                new String[] {"SETTING", "", "", "", "DECLARED_CLOSING_BALANCE", "4100"});
        JsonNode batch = importFile(fresh, corrected, valid);
        assertThat(batch.path("errorCount").asInt()).as(batch.toString()).isZero();
        assertThat(batch.path("balanceVariance").decimalValue()).isEqualByComparingTo("0");
        UUID id = UUID.fromString(batch.path("id").asText());
        assertThat(status(mockMvc.perform(post("/api/v1/cooperatives/" + fresh.id() + "/historical-transactions/" + id + "/approve")
                .header("Authorization", bearer(fresh.president()))).andReturn())).isEqualTo(200);

        // authoritative records, not only the ledger
        assertThat(jdbc.queryForObject("select paid_amount from contributions where cooperative_id = ?", BigDecimal.class, fresh.id())).isEqualByComparingTo("4000");
        assertThat(jdbc.queryForObject("select status from contributions where cooperative_id = ?", String.class, fresh.id())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select outstanding_amount from contributions where cooperative_id = ?", BigDecimal.class, fresh.id())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select outstanding_amount from fines where cooperative_id = ?", BigDecimal.class, fresh.id())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from fine_payments where cooperative_id = ? and status = 'APPROVED'", Long.class, fresh.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from income_expense_transactions where cooperative_id = ? and approval_status = 'APPROVED'", Long.class, fresh.id())).isEqualTo(2);
        // nothing was imported for loans or payouts, and no loan is owed
        assertThat(jdbc.queryForObject("select count(*) from loans where cooperative_id = ?", Long.class, fresh.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payout_runs where cooperative_id = ?", Long.class, fresh.id())).isZero();

        // the books: cash equals the reconciled closing balance, counted once
        BigDecimal cash = jdbc.queryForObject("select coalesce(sum(credit_amount),0) - coalesce(sum(debit_amount),0) from financial_ledger where cooperative_id = ? and status = 'APPROVED'",
                BigDecimal.class, fresh.id());
        assertThat(cash).isEqualByComparingTo("4100");
        var nav = shareValuationService.calculate(fresh.id());
        assertThat(nav.getAvailableFunds()).isEqualByComparingTo("4100");
        assertThat(nav.getOutstandingLoans()).isEqualByComparingTo("0");
        assertThat(nav.getUnpaidPenalties()).isEqualByComparingTo("0");
        assertThat(nav.getLiabilities()).as("nothing imported is known to be rotating").isEqualByComparingTo("0");
        assertThat(nav.getUnclassifiedFunds()).as("the contribution's class was not stated").isEqualByComparingTo("4000");
        assertThat(nav.getTotalIkiminaValue()).isEqualByComparingTo("4100");
        // no invented rotation attribution and no payout eligibility
        assertThat(jdbc.queryForObject("select count(*) from contributions where cooperative_id = ? and (rotation_period_id is not null or rotating_bps_snapshot is not null)",
                Long.class, fresh.id())).isZero();
        JsonNode summary = data(mockMvc.perform(get("/api/v1/cooperatives/" + fresh.id() + "/reports/model-summary")
                .header("Authorization", bearer(fresh.accountantA()))).andReturn());
        assertThat(summary.path("rotatingCollected").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.path("recipientEntitlementsOwed").decimalValue()).isEqualByComparingTo("0");
        // the report agrees with the ledger
        JsonNode report = data(mockMvc.perform(get("/api/v1/cooperatives/" + fresh.id() + "/reports/model-report")
                .header("Authorization", bearer(fresh.accountantA()))).andReturn());
        assertThat(report.path("earnings").path("netEarnings").decimalValue()).as("200 fine + 300 income - 400 expense").isEqualByComparingTo("100");
        assertThat(report.path("position").path("availableFunds").decimalValue()).isEqualByComparingTo("4100");
    }

    // ============================================================ the books

    /** The figures every checkpoint reconciles. */
    private record Books(
            BigDecimal cash, BigDecimal loans, BigDecimal retained, BigDecimal owed, BigDecimal unreconciled,
            BigDecimal liabilities, BigDecimal navValue, BigDecimal paidOut) {}

    private Books checkpoint(String label) throws Exception {
        JsonNode summary = data(getAs(s.accountantA(), "/reports/model-summary"));
        var nav = shareValuationService.calculate(s.id());
        BigDecimal ledgerCash = expectedCash();
        BigDecimal collected = jdbc.queryForObject("select coalesce(sum(paid_amount), 0) from contributions where cooperative_id = ?", BigDecimal.class, s.id());

        assertThat(dec(summary, "availableFunds")).as(label + ": cash in the summary equals the ledger").isEqualByComparingTo(ledgerCash);
        assertThat(nav.getAvailableFunds()).as(label + ": cash in the valuation equals the ledger").isEqualByComparingTo(ledgerCash);
        assertThat(dec(summary, "postedCollections")).as(label + ": collections equal the contribution records").isEqualByComparingTo(collected);
        // (a corrected total reverses the previous credit and appends the new total, so only the live credits count)
        assertThat(jdbc.queryForObject("select coalesce(sum(credit_amount), 0) from financial_ledger where cooperative_id = ?"
                        + " and status = 'APPROVED' and transaction_type = 'REGULAR_CONTRIBUTION' and source_entity_type = 'CONTRIBUTION'", BigDecimal.class, s.id()))
                .as(label + ": contribution ledger equals contribution records").isEqualByComparingTo(collected);
        // every collected rupee is in exactly one category
        assertThat(dec(summary, "retainedCollections").add(dec(summary, "unclassifiedCollections")).add(dec(summary, "unresolvedRotatingCollections"))
                .add(dec(summary, "rotatingCollected")).add(dec(summary, "rotatingRequiringReconciliation")))
                .as(label + ": categories add up to collections").isEqualByComparingTo(collected);
        // liabilities are exactly their parts, and the valuation subtracts them once
        assertThat(nav.getVerifiedRotatingLiabilities()).as(label + ": verified owed").isEqualByComparingTo(dec(summary, "recipientEntitlementsOwed"));
        assertThat(nav.getLiabilities()).as(label + ": liabilities are the sum of their parts")
                .isEqualByComparingTo(nav.getVerifiedRotatingLiabilities().add(nav.getUnreconciledRotatingFunds()).add(nav.getUnresolvedRotatingFunds()));
        assertThat(nav.getTotalIkiminaValue()).as(label + ": value = cash + assets - liabilities, once")
                .isEqualByComparingTo(nav.getAvailableFunds().add(nav.getOutstandingLoans()).add(nav.getUnpaidInterest())
                        .add(nav.getUnpaidPenalties()).add(nav.getOtherAssets()).subtract(nav.getLiabilities()));
        assertThat(dec(summary, "totalRotatingLiabilities")).isEqualByComparingTo(nav.getLiabilities());
        assertThat(dec(summary, "availableAfterEntitlements")).isEqualByComparingTo(ledgerCash.subtract(nav.getLiabilities()));
        // entitlements per period agree with the scheme total
        BigDecimal owedAcrossPeriods = BigDecimal.ZERO;
        for (JsonNode period : data(getAs(s.accountantA(), "/rotation/entitlements"))) {
            owedAcrossPeriods = owedAcrossPeriods.add(dec(period, "outstandingEntitlement"));
        }
        assertThat(owedAcrossPeriods).as(label + ": entitlements add up to what is owed").isEqualByComparingTo(dec(summary, "recipientEntitlementsOwed"));
        // payouts: what the summary says was paid is what left the ledger as rotation payouts
        BigDecimal ledgerPayouts = jdbc.queryForObject("select coalesce(sum(debit_amount), 0) from financial_ledger where cooperative_id = ? and status = 'APPROVED'"
                + " and source_entity_type = 'ROTATION_PAYOUT'", BigDecimal.class, s.id());
        assertThat(dec(summary, "payoutsDisbursed")).as(label + ": payouts equal the ledger").isEqualByComparingTo(ledgerPayouts);
        // verified rotating can never exceed the rotating part of what is paid
        assertThat(dec(summary, "rotatingCollected")).isLessThanOrEqualTo(collected);
        return new Books(
                ledgerCash, nav.getOutstandingLoans(), dec(summary, "retainedCollections"), dec(summary, "recipientEntitlementsOwed"),
                dec(summary, "rotatingRequiringReconciliation"), nav.getLiabilities(), nav.getTotalIkiminaValue(), ledgerPayouts);
    }

    /**
     * Available cash recomputed from first principles, independent of the application formula: approved ledger income
     * (contributions, fines, other income, interest and investment profit) less approved expenses, outflows and member
     * payouts, less the principal still lent out. Loan principal moves are not cash on their own: the borrower owes it,
     * and that is what the loans term of the valuation adds back.
     */
    private BigDecimal expectedCash() {
        BigDecimal income = jdbc.queryForObject("select coalesce(sum(credit_amount), 0) from financial_ledger where cooperative_id = ?"
                + " and status = 'APPROVED' and transaction_type in ('REGULAR_CONTRIBUTION','SPECIAL_CONTRIBUTION','FINE_PAYMENT','OTHER_INCOME',"
                + "'INVESTMENT_CAPITAL_RETURN','SHARE_PURCHASE','LOAN_INTEREST_PAYMENT','INVESTMENT_PROFIT')", BigDecimal.class, s.id());
        BigDecimal out = jdbc.queryForObject("select coalesce(sum(debit_amount), 0) from financial_ledger where cooperative_id = ?"
                + " and status = 'APPROVED' and transaction_type in ('GENERAL_EXPENSE','INTEREST_EXPENSE','INVESTMENT_OUTFLOW','MEMBER_PAYOUT')",
                BigDecimal.class, s.id());
        BigDecimal lentOut = jdbc.queryForObject("select coalesce(sum(outstanding_principal), 0) from loans where cooperative_id = ?"
                + " and status in ('ACTIVE','OVERDUE','WRITTEN_OFF')", BigDecimal.class, s.id());
        return income.subtract(out).subtract(lentOut);
    }

    private long ledgerRows() {
        return jdbc.queryForObject("select count(*) from financial_ledger where cooperative_id = ?", Long.class, s.id());
    }

    // ============================================================ payments

    private String contribution(YearMonth month, String amount) {
        return "{\"type\":\"CONTRIBUTION\",\"amount\":%s,\"periodYear\":%d,\"periodMonth\":%d}".formatted(amount, month.getYear(), month.getMonthValue());
    }

    private String loanLine(UUID loanId, String amount) {
        return "{\"type\":\"LOAN_REPAYMENT\",\"amount\":%s,\"loanId\":\"%s\"}".formatted(amount, loanId);
    }

    /** Submitted by the payer, confirmed by a statement line imported by officer A, verified by B. Returns the payment id. */
    private UUID confirmedPayment(String total, String... allocations) throws Exception {
        String reference = "LC" + (++referenceSeed) + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String body = "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\",\"transactionReference\":\"%s\",\"allocations\":[%s]}"
                .formatted(total, LocalDate.now(KIGALI), reference, String.join(",", allocations));
        MvcResult submitted = mockMvc.perform(post(base() + "/payments")
                        .header("Authorization", bearer(s.memberA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(status(submitted)).as(submitted.getResponse().getContentAsString()).isEqualTo(200);
        UUID id = UUID.fromString(data(submitted).path("id").asText());
        assertThat(status(act(s.accountantA(), id, "/start-review", null))).isEqualTo(200);
        MvcResult imported = mockMvc.perform(post(base() + "/payments/trusted-transactions")
                        .header("Authorization", bearer(s.accountantA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"source\":\"MOBILE_MONEY_STATEMENT_IMPORT\",\"channel\":\"MTN_MOMO\",\"reference\":\"%s\",\"amount\":%s,\"currency\":\"RWF\",\"status\":\"SUCCESS\"}]}"
                                .formatted(reference, total)))
                .andReturn();
        assertThat(data(imported).path("results").get(0).path("result").asText()).isEqualTo("CREATED");
        MvcResult verified = act(s.accountantB(), id, "/verify",
                "{\"method\":\"MOBILE_MONEY_RECONCILED\",\"basis\":\"Statement line matched\",\"reference\":\"STMT-%d\"}".formatted(referenceSeed));
        assertThat(status(verified)).as(verified.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    private void postPayment(Actor poster, UUID payment) throws Exception {
        MvcResult posted = act(poster, payment, "/post", null);
        assertThat(status(posted)).as(posted.getResponse().getContentAsString()).isEqualTo(200);
    }

    // ============================================================ rotation, payouts, reversals

    private UUID requestPayout(UUID period, String amount) throws Exception {
        MvcResult result = requestPayoutRaw(period, amount);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(data(result).path("id").asText());
    }

    private MvcResult requestPayoutRaw(UUID period, String amount) throws Exception {
        String body = amount == null
                ? "{\"rotationPeriodId\":\"" + period + "\"}"
                : "{\"rotationPeriodId\":\"" + period + "\",\"amount\":" + amount + "}";
        return mockMvc.perform(post(base() + "/rotation/payouts")
                        .header("Authorization", bearer(s.accountantA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private void approve(UUID payout) throws Exception {
        assertThat(status(mockMvc.perform(post(base() + "/rotation/payouts/" + payout + "/approve")
                .header("Authorization", bearer(s.president()))).andReturn())).isEqualTo(200);
    }

    private void confirm(UUID payout, String reference) throws Exception {
        MvcResult confirmed = mockMvc.perform(post(base() + "/rotation/payouts/" + payout + "/confirm-disbursement")
                        .header("Authorization", bearer(s.accountantB()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"BANK_TRANSFER\",\"reference\":\"" + reference + "\",\"note\":\"Transferred\"}"))
                .andReturn();
        assertThat(status(confirmed)).as(confirmed.getResponse().getContentAsString()).isEqualTo(200);
    }

    private MvcResult reversalAction(Actor who, UUID reversal, String suffix) throws Exception {
        return mockMvc.perform(post(base() + "/payments/reversals/" + reversal + suffix).header("Authorization", bearer(who))).andReturn();
    }

    private void configureRotation(int rotating, int retained) throws Exception {
        MvcResult result = mockMvc.perform(post(base() + "/rotation/configurations")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveYear", NEXT.getYear(), "effectiveMonth", NEXT.getMonthValue(),
                                "rotatingBps", rotating, "retainedBps", retained))))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
    }

    private void activateCycle(List<UUID> recipients) throws Exception {
        MvcResult created = mockMvc.perform(post(base() + "/rotation/cycles")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startYear", NEXT.getYear(), "startMonth", NEXT.getMonthValue(), "recipientUserIds", recipients))))
                .andReturn();
        assertThat(status(created)).isEqualTo(200);
        String cycleId = data(created).path("id").asText();
        assertThat(status(mockMvc.perform(post(base() + "/rotation/cycles/" + cycleId + "/activate")
                .header("Authorization", "Bearer " + fixtures.superAdminToken())).andReturn())).isEqualTo(200);
    }

    private MvcResult saveLine(YearMonth month, String paid) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("memberUserId", s.memberA().id());
        line.put("paidAmount", new BigDecimal(paid));
        if (new BigDecimal(paid).signum() > 0) {
            line.put("paymentDate", LocalDate.now(KIGALI).toString());
        }
        return mockMvc.perform(put(base() + "/contributions/period?year=" + month.getYear() + "&month=" + month.getMonthValue())
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", List.of(line)))))
                .andReturn();
    }

    // ============================================================ helpers

    private JsonNode importFile(Scheme target, List<String[]> members, List<String[]> txs) throws Exception {
        try (org.apache.poi.ss.usermodel.Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            fill(workbook.createSheet("Members and Configuration"),
                    List.of("Row Type", "Username", "Full Name", "Share Count", "Setting", "Value"), members);
            fill(workbook.createSheet("Historical Transactions"),
                    List.of("Row Type", "Date", "Member Username", "Amount", "Original Reference", "Period Year", "Period Month",
                            "Loan Reference", "Operating Model At The Time", "Description"), txs);
            workbook.write(out);
            MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .multipart("/api/v1/cooperatives/" + target.id() + "/historical-transactions/preview")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", "history.xlsx",
                                    MediaType.APPLICATION_OCTET_STREAM_VALUE, out.toByteArray()))
                            .header("Authorization", bearer(target.accountantA())))
                    .andReturn();
            assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
            return data(result);
        }
    }

    private static void fill(org.apache.poi.ss.usermodel.Sheet sheet, List<String> headers, List<String[]> rows) {
        org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            header.createCell(c).setCellValue(headers.get(c));
        }
        int r = 1;
        for (String[] cells : rows) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(r++);
            for (int c = 0; c < cells.length; c++) {
                row.createCell(c).setCellValue(cells[c]);
            }
        }
    }

    private Loan loan(UUID memberId, String principal) {
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(s.id())
                .memberUserId(memberId)
                .requestedAmount(new BigDecimal(principal))
                .approvedAmount(new BigDecimal(principal))
                .principalAmount(new BigDecimal(principal))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(BigDecimal.ZERO)
                .outstandingPrincipal(new BigDecimal(principal))
                .outstandingInterest(BigDecimal.ZERO)
                .requestDate(today.minusMonths(1))
                .disbursementDate(today.minusMonths(1))
                .dueDate(today.plusMonths(5))
                .status(LoanStatus.ACTIVE)
                .build());
    }

    private void strict(boolean value) {
        Object target = AopTestUtils.getUltimateTargetObject(checkService);
        ReflectionTestUtils.setField(target, "requireTrustedConfirmation", value);
    }

    private MvcResult act(Actor who, UUID payment, String suffix, String json) throws Exception {
        var request = post(base() + "/payments/" + payment + suffix).header("Authorization", bearer(who));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult getAs(Actor who, String suffix) throws Exception {
        return mockMvc.perform(get(base() + suffix).header("Authorization", bearer(who))).andReturn();
    }

    private JsonNode entitlement(UUID period) throws Exception {
        for (JsonNode node : data(getAs(s.accountantA(), "/rotation/entitlements"))) {
            if (node.path("periodId").asText().equals(period.toString())) {
                return node;
            }
        }
        throw new AssertionError("No entitlement row for " + period);
    }

    private UUID periodOf(YearMonth month) {
        return jdbc.queryForObject("select id from rotation_periods where cooperative_id = ? and period_year = ? and period_month = ?",
                UUID.class, s.id(), month.getYear(), month.getMonthValue());
    }

    private UUID contributionId(YearMonth month) {
        return jdbc.queryForObject("select id from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                UUID.class, s.id(), s.memberA().id(), month.getYear(), month.getMonthValue());
    }

    private BigDecimal paid(YearMonth month) {
        return jdbc.queryForObject("select paid_amount from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, s.id(), s.memberA().id(), month.getYear(), month.getMonthValue());
    }

    private String base() {
        return "/api/v1/cooperatives/" + s.id();
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.path(field).decimalValue();
    }

    private static String bearer(Actor who) {
        return "Bearer " + who.token();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String code(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
