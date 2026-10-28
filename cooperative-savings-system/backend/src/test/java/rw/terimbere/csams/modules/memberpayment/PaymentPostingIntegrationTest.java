package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;

/**
 * Task 3B-3B: verified-only, atomic posting of combined member payments. HTTP-level, so authorization, separation of
 * duties, the row locks and the ledger are exercised together.
 *
 * <p>Runs on H2 in PostgreSQL mode. PostgreSQL row-lock behavior is NOT verified by these tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentPostingIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final YearMonth NOW = YearMonth.now(KIGALI);
    private static final YearMonth NEXT = NOW.plusMonths(1);
    private static final YearMonth OLD = NOW.minusMonths(2);

    private static final String PROVIDER_EVIDENCE =
            "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"Provider confirmation seen in the merchant portal\",\"reference\":\"PROV-1\"}";

    private record Actor(UUID id, String token) {}

    /** A scheme with a payer (2 shares, so expected 2000 a month) and three independent accountants. */
    private record Fx(UUID scheme, Actor payer, Actor verifier, Actor poster, Actor third) {}

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    private String superAdminToken;
    private UUID superAdminId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        superAdminId = jdbc.queryForObject("select id from users where username = 'superadmin'", UUID.class);
    }

    // ============================================================ verification

    @Test
    void aSubmittedOrRejectedPaymentCannotPost() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID submitted = submit(fx, "1000", contribution(NOW, "1000"));
        assertThat(error(postPayment(fx, fx.poster(), submitted)).path("code").asText()).isEqualTo("PAYMENT_NOT_VERIFIED");

        UUID rejected = submit(fx, "1000", contribution(NOW, "1000"));
        assertThat(status(act(fx.third(), fx, rejected, "/reject", "{\"reason\":\"Not found in the statement\"}"))).isEqualTo(200);
        assertThat(error(postPayment(fx, fx.poster(), rejected)).path("code").asText()).isEqualTo("PAYMENT_NOT_VERIFIED");
        assertThat(count("select count(*) from member_payment_postings where payment_id in (?, ?)", submitted, rejected)).isZero();
    }

    @Test
    void aVerifiedPaymentWithoutEvidenceIsHeldUntilItsEvidenceIsCompleted() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID id = submit(fx, "1000", contribution(NOW, "1000"));
        forceVerifiedWithoutEvidence(id, fx.verifier());

        MvcResult held = postPayment(fx, fx.poster(), id);
        assertThat(status(held)).isEqualTo(422);
        assertThat(error(held).path("code").asText()).isEqualTo("PAYMENT_EVIDENCE_MISSING");
        assertThat(paid(fx, NOW)).isEqualByComparingTo("0");

        // The payer cannot complete evidence, the submitter cannot either; an independent officer can, once, audited.
        assertThat(status(act(fx.payer(), fx, id, "/verification-evidence", PROVIDER_EVIDENCE))).isEqualTo(403);
        assertThat(status(act(fx.third(), fx, id, "/verification-evidence", PROVIDER_EVIDENCE))).isEqualTo(200);
        assertThat(status(act(fx.third(), fx, id, "/verification-evidence", PROVIDER_EVIDENCE))).as("repeat is a no-op").isEqualTo(200);
        MvcResult overwrite = act(fx.third(), fx, id, "/verification-evidence",
                PROVIDER_EVIDENCE.replace("PROV-1", "PROV-2"));
        assertThat(error(overwrite).path("code").asText()).isEqualTo("VERIFICATION_EVIDENCE_EXISTS");
        assertThat(count("select count(*) from audit_logs where action = 'PAYMENT_EVIDENCE_COMPLETE' and entity_id = ?", id)).isEqualTo(1);

        // The evidence recorder is not allowed to post, a different officer is.
        assertThat(status(postPayment(fx, fx.third(), id))).isEqualTo(403);
        assertThat(status(postPayment(fx, fx.poster(), id))).isEqualTo(200);
        assertThat(paid(fx, NOW)).isEqualByComparingTo("1000");
    }

    @Test
    void separationOfDutiesHoldsOnTheBackendEvenForSuperAdmin() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);

        // the verifier cannot post
        UUID verifiedByV = verified(fx, "1000", contribution(NOW, "1000"));
        MvcResult verifierPosts = postPayment(fx, fx.verifier(), verifiedByV);
        assertThat(status(verifierPosts)).isEqualTo(403);

        // the payer (no write authority) cannot post
        assertThat(status(postPayment(fx, fx.payer(), verifiedByV))).isEqualTo(403);

        // an officer who recorded the payment on the member's behalf (the submitter) can neither verify nor post it
        UUID bySubmitter = submitAsStaff(fx, fx.third(), "500", contribution(NOW, "500"));
        assertThat(status(act(fx.verifier(), fx, bySubmitter, "/start-review", null))).isEqualTo(200);
        assertThat(status(act(fx.third(), fx, bySubmitter, "/verify", PROVIDER_EVIDENCE))).isEqualTo(403);
        assertThat(status(act(fx.verifier(), fx, bySubmitter, "/verify", PROVIDER_EVIDENCE))).isEqualTo(200);
        assertThat(status(postPayment(fx, fx.third(), bySubmitter))).isEqualTo(403);

        // Super Admin verifies, then may not post what they verified
        UUID bySuper = submit(fx, "300", contribution(NOW, "300"));
        mockMvc.perform(post(path(fx, bySuper, "/start-review")).header("Authorization", bearer(superAdminToken))).andReturn();
        MvcResult superVerified = mockMvc.perform(post(path(fx, bySuper, "/verify"))
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROVIDER_EVIDENCE))
                .andReturn();
        assertThat(status(superVerified)).isEqualTo(200);
        MvcResult superPosts = mockMvc.perform(post(path(fx, bySuper, "/post")).header("Authorization", bearer(superAdminToken))).andReturn();
        assertThat(status(superPosts)).isEqualTo(403);
        assertThat(paid(fx, NOW)).as("nothing posted by any refused attempt").isEqualByComparingTo("0");
        assertThat(status(postPayment(fx, fx.poster(), bySuper))).isEqualTo(200);
    }

    @Test
    void repeatedVerificationIsIdempotentAndNeverOverwritesEvidence() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID id = submit(fx, "1000", contribution(NOW, "1000"));
        act(fx.third(), fx, id, "/start-review", null);
        assertThat(status(act(fx.verifier(), fx, id, "/verify", PROVIDER_EVIDENCE))).isEqualTo(200);
        assertThat(status(act(fx.verifier(), fx, id, "/verify", PROVIDER_EVIDENCE))).isEqualTo(200);
        MvcResult different = act(fx.verifier(), fx, id, "/verify", PROVIDER_EVIDENCE.replace("PROV-1", "PROV-9"));
        assertThat(status(different)).isEqualTo(409);
        assertThat(error(different).path("code").asText()).isEqualTo("VERIFICATION_EVIDENCE_EXISTS");
        assertThat(jdbc.queryForObject("select verification_reference from member_payments where id = ?", String.class, id))
                .isEqualTo("PROV-1");
        assertThat(count("select count(*) from member_payment_events where payment_id = ? and to_status = 'VERIFIED'", id)).isEqualTo(1);
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme())).isZero();
    }

    @Test
    void ocrOrReceiptDataAloneCanNeverVerifyAPayment() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID id = submit(fx, "1000", contribution(NOW, "1000"));
        jdbc.update("update member_payments set receipt_status = 'SUCCESS_INDICATED', receipt_confidence = 0.9900,"
                + " extracted_amount = 1000, extracted_reference = 'TX1' where id = ?", id);
        act(fx.third(), fx, id, "/start-review", null);

        assertThat(status(act(fx.verifier(), fx, id, "/verify", "{}"))).isEqualTo(400);
        assertThat(status(act(fx.verifier(), fx, id, "/verify",
                "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"x\",\"reference\":\"R\",\"extractedAmount\":1000}"))).isEqualTo(400);
        assertThat(status(act(fx.verifier(), fx, id, "/verify",
                "{\"method\":\"OCR_EXTRACTED\",\"basis\":\"receipt text\",\"reference\":\"R\"}"))).isEqualTo(400);
        assertThat(status(act(fx.verifier(), fx, id, "/verify", "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"  \",\"reference\":\"R\"}")))
                .isEqualTo(400);
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, id)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void theVerificationMethodMustFitTheWayThePaymentWasMade() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID id = submit(fx, "1000", contribution(NOW, "1000"));
        act(fx.third(), fx, id, "/start-review", null);
        MvcResult cash = act(fx.verifier(), fx, id, "/verify", "{\"method\":\"CASH_RECEIPT_CONFIRMED\",\"basis\":\"Counted\"}");
        assertThat(error(cash).path("code").asText()).isEqualTo("VERIFICATION_METHOD_MISMATCH");
        MvcResult noReference = act(fx.verifier(), fx, id, "/verify", "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"Seen\"}");
        assertThat(error(noReference).path("code").asText()).isEqualTo("VERIFICATION_REFERENCE_REQUIRED");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, id)).isEqualTo("UNDER_REVIEW");
    }

    // ============================================================ combined allocations

    @Test
    void aPaymentCoversSeveralContributionPeriodsAndLegacyRowsPostAsUnattributed() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, OLD); // a historical row without snapshot
        row(fx, NOW); // a snapshotted retained-only row
        UUID id = verified(fx, "3500", contribution(OLD, "2000"), contribution(NOW, "1500"));

        MvcResult posted = postPayment(fx, fx.poster(), id);
        assertThat(status(posted)).isEqualTo(200);
        assertThat(data(posted).path("status").asText()).isEqualTo("POSTED");
        assertThat(paid(fx, OLD)).isEqualByComparingTo("2000");
        assertThat(paid(fx, NOW)).isEqualByComparingTo("1500");
        assertThat(ledgerNet(fx)).isEqualByComparingTo("3500");

        JsonNode lines = data(posted).path("postings");
        assertThat(lines).hasSize(2);
        JsonNode legacy = lineFor(lines, OLD);
        assertThat(legacy.path("attribution").asText()).isEqualTo("UNATTRIBUTED");
        assertThat(legacy.path("rotatingAmount").isNull()).isTrue();
        assertThat(legacy.path("originalRecipientUserId").isNull()).isTrue();
        JsonNode accumulating = lineFor(lines, NOW);
        assertThat(accumulating.path("attribution").asText()).isEqualTo("ACCUMULATING");
        assertThat(accumulating.path("rotatingAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(accumulating.path("retainedAmount").decimalValue()).isEqualByComparingTo("1500");
        // the obligation snapshot and the expected amount are untouched
        assertThat(jdbc.queryForObject("select expected_amount from contributions where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, fx.payer().id(), NOW.getYear(), NOW.getMonthValue())).isEqualByComparingTo("2000");
    }

    @Test
    void aPaymentCoversSeveralLoansAndTheLoanEngineSplitsEachRepayment() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        Loan first = loan(fx, "100000", "5000");
        Loan second = loan(fx, "50000", "0");
        UUID id = verified(fx, "10000", loanAlloc(first.getId(), "8000"), loanAlloc(second.getId(), "2000"));

        MvcResult posted = postPayment(fx, fx.poster(), id);
        assertThat(status(posted)).isEqualTo(200);
        // interest first (the engine's default): 5000 interest + 3000 principal, then 2000 principal
        Loan afterFirst = loanRepository.findById(first.getId()).orElseThrow();
        assertThat(afterFirst.getOutstandingInterest()).isEqualByComparingTo("0");
        assertThat(afterFirst.getOutstandingPrincipal()).isEqualByComparingTo("97000");
        assertThat(loanRepository.findById(second.getId()).orElseThrow().getOutstandingPrincipal()).isEqualByComparingTo("48000");
        JsonNode firstLine = data(posted).path("postings").get(0).path("loanId").asText().equals(first.getId().toString())
                ? data(posted).path("postings").get(0)
                : data(posted).path("postings").get(1);
        assertThat(firstLine.path("principalAmount").decimalValue()).isEqualByComparingTo("3000");
        assertThat(firstLine.path("interestAmount").decimalValue()).isEqualByComparingTo("5000");
        assertThat(firstLine.path("attribution").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(ledgerNet(fx)).isEqualByComparingTo("10000");
        assertThat(count("select count(*) from loan_repayments where member_user_id = ?", fx.payer().id())).isEqualTo(2);
    }

    @Test
    void aMixedPaymentPostsContributionsAndLoansTogetherWithOneNetLedgerBalance() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        Loan loan = loan(fx, "100000", "0");
        UUID id = verified(fx, "6000", contribution(NOW, "2000"), loanAlloc(loan.getId(), "4000"));

        assertThat(status(postPayment(fx, fx.poster(), id))).isEqualTo(200);
        assertThat(paid(fx, NOW)).isEqualByComparingTo("2000");
        assertThat(loanRepository.findById(loan.getId()).orElseThrow().getOutstandingPrincipal()).isEqualByComparingTo("96000");
        assertThat(ledgerNet(fx)).isEqualByComparingTo("6000");

        JsonNode posting = data(getPayment(fx, fx.poster(), id, "/posting"));
        assertThat(posting.path("status").asText()).isEqualTo("POSTED");
        assertThat(posting.path("postings")).hasSize(2);
        assertThat(posting.path("postingAttempts").asInt()).isZero();
    }

    @Test
    void unsupportedExcessAndMemberCreditAreRejectedAtSubmission() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        Loan loan = loan(fx, "10000", "0");
        MvcResult tooMuchContribution = submitRaw(fx.payer(), fx, body("2500", "MTN_MOMO", ref(), contribution(NOW, "2500")));
        assertThat(error(tooMuchContribution).path("code").asText()).isEqualTo("ALLOCATION_EXCEEDS_OUTSTANDING");
        MvcResult tooMuchLoan = submitRaw(fx.payer(), fx, body("10001", "MTN_MOMO", ref(), loanAlloc(loan.getId(), "10001")));
        assertThat(error(tooMuchLoan).path("code").asText()).isEqualTo("ALLOCATION_EXCEEDS_OUTSTANDING");
        // excess money must be classified: a credit without a purpose is refused
        MvcResult credit = submitRaw(fx.payer(), fx, body("500", "MTN_MOMO", ref(), "{\"type\":\"MEMBER_CREDIT\",\"amount\":500}"));
        assertThat(error(credit).path("code").asText()).isEqualTo("INVALID_ALLOCATION");
    }

    @Test
    void aClosedLoanOrAWaivedContributionKeepsTheVerifiedPaymentUnresolved() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        Loan loan = loan(fx, "50000", "0");
        UUID closedLoan = verified(fx, "1000", loanAlloc(loan.getId(), "1000"));
        jdbc.update("update loans set status = 'CLOSED' where id = ?", loan.getId());
        MvcResult closed = postPayment(fx, fx.poster(), closedLoan);
        assertThat(status(closed)).isEqualTo(422);
        assertThat(state(closedLoan)).isEqualTo("VERIFIED");

        UUID waived = verified(fx, "1000", contribution(NOW, "1000"));
        jdbc.update("update contributions set status = 'WAIVED' where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                fx.payer().id(), NOW.getYear(), NOW.getMonthValue());
        MvcResult waivedResult = postPayment(fx, fx.poster(), waived);
        assertThat(error(waivedResult).path("code").asText()).isEqualTo("CONTRIBUTION_PERIOD_CLOSED");
        assertThat(state(waived)).isEqualTo("VERIFIED");
        assertThat(ledgerNet(fx)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select posting_attempts from member_payments where id = ?", Integer.class, waived)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_posting_failure_code from member_payments where id = ?", String.class, waived))
                .isEqualTo("CONTRIBUTION_PERIOD_CLOSED");
    }

    // ============================================================ financial integrity

    @Test
    void aFailingAllocationRollsBackEverythingAndARetrySucceedsExactlyOnce() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        Loan loan = loan(fx, "50000", "0");
        UUID id = verified(fx, "3000", contribution(NOW, "2000"), loanAlloc(loan.getId(), "1000"));
        jdbc.update("update loans set status = 'CLOSED' where id = ?", loan.getId());

        MvcResult failed = postPayment(fx, fx.poster(), id);
        assertThat(status(failed)).isEqualTo(422);
        assertThat(paid(fx, NOW)).as("contribution effect rolled back").isEqualByComparingTo("0");
        assertThat(ledgerNet(fx)).as("no partial ledger posting").isEqualByComparingTo("0");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme())).isZero();
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", id)).isZero();
        assertThat(count("select count(*) from loan_repayments where member_user_id = ?", fx.payer().id())).isZero();
        assertThat(state(id)).isEqualTo("VERIFIED");
        assertThat(count("select count(*) from member_payment_events where payment_id = ? and comment like 'POSTING_FAILED:%'", id)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'PAYMENT_POST_FAILED' and entity_id = ?", id)).isEqualTo(1);

        // retry only makes sense after a failure; it is refused for a payment that never failed
        UUID other = verified(fx, "500", contribution(NOW, "500"));
        assertThat(error(act(fx.poster(), fx, other, "/retry-posting", null)).path("code").asText()).isEqualTo("NOTHING_TO_RETRY");

        jdbc.update("update loans set status = 'ACTIVE' where id = ?", loan.getId());
        MvcResult retried = act(fx.poster(), fx, id, "/retry-posting", null);
        assertThat(status(retried)).isEqualTo(200);
        assertThat(data(retried).path("status").asText()).isEqualTo("POSTED");
        assertThat(data(retried).path("postingAttempts").asInt()).isEqualTo(1);
        assertThat(paid(fx, NOW)).isEqualByComparingTo("2000");
        assertThat(ledgerNet(fx)).isEqualByComparingTo("3000");
    }

    @Test
    void postingTwiceCreatesNoSecondCreditAndNoSecondRepayment() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        Loan loan = loan(fx, "50000", "0");
        UUID id = verified(fx, "3000", contribution(NOW, "2000"), loanAlloc(loan.getId(), "1000"));
        assertThat(status(postPayment(fx, fx.poster(), id))).isEqualTo(200);
        long ledger = count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme());
        for (int i = 0; i < 3; i++) {
            MvcResult again = postPayment(fx, fx.poster(), id);
            assertThat(status(again)).isEqualTo(200);
            assertThat(data(again).path("status").asText()).isEqualTo("POSTED");
        }
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme())).isEqualTo(ledger);
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", id)).isEqualTo(2);
        assertThat(count("select count(*) from loan_repayments where member_user_id = ?", fx.payer().id())).isEqualTo(1);
        assertThat(count("select count(*) from member_payment_events where payment_id = ? and to_status = 'POSTED'", id)).isEqualTo(1);
        assertThat(ledgerNet(fx)).isEqualByComparingTo("3000");
    }

    @Test
    void anOfficerCorrectionAfterPostingDoesNotDoubleCountThePayment() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID id = verified(fx, "2000", contribution(NOW, "2000"));
        assertThat(status(postPayment(fx, fx.poster(), id))).isEqualTo(200);
        assertThat(contributionLedgerNet(fx, NOW)).isEqualByComparingTo("2000");

        // an officer re-saves the grid with the same paid amount, then corrects it downwards and back up
        assertThat(status(saveLine(fx, NOW, "2000"))).isEqualTo(200);
        assertThat(contributionLedgerNet(fx, NOW)).isEqualByComparingTo("2000");
        assertThat(status(saveLine(fx, NOW, "1500"))).isEqualTo(200);
        assertThat(contributionLedgerNet(fx, NOW)).isEqualByComparingTo("1500");
        assertThat(status(saveLine(fx, NOW, "2000"))).isEqualTo(200);
        assertThat(contributionLedgerNet(fx, NOW)).isEqualByComparingTo("2000");
        assertThat(paid(fx, NOW)).isEqualByComparingTo("2000");
    }

    @Test
    void aMissingHistoricalObligationIsRejectedAndNeverCreatedFromTodaysSettings() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        YearMonth missing = NOW.minusMonths(5);
        UUID id = verified(fx, "1000", contribution(missing, "1000"));
        MvcResult result = postPayment(fx, fx.poster(), id);
        assertThat(error(result).path("code").asText()).isEqualTo("OBLIGATION_NOT_FOUND");
        assertThat(count("select count(*) from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                fx.scheme(), fx.payer().id(), missing.getYear(), missing.getMonthValue())).isZero();
        assertThat(state(id)).isEqualTo("VERIFIED");
    }

    // ============================================================ hybrid / rotating attribution

    @Test
    void hybridPostingsUseTheOriginalPeriodSnapshotCumulativeRoundingAndTheOriginalRecipient() throws Exception {
        Fx fx = fx("HYBRID", 1);
        UUID recipient1 = register(fx.scheme(), "MEMBER").id();
        UUID recipient2 = register(fx.scheme(), "MEMBER").id();
        configure(fx.scheme(), NEXT, 3333, 6667);
        activateCycle(fx.scheme(), NEXT, List.of(recipient1, recipient2));
        row(fx, NEXT);
        // the period's snapshot is taken now; a later configuration change must not touch it
        assertThat(jdbc.queryForObject("select rotating_bps_snapshot from contributions where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                Integer.class, fx.payer().id(), NEXT.getYear(), NEXT.getMonthValue())).isEqualTo(3333);

        BigDecimal rotating = BigDecimal.ZERO;
        BigDecimal retained = BigDecimal.ZERO;
        for (String part : List.of("333", "333", "334")) {
            UUID id = verified(fx, part, contribution(NEXT, part));
            MvcResult posted = postPayment(fx, fx.poster(), id);
            assertThat(status(posted)).isEqualTo(200);
            JsonNode line = data(posted).path("postings").get(0);
            assertThat(line.path("attribution").asText()).isEqualTo("ATTRIBUTED");
            assertThat(line.path("rotatingAmount").decimalValue().add(line.path("retainedAmount").decimalValue()))
                    .isEqualByComparingTo(part);
            assertThat(line.path("rotatingBps").asInt()).isEqualTo(3333);
            assertThat(line.path("originalRecipientUserId").asText()).isEqualTo(recipient1.toString());
            assertThat(line.path("originalRecipientUserId").asText()).isNotEqualTo(recipient2.toString());
            rotating = rotating.add(line.path("rotatingAmount").decimalValue());
            retained = retained.add(line.path("retainedAmount").decimalValue());
        }
        // cumulative targets: 1000 x 33.33% = 333 rotating in total, never drifting with the three partial payments
        assertThat(rotating).isEqualByComparingTo("333");
        assertThat(retained).isEqualByComparingTo("667");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1000");
    }

    @Test
    void aRotatingObligationWithoutARecordedRecipientCannotBePostedWithAGuessedOne() throws Exception {
        Fx fx = fx("HYBRID", 1);
        configure(fx.scheme(), NEXT, 5000, 5000); // a split exists, but no schedule covers the month
        row(fx, NEXT);
        UUID id = verified(fx, "500", contribution(NEXT, "500"));
        MvcResult result = postPayment(fx, fx.poster(), id);
        assertThat(error(result).path("code").asText()).isEqualTo("ROTATION_ATTRIBUTION_UNRESOLVED");
        assertThat(state(id)).isEqualTo("VERIFIED");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("0");
    }

    // ============================================================ concurrency

    @Test
    void concurrentPostingOfOnePaymentPostsExactlyOnce() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        Loan loan = loan(fx, "50000", "0");
        UUID id = verified(fx, "3000", contribution(NOW, "2000"), loanAlloc(loan.getId(), "1000"));

        List<Integer> statuses = concurrently(4, () -> status(postPayment(fx, fx.poster(), id)));
        assertThat(statuses).containsOnly(200);
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", id)).isEqualTo(2);
        assertThat(count("select count(*) from member_payment_events where payment_id = ? and to_status = 'POSTED'", id)).isEqualTo(1);
        assertThat(count("select count(*) from loan_repayments where member_user_id = ?", fx.payer().id())).isEqualTo(1);
        assertThat(ledgerNet(fx)).isEqualByComparingTo("3000");
        assertThat(paid(fx, NOW)).isEqualByComparingTo("2000");
    }

    @Test
    void aPostedPaymentAndADirectLoanRepaymentCannotOverpayTheSameLoan() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        Loan loan = loan(fx, "10000", "0");
        UUID id = verified(fx, "8000", loanAlloc(loan.getId(), "8000"));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> posting = pool.submit(() -> {
                go.await();
                return status(postPayment(fx, fx.poster(), id));
            });
            Future<Integer> direct = pool.submit(() -> {
                go.await();
                return status(mockMvc.perform(post("/api/v1/cooperatives/" + fx.scheme() + "/loans/" + loan.getId() + "/repayments")
                                .header("Authorization", bearer(fx.third().token()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":8000,\"paymentDate\":\"" + LocalDate.now(KIGALI) + "\"}"))
                        .andReturn());
            });
            go.countDown();
            int a = posting.get(60, TimeUnit.SECONDS);
            int b = direct.get(60, TimeUnit.SECONDS);
            assertThat(List.of(a, b).stream().filter(s -> s == 200).count()).as("exactly one repayment of 8000 succeeds").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        Loan after = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(after.getOutstandingPrincipal()).isEqualByComparingTo("2000");
        assertThat(count("select count(*) from loan_repayments where loan_id = ?", loan.getId())).isEqualTo(1);
        assertThat(ledgerNet(fx)).isEqualByComparingTo("8000");
    }

    @Test
    void aContributionCorrectionRacingAPostingNeverLeavesTheLedgerOutOfStepWithThePaidAmount() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID id = verified(fx, "1000", contribution(NOW, "1000"));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> posting = pool.submit(() -> {
                go.await();
                return status(postPayment(fx, fx.poster(), id));
            });
            Future<Integer> correction = pool.submit(() -> {
                go.await();
                return status(saveLine(fx, NOW, "500"));
            });
            go.countDown();
            posting.get(60, TimeUnit.SECONDS);
            correction.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // whichever order won, the books agree: ledger == paid amount, and paid is never negative or above expected
        BigDecimal paid = paid(fx, NOW);
        assertThat(contributionLedgerNet(fx, NOW)).isEqualByComparingTo(paid);
        assertThat(paid.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
        assertThat(paid.compareTo(new BigDecimal("2000"))).isLessThanOrEqualTo(0);
    }

    @Test
    void twoPaymentsCannotTogetherOverpayTheSameContribution() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID first = verified(fx, "1500", contribution(NOW, "1500"));
        UUID second = verified(fx, "1500", contribution(NOW, "1500"));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                go.await();
                return status(postPayment(fx, fx.poster(), first));
            });
            Future<Integer> b = pool.submit(() -> {
                go.await();
                return status(postPayment(fx, fx.third(), second));
            });
            go.countDown();
            List<Integer> statuses = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(paid(fx, NOW)).isEqualByComparingTo("1500");
        assertThat(contributionLedgerNet(fx, NOW)).isEqualByComparingTo("1500");
        assertThat(count("select count(*) from member_payments where cooperative_id = ? and status = 'POSTED'", fx.scheme())).isEqualTo(1);
        assertThat(count("select count(*) from member_payments where cooperative_id = ? and status = 'VERIFIED' and posting_attempts = 1",
                fx.scheme())).isEqualTo(1);
    }

    // ============================================================ security

    @Test
    void crossSchemeAndUnauthorizedActorsCannotVerifyOrPost() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        Fx other = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID id = verified(fx, "1000", contribution(NOW, "1000"));

        // an accountant of another scheme can neither see nor post this scheme's payment
        int foreign = status(mockMvc.perform(post(path(fx, id, "/post")).header("Authorization", bearer(other.poster().token()))).andReturn());
        assertThat(foreign).isIn(403, 404);
        // a member with no write authority cannot
        assertThat(status(postPayment(fx, fx.payer(), id))).isEqualTo(403);
        // a payment of one scheme addressed through another scheme's path is simply not found
        int wrongPath = status(mockMvc.perform(post("/api/v1/cooperatives/" + other.scheme() + "/payments/" + id + "/post")
                .header("Authorization", bearer(other.poster().token()))).andReturn());
        assertThat(wrongPath).isEqualTo(404);
        assertThat(state(id)).isEqualTo("VERIFIED");

        // an allocation pointing at another scheme's loan is refused at submission
        Loan foreignLoan = loan(other, "10000", "0");
        MvcResult cross = submitRaw(fx.payer(), fx, body("1000", "MTN_MOMO", ref(), loanAlloc(foreignLoan.getId(), "1000")));
        assertThat(status(cross)).isEqualTo(404);
    }

    @Test
    void postingRecordsAreScopedUniquePerAllocationAndAuditedWithEvents() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID id = verified(fx, "1000", contribution(NOW, "1000"));
        assertThat(status(postPayment(fx, fx.poster(), id))).isEqualTo(200);

        assertThat(jdbc.queryForList("select to_status from member_payment_events where payment_id = ? order by created_at, case to_status when 'SUBMITTED' then 1 when 'UNDER_REVIEW' then 2 when 'VERIFIED' then 3 else 4 end", String.class, id))
                .containsExactly("SUBMITTED", "UNDER_REVIEW", "VERIFIED", "POSTED");
        assertThat(count("select count(*) from audit_logs where action = 'PAYMENT_VERIFY' and entity_id = ?", id)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'PAYMENT_POST' and entity_id = ?", id)).isEqualTo(1);

        // the database refuses a second posting of the same allocation, and a posting scoped to another member
        Map<String, Object> posting = jdbc.queryForMap("select * from member_payment_postings where payment_id = ?", id);
        assertThatThrownBy(() -> insertPostingCopy(posting, UUID.randomUUID(), (UUID) posting.get("member_user_id")))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID otherMember = fx.verifier().id();
        assertThatThrownBy(() -> insertPostingCopy(posting, UUID.randomUUID(), otherMember))
                .isInstanceOf(DataIntegrityViolationException.class);
        // the poster must differ from the verifier at the database level too
        assertThatThrownBy(() -> jdbc.update("update member_payments set posted_by = verified_by where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================ reversal

    @Test
    void thereIsNoStatusOnlyReversalRoute() throws Exception {
        Fx fx = fx("ACCUMULATING", 2);
        row(fx, NOW);
        UUID contributionOnly = verified(fx, "500", contribution(NOW, "500"));
        assertThat(status(postPayment(fx, fx.poster(), contributionOnly))).isEqualTo(200);

        long ledger = count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme());
        long postings = count("select count(*) from member_payment_postings where cooperative_id = ?", fx.scheme());

        // the old status-only route is gone: a payment is reversed only through a requested and approved reversal
        assertThat(status(act(fx.third(), fx, contributionOnly, "/reverse", "{\"reason\":\"Posted in error\"}"))).isIn(404, 405);

        assertThat(state(contributionOnly)).isEqualTo("POSTED");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme())).isEqualTo(ledger);
        assertThat(count("select count(*) from member_payment_postings where cooperative_id = ?", fx.scheme())).isEqualTo(postings);
        assertThat(count("select count(*) from member_payments where cooperative_id = ? and status = 'REVERSED'", fx.scheme())).isZero();
        assertThat(count("select count(*) from member_payment_reversals where cooperative_id = ?", fx.scheme())).isZero();
    }

    // ============================================================ helpers

    private Fx fx(String model, int payerShares) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "PST " + UUID.randomUUID().toString().substring(0, 8), "1000.0000", 1, model)))
                .andReturn();
        assertThat(status(created)).isEqualTo(200);
        UUID scheme = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText());
        Actor payer = register(scheme, "MEMBER");
        jdbc.update("update cooperative_memberships set share_count = ? where cooperative_id = ? and user_id = ?", payerShares, scheme, payer.id());
        return new Fx(scheme, payer, register(scheme, "ACCOUNTANT"), register(scheme, "ACCOUNTANT"), register(scheme, "ACCOUNTANT"));
    }

    private Actor register(UUID scheme, String role) throws Exception {
        String username = "pst_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Post","lastName":"Test","username":"%s","email":"%s@test.local","roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andReturn();
        assertThat(status(result)).as("register " + role).isEqualTo(200);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Actor(UUID.fromString(data.path("userId").asText()), login(username, data.path("temporaryPassword").asText()));
    }

    /** Creates the obligation row of a month (paid 0) through the officer grid, like the real workflow does. */
    private void row(Fx fx, YearMonth month) throws Exception {
        assertThat(status(saveLine(fx, month, "0"))).isEqualTo(200);
    }

    private MvcResult saveLine(Fx fx, YearMonth month, String paid) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("memberUserId", fx.payer().id());
        line.put("paidAmount", new BigDecimal(paid));
        if (new BigDecimal(paid).signum() > 0) {
            line.put("paymentDate", LocalDate.now(KIGALI).toString());
        }
        return mockMvc.perform(put("/api/v1/cooperatives/" + fx.scheme() + "/contributions/period?year=" + month.getYear()
                                + "&month=" + month.getMonthValue())
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", List.of(line)))))
                .andReturn();
    }

    private void configure(UUID scheme, YearMonth month, int rotating, int retained) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/rotation/configurations")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveYear", month.getYear(),
                                "effectiveMonth", month.getMonthValue(),
                                "rotatingBps", rotating,
                                "retainedBps", retained))))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
    }

    private void activateCycle(UUID scheme, YearMonth start, List<UUID> recipients) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/rotation/cycles")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startYear", start.getYear(),
                                "startMonth", start.getMonthValue(),
                                "recipientUserIds", recipients))))
                .andReturn();
        assertThat(status(created)).isEqualTo(200);
        String cycleId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        MvcResult activated = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/rotation/cycles/" + cycleId + "/activate")
                        .header("Authorization", bearer(superAdminToken)))
                .andReturn();
        assertThat(status(activated)).isEqualTo(200);
    }

    private Loan loan(Fx fx, String principal, String interest) {
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(fx.scheme())
                .memberUserId(fx.payer().id())
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

    private static String body(String amount, String method, String reference, String... allocations) {
        return "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"%s\",\"transactionReference\":\"%s\",\"allocations\":[%s]}"
                .formatted(amount, LocalDate.now(KIGALI), method, reference, String.join(",", allocations));
    }

    private MvcResult submitRaw(Actor who, Fx fx, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/cooperatives/" + fx.scheme() + "/payments")
                        .header("Authorization", bearer(who.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private UUID submit(Fx fx, String amount, String... allocations) throws Exception {
        MvcResult result = submitRaw(fx.payer(), fx, body(amount, "MTN_MOMO", ref(), allocations));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(data(result).path("id").asText());
    }

    /**
     * A payment recorded on the member's behalf by an officer (the officer is the submitter). The HTTP API only lets
     * members submit for themselves, so the submitter is switched in the database to model the service-level path.
     */
    private UUID submitAsStaff(Fx fx, Actor officer, String amount, String... allocations) throws Exception {
        UUID id = submit(fx, amount, allocations);
        jdbc.update("update member_payments set submitted_by = ? where id = ?", officer.id(), id);
        return id;
    }

    /** Submitted by the payer, reviewed by the third officer, verified by the verifier with provider evidence. */
    private UUID verified(Fx fx, String amount, String... allocations) throws Exception {
        UUID id = submit(fx, amount, allocations);
        assertThat(status(act(fx.third(), fx, id, "/start-review", null))).isEqualTo(200);
        assertThat(status(act(fx.verifier(), fx, id, "/verify", PROVIDER_EVIDENCE))).isEqualTo(200);
        return id;
    }

    private void forceVerifiedWithoutEvidence(UUID id, Actor verifier) {
        jdbc.update("update member_payments set status = 'VERIFIED', review_started_by = ?, review_started_at = now(),"
                + " verified_by = ?, verified_at = now() where id = ?", verifier.id(), verifier.id(), id);
    }

    private void forceVerifiedWithEvidence(UUID id, Actor verifier) {
        forceVerifiedWithoutEvidence(id, verifier);
        jdbc.update("update member_payments set verification_method = 'PROVIDER_CONFIRMED', verification_basis = 'seen',"
                + " verification_reference = 'PROV-1' where id = ?", id);
    }

    private String path(Fx fx, UUID id, String suffix) {
        return "/api/v1/cooperatives/" + fx.scheme() + "/payments/" + id + suffix;
    }

    private MvcResult postPayment(Fx fx, Actor who, UUID id) throws Exception {
        return act(who, fx, id, "/post", null);
    }

    private MvcResult act(Actor who, Fx fx, UUID id, String suffix, String json) throws Exception {
        var request = post(path(fx, id, suffix)).header("Authorization", bearer(who.token()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult getPayment(Fx fx, Actor who, UUID id, String suffix) throws Exception {
        return mockMvc.perform(get(path(fx, id, suffix)).header("Authorization", bearer(who.token()))).andReturn();
    }

    private BigDecimal paid(Fx fx, YearMonth month) {
        return jdbc.queryForObject("select paid_amount from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, fx.scheme(), fx.payer().id(), month.getYear(), month.getMonthValue());
    }

    /** Net of everything the ledger holds for the member (contribution and loan credits minus reversals). */
    private BigDecimal ledgerNet(Fx fx) {
        return jdbc.queryForObject("select coalesce(sum(credit_amount), 0) - coalesce(sum(debit_amount), 0) from financial_ledger"
                + " where cooperative_id = ? and member_user_id = ?", BigDecimal.class, fx.scheme(), fx.payer().id());
    }

    private BigDecimal contributionLedgerNet(Fx fx, YearMonth month) {
        return jdbc.queryForObject("select coalesce(sum(l.credit_amount), 0) - coalesce(sum(l.debit_amount), 0) from financial_ledger l"
                + " join contributions c on c.id = l.source_entity_id where c.cooperative_id = ? and c.member_user_id = ?"
                + " and c.\"year\" = ? and c.\"month\" = ? and l.source_entity_type = 'CONTRIBUTION'",
                BigDecimal.class, fx.scheme(), fx.payer().id(), month.getYear(), month.getMonthValue());
    }

    private JsonNode lineFor(JsonNode lines, YearMonth month) {
        for (JsonNode line : lines) {
            if (line.path("periodYear").asInt() == month.getYear() && line.path("periodMonth").asInt() == month.getMonthValue()) {
                return line;
            }
        }
        throw new AssertionError("No posting line for " + month);
    }

    private String state(UUID paymentId) {
        return jdbc.queryForObject("select status from member_payments where id = ?", String.class, paymentId);
    }

    private void insertPostingCopy(Map<String, Object> source, UUID newId, UUID memberId) {
        jdbc.update("insert into member_payment_postings (id, payment_id, allocation_id, cooperative_id, member_user_id, allocation_type,"
                        + " amount, payment_date, posted_by, posted_at, contribution_id, period_year, period_month, paid_before, paid_after,"
                        + " attribution) values (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?, ?, ?)",
                newId, source.get("payment_id"), source.get("allocation_id"), source.get("cooperative_id"), memberId,
                source.get("allocation_type"), source.get("amount"), source.get("payment_date"), source.get("posted_by"),
                source.get("contribution_id"), source.get("period_year"), source.get("period_month"),
                source.get("paid_before"), source.get("paid_after"), source.get("attribution"));
    }

    private <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode error(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
}
