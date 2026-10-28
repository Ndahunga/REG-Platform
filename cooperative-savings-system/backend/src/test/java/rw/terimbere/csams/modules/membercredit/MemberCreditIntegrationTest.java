package rw.terimbere.csams.modules.membercredit;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * Excess money is held for the member, never income: it is classified, journalled and ledgered, reconciles, can be applied
 * later exactly once, stays inside its scheme, and counts as verified money when it reaches a rotating obligation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberCreditIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final YearMonth NOW = YearMonth.now(KIGALI);
    private static final YearMonth NEXT = NOW.plusMonths(1);
    private static final String PROVIDER_EVIDENCE =
            "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"Matched against the trusted record\",\"reference\":\"PROV-1\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    private SchemeFixtures fixtures;
    private Scheme s;
    private Actor payer;
    private Actor reviewer;
    private Actor verifier;
    private Actor poster;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
        build("ACCUMULATING");
    }

    private void build(String model) throws Exception {
        s = fixtures.scheme(model);
        payer = s.memberA();
        reviewer = s.accountantA();
        verifier = s.accountantB();
        poster = fixtures.register(s.id(), "ACCOUNTANT");
        // 30 shares of 1,000: the member owes 30,000 for a month
        jdbc.update("update cooperative_memberships set share_count = 30 where cooperative_id = ? and user_id = ?", s.id(), payer.id());
    }

    // ============================================================ receiving: the 100,000 payment

    @Test
    void anOverpaymentIsSplitIntoContributionLoanAndHeldCreditAndTheCreditIsNeverIncome() throws Exception {
        row(NOW);
        Loan loan = loan("100000", "5000");
        BigDecimal fundsBefore = availableFunds();

        UUID payment = verified("100000",
                contribution(NOW, "30000"), loanAlloc(loan.getId(), "50000"), credit("20000", "UNAPPLIED_CREDIT", null));
        MvcResult posted = act(poster, payment, "/post", null);
        assertThat(status(posted)).as(posted.getResponse().getContentAsString()).isEqualTo(200);

        // the contribution and the loan engine did their usual work
        assertThat(paid(NOW)).isEqualByComparingTo("30000");
        Loan after = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(after.getOutstandingInterest()).isEqualByComparingTo("0");
        assertThat(after.getOutstandingPrincipal()).as("5,000 interest and 45,000 principal").isEqualByComparingTo("55000");

        // the excess is held, classified, owned by this member of this scheme, and sums with the rest to the payment
        List<Map<String, Object>> journal = jdbc.queryForList(
                "select * from member_credit_entries where cooperative_id = ?", s.id());
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).get("entry_type")).isEqualTo("RECEIVED");
        assertThat(journal.get(0).get("purpose")).isEqualTo("UNAPPLIED_CREDIT");
        assertThat(journal.get(0).get("member_user_id")).isEqualTo(payer.id());
        assertThat(((BigDecimal) journal.get(0).get("amount"))).isEqualByComparingTo("20000");
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(amount) from member_payment_postings where payment_id = ?", BigDecimal.class, payment))
                .isEqualByComparingTo("100000");

        // ledger: one MEMBER_CREDIT_RECEIVED credit of 20,000 and no income-type entry for it
        assertThat(jdbc.queryForObject("select sum(credit_amount) from financial_ledger where cooperative_id = ? and transaction_type = 'MEMBER_CREDIT_RECEIVED'",
                BigDecimal.class, s.id())).isEqualByComparingTo("20000");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ? and transaction_type in ('OTHER_INCOME','FINE_PAYMENT') ", s.id()))
                .isZero();

        // the group's available funds moved only by what is group money: 30,000 contribution + 5,000 interest, less the
        // loan principal still out; the held credit is not in it
        BigDecimal expected = fundsBefore.add(new BigDecimal("30000")).add(new BigDecimal("5000")).add(new BigDecimal("45000"));
        assertThat(availableFunds()).as("credit excluded from available funds").isEqualByComparingTo(expected);
        JsonNode summary = data(getAs(poster, "/reports/model-summary"));
        assertThat(summary.path("memberCreditHeld").decimalValue()).isEqualByComparingTo("20000");
        JsonNode report = data(getAs(poster, "/reports/model-report"));
        assertThat(report.path("earnings").path("totalIncome").decimalValue()).as("only the 5,000 interest is income").isEqualByComparingTo("5000");

        // the journal and the ledger agree
        assertThat(journalBalance()).isEqualByComparingTo("20000");
        assertThat(ledgerCreditBalance()).isEqualByComparingTo("20000");
        // the member sees their own credit
        JsonNode mine = data(getAs(payer, "/payments/credits"));
        assertThat(mine.path("totalHeld").decimalValue()).isEqualByComparingTo("20000");
        assertThat(mine.path("balances")).hasSize(1);
    }

    @Test
    void anExactPaymentLeavesNoCreditAndAnUnclassifiedExcessIsStillRefused() throws Exception {
        row(NOW);
        UUID exact = verified("30000", contribution(NOW, "30000"));
        assertThat(status(act(poster, exact, "/post", null))).isEqualTo(200);
        assertThat(count("select count(*) from member_credit_entries where cooperative_id = ?", s.id())).isZero();
        assertThat(journalBalance()).isEqualByComparingTo("0");

        // paying more than a period needs without saying what the rest is for is refused, never absorbed
        row(NEXT);
        MvcResult tooMuch = submitRaw(payer, body("35000", contribution(NEXT, "35000")));
        assertThat(error(tooMuch).path("code").asText()).isEqualTo("ALLOCATION_EXCEEDS_OUTSTANDING");
        // and a credit has to say what it is held for
        MvcResult noPurpose = submitRaw(payer, body("5000", "{\"type\":\"MEMBER_CREDIT\",\"amount\":5000}"));
        assertThat(error(noPurpose).path("code").asText()).isEqualTo("INVALID_ALLOCATION");
    }

    @Test
    void aFailingAllocationLeavesNoCreditBehindBecauseThePostingIsAtomic() throws Exception {
        row(NOW);
        Loan loan = loan("10000", "0");
        UUID payment = verified("5000", loanAlloc(loan.getId(), "2000"), credit("3000", "UNAPPLIED_CREDIT", null));
        jdbc.update("update loans set status = 'CLOSED' where id = ?", loan.getId());

        MvcResult failed = act(poster, payment, "/post", null);
        assertThat(status(failed)).isGreaterThanOrEqualTo(400);
        assertThat(count("select count(*) from member_credit_entries where cooperative_id = ?", s.id())).isZero();
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ? and transaction_type = 'MEMBER_CREDIT_RECEIVED'", s.id())).isZero();
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("VERIFIED");
    }

    // ============================================================ applying later

    @Test
    void heldCreditIsAppliedLaterToALoanAndAContributionAndCannotBeSpentTwice() throws Exception {
        row(NEXT);
        Loan loan = loan("100000", "0");
        UUID payment = verified("20000", credit("20000", "UNAPPLIED_CREDIT", null));
        assertThat(status(act(poster, payment, "/post", null))).isEqualTo(200);
        BigDecimal fundsBefore = availableFunds();

        MvcResult toLoan = apply(poster, "k-loan-1", "UNAPPLIED_CREDIT", null, "LOAN_REPAYMENT", null, null, loan.getId(), "10000");
        assertThat(status(toLoan)).as(toLoan.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(loanRepository.findById(loan.getId()).orElseThrow().getOutstandingPrincipal()).isEqualByComparingTo("90000");

        MvcResult toContribution = apply(poster, "k-contribution-1", "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "10000");
        assertThat(status(toContribution)).as(toContribution.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(paid(NEXT)).isEqualByComparingTo("10000");

        // spent: the balance is zero, the journal and the ledger still agree, and nothing more can be taken
        assertThat(journalBalance()).isEqualByComparingTo("0");
        assertThat(ledgerCreditBalance()).isEqualByComparingTo("0");
        MvcResult nothingLeft = apply(poster, "k-more", "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "1");
        assertThat(status(nothingLeft)).isEqualTo(422);
        assertThat(code(nothingLeft)).isEqualTo("CREDIT_INSUFFICIENT");

        // applying makes it group money exactly once: 10,000 of loan principal and 10,000 of contribution
        assertThat(availableFunds().subtract(fundsBefore)).isEqualByComparingTo("20000");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ? and transaction_type = 'MEMBER_CREDIT_APPLIED'", s.id())).isEqualTo(2);
        assertThat(data(getAs(poster, "/reports/model-summary")).path("memberCreditHeld").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void anApplicationIsIdempotentPerKeyAndAKeyCannotBeReusedForSomethingElse() throws Exception {
        row(NEXT);
        UUID payment = verified("20000", credit("20000", "UNAPPLIED_CREDIT", null));
        assertThat(status(act(poster, payment, "/post", null))).isEqualTo(200);

        MvcResult first = apply(poster, "same-key", "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "5000");
        MvcResult again = apply(poster, "same-key", "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "5000");
        assertThat(status(first)).isEqualTo(200);
        assertThat(status(again)).isEqualTo(200);
        assertThat(data(again).path("id").asText()).isEqualTo(data(first).path("id").asText());
        assertThat(paid(NEXT)).as("applied once").isEqualByComparingTo("5000");
        assertThat(journalBalance()).isEqualByComparingTo("15000");

        MvcResult different = apply(poster, "same-key", "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "9000");
        assertThat(status(different)).isEqualTo(409);
        assertThat(code(different)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(status(applyWithoutKey(poster))).isEqualTo(422);
    }

    @Test
    void twoOfficersSpendingTheSameCreditAtOnceCannotBothSucceed() throws Exception {
        row(NEXT);
        UUID payment = verified("10000", credit("10000", "UNAPPLIED_CREDIT", null));
        assertThat(status(act(poster, payment, "/post", null))).isEqualTo(200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (Actor officer : List.of(poster, reviewer)) {
            results.add(pool.submit(() -> {
                go.await();
                return status(apply(officer, "race-" + officer.id(), "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "10000"));
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).as("exactly one application wins: " + statuses).containsExactlyInAnyOrder(200, 422);
        assertThat(paid(NEXT)).isEqualByComparingTo("10000");
        assertThat(journalBalance()).isEqualByComparingTo("0");
    }

    @Test
    void aCreditCanOnlyBeUsedForWhatItWasSetAsideFor() throws Exception {
        row(NEXT);
        Loan loan = loan("100000", "0");
        Loan other = loan("50000", "0");
        UUID payment = verified("30000",
                credit("10000", "ADVANCE_CONTRIBUTION", null),
                credit("10000", "LOAN_PREPAYMENT", loan.getId()),
                credit("10000", "UNAPPLIED_CREDIT", null));
        assertThat(status(act(poster, payment, "/post", null))).isEqualTo(200);

        // an advance contribution is for contributions only
        MvcResult advanceToLoan = apply(poster, "a1", "ADVANCE_CONTRIBUTION", null, "LOAN_REPAYMENT", null, null, loan.getId(), "1000");
        assertThat(code(advanceToLoan)).isEqualTo("CREDIT_PURPOSE_MISMATCH");
        // a loan prepayment is for its own loan only
        MvcResult prepayContribution = apply(poster, "a2", "LOAN_PREPAYMENT", loan.getId(), "CONTRIBUTION", NEXT, null, null, "1000");
        assertThat(code(prepayContribution)).isEqualTo("CREDIT_PURPOSE_MISMATCH");
        MvcResult prepayOtherLoan = apply(poster, "a3", "LOAN_PREPAYMENT", loan.getId(), "LOAN_REPAYMENT", null, null, other.getId(), "1000");
        assertThat(code(prepayOtherLoan)).isEqualTo("CREDIT_PURPOSE_MISMATCH");
        // each bucket is separate: the advance cannot be drawn from the unapplied bucket's balance or the other way round
        assertThat(status(apply(poster, "ok1", "ADVANCE_CONTRIBUTION", null, "CONTRIBUTION", NEXT, null, null, "10000"))).isEqualTo(200);
        assertThat(code(apply(poster, "ok2", "ADVANCE_CONTRIBUTION", null, "CONTRIBUTION", NEXT, null, null, "1"))).isEqualTo("CREDIT_INSUFFICIENT");
        assertThat(status(apply(poster, "ok3", "LOAN_PREPAYMENT", loan.getId(), "LOAN_REPAYMENT", null, null, loan.getId(), "10000"))).isEqualTo(200);
        assertThat(loanRepository.findById(loan.getId()).orElseThrow().getOutstandingPrincipal()).isEqualByComparingTo("90000");
        assertThat(journalBalance()).as("only the unapplied 10,000 remains").isEqualByComparingTo("10000");
    }

    // ============================================================ who may see and spend it

    @Test
    void creditStaysInsideItsSchemeAndItsMemberAndOnlyOfficersCanApplyIt() throws Exception {
        row(NEXT);
        UUID payment = verified("10000", credit("10000", "UNAPPLIED_CREDIT", null));
        assertThat(status(act(poster, payment, "/post", null))).isEqualTo(200);

        // another member of the same scheme sees none of it and cannot ask for it
        JsonNode theirs = data(getAs(s.memberB(), "/payments/credits"));
        assertThat(theirs.path("balances")).isEmpty();
        assertThat(status(mockMvc.perform(get(base() + "/payments/credits?memberUserId=" + payer.id())
                .header("Authorization", bearer(s.memberB()))).andReturn())).isEqualTo(403);
        // members cannot apply credit, even their own
        assertThat(status(apply(payer, "m1", "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "1000"))).isEqualTo(403);

        // an officer of another scheme can see and spend nothing here
        Scheme other = fixtures.scheme("ACCUMULATING");
        MvcResult foreign = mockMvc.perform(post("/api/v1/cooperatives/" + s.id() + "/payments/credits/apply")
                        .header("Authorization", bearer(other.accountantA()))
                        .header("Idempotency-Key", "x1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(payer.id(), "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "1000")))
                .andReturn();
        assertThat(status(foreign)).isIn(403, 404);
        // naming a member of another scheme from inside this one finds no member
        MvcResult stranger = mockMvc.perform(post(base() + "/payments/credits/apply")
                        .header("Authorization", bearer(poster))
                        .header("Idempotency-Key", "x2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(other.memberA().id(), "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "1000")))
                .andReturn();
        assertThat(status(stranger)).isEqualTo(404);
        assertThat(journalBalance()).isEqualByComparingTo("10000");
    }

    // ============================================================ rotating schemes

    @Test
    void creditAppliedToARotatingObligationCountsAsVerifiedRotatingMoneyAtThePeriodsOwnSplit() throws Exception {
        build("HYBRID");
        Actor r1 = fixtures.register(s.id(), "MEMBER");
        Actor r2 = fixtures.register(s.id(), "MEMBER");
        configure(NEXT, 6000, 4000);
        activateCycle(NEXT, List.of(r1.id(), r2.id()));
        row(NEXT);

        UUID payment = verified("1000", credit("1000", "UNAPPLIED_CREDIT", null));
        assertThat(status(act(poster, payment, "/post", null))).isEqualTo(200);
        JsonNode held = data(getAs(poster, "/reports/model-summary"));
        assertThat(held.path("rotatingCollected").decimalValue()).as("held credit is not collected rotating money").isEqualByComparingTo("0");

        assertThat(status(apply(poster, "rot-1", "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "1000"))).isEqualTo(200);
        JsonNode summary = data(getAs(poster, "/reports/model-summary"));
        assertThat(summary.path("rotatingCollected").decimalValue()).as("60% of the 1,000 applied is verified rotating money")
                .isEqualByComparingTo("600");
        assertThat(summary.path("rotatingRequiringReconciliation").decimalValue()).as("it was verified when the payment posted")
                .isEqualByComparingTo("0");
        assertThat(summary.path("memberCreditHeld").decimalValue()).isEqualByComparingTo("0");
    }

    // ============================================================ helpers

    private String base() {
        return "/api/v1/cooperatives/" + s.id();
    }

    private static String bearer(Actor who) {
        return "Bearer " + who.token();
    }

    private static String ref() {
        return "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private static String contribution(YearMonth month, String amount) {
        return "{\"type\":\"CONTRIBUTION\",\"amount\":%s,\"periodYear\":%d,\"periodMonth\":%d}"
                .formatted(amount, month.getYear(), month.getMonthValue());
    }

    private static String loanAlloc(UUID loanId, String amount) {
        return "{\"type\":\"LOAN_REPAYMENT\",\"amount\":%s,\"loanId\":\"%s\"}".formatted(amount, loanId);
    }

    private static String credit(String amount, String purpose, UUID loanId) {
        return "{\"type\":\"MEMBER_CREDIT\",\"amount\":%s,\"creditPurpose\":\"%s\"%s}"
                .formatted(amount, purpose, loanId == null ? "" : ",\"loanId\":\"" + loanId + "\"");
    }

    private static String body(String amount, String... allocations) {
        return "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\",\"transactionReference\":\"%s\",\"allocations\":[%s]}"
                .formatted(amount, LocalDate.now(KIGALI), ref(), String.join(",", allocations));
    }

    private MvcResult submitRaw(Actor who, String json) throws Exception {
        return mockMvc.perform(post(base() + "/payments")
                        .header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private UUID verified(String amount, String... allocations) throws Exception {
        MvcResult result = submitRaw(payer, body(amount, allocations));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        UUID id = UUID.fromString(data(result).path("id").asText());
        assertThat(status(act(reviewer, id, "/start-review", null))).isEqualTo(200);
        MvcResult verify = act(verifier, id, "/verify", PROVIDER_EVIDENCE);
        assertThat(status(verify)).as(verify.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    private MvcResult act(Actor who, UUID id, String suffix, String json) throws Exception {
        var request = post(base() + "/payments/" + id + suffix).header("Authorization", bearer(who));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult getAs(Actor who, String path) throws Exception {
        return mockMvc.perform(get(base() + path).header("Authorization", bearer(who))).andReturn();
    }

    private String applyJson(UUID member, String purpose, UUID bucketLoan, String target, YearMonth period, Integer unused, UUID loanId, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberUserId", member);
        body.put("purpose", purpose);
        if (bucketLoan != null) {
            body.put("bucketLoanId", bucketLoan);
        }
        body.put("targetType", target);
        if (period != null) {
            body.put("periodYear", period.getYear());
            body.put("periodMonth", period.getMonthValue());
        }
        if (loanId != null) {
            body.put("loanId", loanId);
        }
        body.put("amount", new BigDecimal(amount));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult apply(Actor who, String key, String purpose, UUID bucketLoan, String target, YearMonth period, Integer unused,
            UUID loanId, String amount) throws Exception {
        return mockMvc.perform(post(base() + "/payments/credits/apply")
                        .header("Authorization", bearer(who))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(payer.id(), purpose, bucketLoan, target, period, unused, loanId, amount)))
                .andReturn();
    }

    private MvcResult applyWithoutKey(Actor who) throws Exception {
        return mockMvc.perform(post(base() + "/payments/credits/apply")
                        .header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(payer.id(), "UNAPPLIED_CREDIT", null, "CONTRIBUTION", NEXT, null, null, "100")))
                .andReturn();
    }

    private void row(YearMonth month) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("memberUserId", payer.id());
        line.put("paidAmount", BigDecimal.ZERO);
        assertThat(status(mockMvc.perform(put(base() + "/contributions/period?year=" + month.getYear() + "&month=" + month.getMonthValue())
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", List.of(line)))))
                .andReturn())).isEqualTo(200);
    }

    private void configure(YearMonth month, int rotating, int retained) throws Exception {
        assertThat(status(mockMvc.perform(post(base() + "/rotation/configurations")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveYear", month.getYear(), "effectiveMonth", month.getMonthValue(),
                                "rotatingBps", rotating, "retainedBps", retained))))
                .andReturn())).isEqualTo(200);
    }

    private void activateCycle(YearMonth start, List<UUID> recipients) throws Exception {
        MvcResult created = mockMvc.perform(post(base() + "/rotation/cycles")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startYear", start.getYear(), "startMonth", start.getMonthValue(), "recipientUserIds", recipients))))
                .andReturn();
        assertThat(status(created)).isEqualTo(200);
        String cycle = data(created).path("id").asText();
        assertThat(status(mockMvc.perform(post(base() + "/rotation/cycles/" + cycle + "/activate")
                .header("Authorization", "Bearer " + fixtures.superAdminToken())).andReturn())).isEqualTo(200);
    }

    private Loan loan(String principal, String interest) {
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(s.id())
                .memberUserId(payer.id())
                .requestedAmount(new BigDecimal(principal))
                .approvedAmount(new BigDecimal(principal))
                .principalAmount(new BigDecimal(principal))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(new BigDecimal(interest))
                .outstandingPrincipal(new BigDecimal(principal))
                .outstandingInterest(new BigDecimal(interest))
                .requestDate(today.minusMonths(6))
                .disbursementDate(today.minusMonths(6))
                .dueDate(today.plusMonths(5))
                .status(LoanStatus.ACTIVE)
                .build());
    }

    private BigDecimal paid(YearMonth month) {
        return jdbc.queryForObject("select paid_amount from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, s.id(), payer.id(), month.getYear(), month.getMonthValue());
    }

    private BigDecimal availableFunds() throws Exception {
        return data(getAs(poster, "/reports/model-summary")).path("availableFunds").decimalValue();
    }

    private BigDecimal journalBalance() {
        return jdbc.queryForObject("select coalesce(sum(case when entry_type = 'RECEIVED' then amount else -amount end), 0)"
                + " from member_credit_entries where cooperative_id = ?", BigDecimal.class, s.id());
    }

    /** Ledger side of the same figure: credits held less credits applied or reversed. */
    private BigDecimal ledgerCreditBalance() {
        return jdbc.queryForObject("select coalesce(sum(credit_amount), 0) - coalesce(sum(debit_amount), 0) from financial_ledger"
                + " where cooperative_id = ? and transaction_type in ('MEMBER_CREDIT_RECEIVED','MEMBER_CREDIT_APPLIED','MEMBER_CREDIT_REVERSED')",
                BigDecimal.class, s.id());
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String code(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private JsonNode error(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
