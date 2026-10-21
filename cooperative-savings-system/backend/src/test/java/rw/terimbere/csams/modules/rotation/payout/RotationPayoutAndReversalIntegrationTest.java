package rw.terimbere.csams.modules.rotation.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * Phase 2: contribution-only payment reversal, rotating entitlements, controlled rotation payouts, shortfalls and late
 * recoveries, the legacy-edit guard, model-aware valuation and the model summary. HTTP-level on H2 in PostgreSQL mode;
 * PostgreSQL row-lock behavior is NOT verified by these tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RotationPayoutAndReversalIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final YearMonth NOW = YearMonth.now(KIGALI);
    private static final YearMonth NEXT = NOW.plusMonths(1);
    private static final String PROVIDER_EVIDENCE =
            "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"Provider confirmation seen\",\"reference\":\"PROV-1\"}";

    private record Actor(UUID id, String token) {}

    /**
     * A scheme with a payer (2 shares: expected 2000 a month, split 50/50 in a hybrid scheme), two scheduled recipients,
     * a President (fund authority) and three accountants.
     */
    private record Fx(UUID scheme, Actor payer, Actor r1, Actor r2, Actor president, Actor accA, Actor accB, Actor accC) {}

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private ShareValuationService shareValuationService;

    private String superAdminToken;
    private UUID superAdminId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        superAdminId = jdbc.queryForObject("select id from users where username = 'superadmin'", UUID.class);
    }

    // ============================================================ entitlement: derived from collections only

    @Test
    void anEntitlementIsOnlyWhatWasActuallyCollectedAndExpectedContributionsAreNeverCash() throws Exception {
        Fx fx = hybrid();
        UUID period = periodOf(fx, NEXT);

        JsonNode before = entitlement(fx, period);
        assertThat(dec(before, "expectedRotating")).isEqualByComparingTo("1000");
        assertThat(dec(before, "collectedRotating")).isEqualByComparingTo("0");
        assertThat(dec(before, "outstandingEntitlement")).isEqualByComparingTo("0");
        assertThat(dec(before, "rotatingArrears")).isEqualByComparingTo("1000");
        MvcResult none = requestPayout(fx, fx.accA(), period, null);
        assertThat(status(none)).isEqualTo(422);
        assertThat(code(none)).isEqualTo("NO_ENTITLEMENT");

        payAndPost(fx, "1500"); // rotating 750, retained 750

        JsonNode after = entitlement(fx, period);
        assertThat(dec(after, "collectedRotating")).isEqualByComparingTo("750");
        assertThat(dec(after, "rotatingArrears")).isEqualByComparingTo("250");
        assertThat(dec(after, "outstandingEntitlement")).isEqualByComparingTo("750");
        assertThat(after.path("recipientUserId").asText()).isEqualTo(fx.r1().id().toString());
    }

    @Test
    void theRecipientSeesOnlyTheirOwnEntitlementAndOtherMembersSeeNone() throws Exception {
        Fx fx = hybrid();
        payAndPost(fx, "1000");
        UUID period = periodOf(fx, NEXT);

        JsonNode mine = data(getAs(fx.r1(), fx, "/rotation/entitlements/mine"));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("periodId").asText()).isEqualTo(period.toString());
        assertThat(data(getAs(fx.payer(), fx, "/rotation/entitlements/mine"))).isEmpty();
        assertThat(status(getAs(fx.payer(), fx, "/rotation/entitlements"))).isEqualTo(403);
        assertThat(status(getAs(fx.payer(), fx, "/rotation/entitlements/" + period + "/recoveries"))).isEqualTo(404);
        assertThat(status(getAs(fx.r1(), fx, "/rotation/entitlements/" + period + "/recoveries"))).isEqualTo(200);
    }

    // ============================================================ payouts

    @Test
    void aPayoutNeedsAnIndependentApprovalAndAnExplicitConfirmationOfTheActualDisbursement() throws Exception {
        Fx fx = hybrid();
        payAndPost(fx, "1500");
        UUID period = periodOf(fx, NEXT);
        BigDecimal availableBefore = availableFunds(fx);

        MvcResult requested = requestPayout(fx, fx.accA(), period, null);
        assertThat(status(requested)).isEqualTo(200);
        JsonNode payout = data(requested);
        UUID id = UUID.fromString(payout.path("id").asText());
        assertThat(payout.path("status").asText()).isEqualTo("REQUESTED");
        assertThat(dec(payout, "amount")).isEqualByComparingTo("750");
        assertThat(payout.path("partial").asBoolean()).isFalse();
        assertThat(payout.path("recipientUserId").asText()).isEqualTo(fx.r1().id().toString());
        // requesting moves no money
        assertThat(ledgerCount(fx, "ROTATION_PAYOUT")).isZero();

        // the requester cannot approve; the recipient cannot request, approve or confirm
        assertThat(status(payoutAction(fx.accA(), fx, id, "/approve", null))).isEqualTo(403);
        assertThat(status(requestPayout(fx, fx.r1(), period, null))).isEqualTo(403);
        assertThat(status(payoutAction(fx.accB(), fx, id, "/confirm-disbursement", bank("BK-1")))).isEqualTo(422);
        // a second officer cannot open a second payout for the same period
        MvcResult duplicate = requestPayout(fx, fx.accB(), period, null);
        assertThat(status(duplicate)).isEqualTo(409);
        assertThat(code(duplicate)).isEqualTo("PAYOUT_ALREADY_OPEN");

        assertThat(status(payoutAction(fx.president(), fx, id, "/approve", null))).isEqualTo(200);
        assertThat(ledgerCount(fx, "ROTATION_PAYOUT")).as("approval alone moves no money").isZero();

        // confirmation needs the actual disbursement facts
        assertThat(status(payoutAction(fx.accB(), fx, id, "/confirm-disbursement", "{\"method\":\"BANK_TRANSFER\"}"))).isEqualTo(400);
        assertThat(status(payoutAction(fx.accB(), fx, id, "/confirm-disbursement", "{\"method\":\"BANK_TRANSFER\",\"reference\":\"  \"}"))).isEqualTo(400);
        MvcResult confirmed = payoutAction(fx.accB(), fx, id, "/confirm-disbursement", bank("BK-1"));
        assertThat(status(confirmed)).isEqualTo(200);
        assertThat(data(confirmed).path("status").asText()).isEqualTo("DISBURSED");
        assertThat(data(confirmed).path("disbursementReference").asText()).isEqualTo("BK-1");

        assertThat(ledgerCount(fx, "ROTATION_PAYOUT")).isEqualTo(1);
        assertThat(ledgerDebits(fx, "ROTATION_PAYOUT")).isEqualByComparingTo("750");
        assertThat(availableFunds(fx)).isEqualByComparingTo(availableBefore.subtract(new BigDecimal("750")));
        JsonNode entitlement = entitlement(fx, period);
        assertThat(dec(entitlement, "distributed")).isEqualByComparingTo("750");
        assertThat(dec(entitlement, "outstandingEntitlement")).isEqualByComparingTo("0");

        // idempotent: the same confirmation changes nothing, a different reference is refused
        assertThat(status(payoutAction(fx.accC(), fx, id, "/confirm-disbursement", bank("BK-1")))).isEqualTo(200);
        assertThat(ledgerCount(fx, "ROTATION_PAYOUT")).isEqualTo(1);
        MvcResult different = payoutAction(fx.accC(), fx, id, "/confirm-disbursement", bank("BK-2"));
        assertThat(status(different)).isEqualTo(409);
        // and a fully paid period has nothing left to request
        assertThat(code(requestPayout(fx, fx.accA(), period, null))).isEqualTo("NO_ENTITLEMENT");

        List<String> history = jdbc.queryForList("select to_status from rotation_payout_events where payout_id = ? order by created_at, id", String.class, id);
        assertThat(history).containsExactly("REQUESTED", "APPROVED", "DISBURSED");
        assertThat(count("select count(*) from audit_logs where entity_id = ? and action in ('ROTATION_PAYOUT_REQUEST','ROTATION_PAYOUT_APPROVE','ROTATION_PAYOUT_DISBURSE')", id))
                .isEqualTo(3);
    }

    @Test
    void aPartialPayoutLeavesTheRestOwedAndALateRecoveryOnlyIncreasesTheOriginalRecipientsEntitlement() throws Exception {
        Fx fx = hybrid();
        payAndPost(fx, "1000"); // rotating 500
        UUID period = periodOf(fx, NEXT);

        // an officer pays out only part of the entitlement
        MvcResult partialRequest = requestPayout(fx, fx.accA(), period, "200");
        JsonNode partial = data(partialRequest);
        assertThat(partial.path("partial").asBoolean()).isTrue();
        assertThat(dec(partial, "remainingAfterPayout")).isEqualByComparingTo("300");
        UUID id = UUID.fromString(partial.path("id").asText());
        approveAndDisburse(fx, id, "BK-PART");
        assertThat(dec(entitlement(fx, period), "outstandingEntitlement")).isEqualByComparingTo("300");

        // a later, verified recovery on the SAME obligation raises only this period's (original) recipient's entitlement
        payAndPost(fx, "1000"); // paid 2000: rotating 1000, so +500
        JsonNode after = entitlement(fx, period);
        assertThat(dec(after, "collectedRotating")).isEqualByComparingTo("1000");
        assertThat(dec(after, "distributed")).isEqualByComparingTo("200");
        assertThat(dec(after, "outstandingEntitlement")).isEqualByComparingTo("800"); // 1000 - 200, never double counted
        assertThat(dec(after, "rotatingArrears")).isEqualByComparingTo("0");
        UUID period2 = periodOf(fx, NEXT.plusMonths(1));
        assertThat(dec(entitlement(fx, period2), "collectedRotating")).as("nothing moves to the next recipient").isEqualByComparingTo("0");

        // the recovery history names the postings; a payment dated after the period ended is flagged as late
        jdbc.update("update member_payment_postings set payment_date = ? where rotation_period_id = ? and paid_before > 0",
                NEXT.atEndOfMonth().plusDays(10), period);
        JsonNode history = data(getAs(fx.accA(), fx, "/rotation/entitlements/" + period + "/recoveries"));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("late").asBoolean()).isFalse();
        assertThat(history.get(1).path("late").asBoolean()).isTrue();

        // a payout can never exceed the entitlement
        MvcResult tooMuch = requestPayout(fx, fx.accA(), period, "800.01");
        assertThat(code(tooMuch)).isEqualTo("PAYOUT_EXCEEDS_ENTITLEMENT");
    }

    @Test
    void aPayoutNeverExceedsTheAvailableFundsAndIsCheckedAgainAtApproval() throws Exception {
        Fx fx = hybrid();
        payAndPost(fx, "1500"); // rotating 750
        UUID period = periodOf(fx, NEXT);
        BigDecimal available = availableFunds(fx); // 1500

        // spend most of the cash elsewhere so only 400 is available
        expense(fx, available.subtract(new BigDecimal("400")));
        assertThat(availableFunds(fx)).isEqualByComparingTo("400");

        MvcResult exceeds = requestPayout(fx, fx.accA(), period, "500");
        assertThat(code(exceeds)).isEqualTo("PAYOUT_EXCEEDS_AVAILABLE");
        MvcResult defaultsToPartial = requestPayout(fx, fx.accA(), period, null);
        JsonNode payout = data(defaultsToPartial);
        assertThat(dec(payout, "amount")).isEqualByComparingTo("400");
        assertThat(payout.path("partial").asBoolean()).isTrue();
        assertThat(dec(payout, "remainingAfterPayout")).isEqualByComparingTo("350");
        UUID id = UUID.fromString(payout.path("id").asText());

        // funds drop further before approval: the approval re-checks and refuses
        expense(fx, new BigDecimal("300"));
        MvcResult stale = payoutAction(fx.president(), fx, id, "/approve", null);
        assertThat(code(stale)).isEqualTo("PAYOUT_EXCEEDS_AVAILABLE");
        assertThat(jdbc.queryForObject("select status from rotation_payouts where id = ?", String.class, id)).isEqualTo("REQUESTED");

        // with no available funds at all there is nothing to request
        expense(fx, availableFunds(fx));
        assertThat(code(requestPayout(fx, fx.accB(), period, null))).as("one open payout already holds the period").isEqualTo("PAYOUT_ALREADY_OPEN");
        assertThat(status(payoutAction(fx.accA(), fx, id, "/cancel", "{\"reason\":\"Funds are not there\"}"))).isEqualTo(200);
        assertThat(code(requestPayout(fx, fx.accB(), period, null))).isEqualTo("INSUFFICIENT_AVAILABLE_FUNDS");
        assertThat(availableFunds(fx).signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void concurrentRequestsAndConfirmationsNeverDuplicateAPayout() throws Exception {
        Fx fx = hybrid();
        payAndPost(fx, "1500");
        UUID period = periodOf(fx, NEXT);

        List<Integer> requests = concurrently(
                List.of(fx.accA(), fx.accB(), fx.accC()), actor -> status(requestPayout(fx, actor, period, null)));
        assertThat(requests.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(count("select count(*) from rotation_payouts where rotation_period_id = ?", period)).isEqualTo(1);
        UUID id = jdbc.queryForObject("select id from rotation_payouts where rotation_period_id = ?", UUID.class, period);
        assertThat(status(payoutAction(fx.president(), fx, id, "/approve", null))).isEqualTo(200);

        List<Integer> confirms = concurrently(
                List.of(fx.accA(), fx.accB(), fx.accC(), fx.accB()), actor -> status(payoutAction(actor, fx, id, "/confirm-disbursement", bank("BK-RACE"))));
        assertThat(confirms).containsOnly(200);
        assertThat(ledgerCount(fx, "ROTATION_PAYOUT")).isEqualTo(1);
        assertThat(ledgerDebits(fx, "ROTATION_PAYOUT")).isEqualByComparingTo("750");
    }

    @Test
    void rotationPayoutsExistOnlyForRotatingAndHybridSchemes() throws Exception {
        Fx fx = fx("ACCUMULATING");
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + fx.scheme() + "/rotation/payouts")
                        .header("Authorization", bearer(fx.accA().token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rotationPeriodId\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn();
        assertThat(status(result)).isEqualTo(422);
        assertThat(code(result)).isEqualTo("ROTATION_PAYOUT_NOT_APPLICABLE");
    }

    @Test
    void anotherSchemesOfficersCannotSeeOrDecidePayoutsOrReversals() throws Exception {
        Fx fx = hybrid();
        Fx other = hybrid();
        payAndPost(fx, "1000");
        UUID period = periodOf(fx, NEXT);
        UUID id = UUID.fromString(data(requestPayout(fx, fx.accA(), period, null)).path("id").asText());

        int viewForeign = status(mockMvc.perform(get(base(fx) + "/rotation/payouts/" + id)
                .header("Authorization", bearer(other.accA().token()))).andReturn());
        assertThat(viewForeign).isIn(403, 404);
        int approveForeign = status(mockMvc.perform(post(base(fx) + "/rotation/payouts/" + id + "/approve")
                .header("Authorization", bearer(other.president().token()))).andReturn());
        assertThat(approveForeign).isIn(403, 404);
        // through its own scheme's path the payout does not exist
        int wrongScheme = status(mockMvc.perform(post(base(other) + "/rotation/payouts/" + id + "/approve")
                .header("Authorization", bearer(other.president().token()))).andReturn());
        assertThat(wrongScheme).isEqualTo(404);
        assertThat(jdbc.queryForObject("select status from rotation_payouts where id = ?", String.class, id)).isEqualTo("REQUESTED");
    }

    // ============================================================ reversal

    @Test
    void aContributionOnlyReversalNeedsAnIndependentApprovalAndCompensatesTheBooks() throws Exception {
        Fx fx = hybrid();
        UUID payment = payAndPost(fx, "1500");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1500");
        assertThat(contributionLedgerNet(fx, NEXT)).isEqualByComparingTo("1500");
        long postingRows = count("select count(*) from member_payment_postings where payment_id = ?", payment);

        MvcResult noReason = payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"  \"}");
        assertThat(status(noReason)).isEqualTo(400);
        MvcResult requested = payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"Paid to the wrong member\"}");
        assertThat(status(requested)).isEqualTo(200);
        UUID reversal = UUID.fromString(data(requested).path("id").asText());
        assertThat(data(requested).path("status").asText()).isEqualTo("REQUESTED");
        assertThat(paid(fx, NEXT)).as("a request moves no money").isEqualByComparingTo("1500");
        assertThat(status(payments(fx.accC(), fx, payment, "/reversals", "{\"reason\":\"again\"}"))).isEqualTo(409);

        // independence: an accountant (no fund authority) cannot approve, the requester cannot, never the payer
        assertThat(status(reversalAction(fx.accB(), fx, reversal, "/approve", null))).isEqualTo(403);
        assertThat(status(reversalAction(fx.accA(), fx, reversal, "/approve", null))).isEqualTo(403);
        assertThat(status(reversalAction(fx.payer(), fx, reversal, "/approve", null))).isEqualTo(403);
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1500");

        MvcResult approved = reversalAction(fx.president(), fx, reversal, "/approve", null);
        assertThat(status(approved)).isEqualTo(200);
        JsonNode result = data(approved);
        assertThat(result.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(result.path("takenBack")).hasSize(1);
        assertThat(dec(result.path("takenBack").get(0), "rotatingAmount")).isEqualByComparingTo("750");
        assertThat(dec(result.path("takenBack").get(0), "retainedAmount")).isEqualByComparingTo("750");

        // the compensating accounting
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("0");
        assertThat(contributionLedgerNet(fx, NEXT)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("REVERSED");
        assertThat(dec(entitlement(fx, periodOf(fx, NEXT)), "collectedRotating")).as("rotating attribution is taken back").isEqualByComparingTo("0");
        // nothing original was deleted or changed
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isEqualTo(postingRows);
        assertThat(count("select count(*) from member_payment_posting_reversals where payment_id = ?", payment)).isEqualTo(1);
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ? and status = 'REVERSED'", fx.scheme())).isGreaterThan(0);
        assertThat(jdbc.queryForList("select to_status from member_payment_events where payment_id = ? order by created_at, id", String.class, payment))
                .contains("POSTED", "REVERSED");
        assertThat(count("select count(*) from audit_logs where entity_id = ? and action in ('PAYMENT_REVERSAL_REQUEST','PAYMENT_REVERSAL_EXECUTE')", reversal)).isEqualTo(2);

        // idempotent: approving again takes nothing back a second time
        assertThat(status(reversalAction(fx.president(), fx, reversal, "/approve", null))).isEqualTo(200);
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("0");
        assertThat(count("select count(*) from member_payment_posting_reversals where payment_id = ?", payment)).isEqualTo(1);
        // and a reversed payment cannot be reversed again
        assertThat(code(payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"again\"}"))).isEqualTo("PAYMENT_NOT_POSTED");
    }

    @Test
    void aReversalIsBlockedWhenALaterChangeOrADistributionWouldMakeItInconsistent() throws Exception {
        Fx fx = hybrid();
        UUID first = payAndPost(fx, "1000");
        UUID second = payAndPost(fx, "500");
        UUID reversalOfFirst = UUID.fromString(data(payments(fx.accA(), fx, first, "/reversals", "{\"reason\":\"Wrong period\"}")).path("id").asText());
        // the second payment changed the same period after the first one: the first cannot be reversed on its own
        MvcResult laterChange = reversalAction(fx.president(), fx, reversalOfFirst, "/approve", null);
        assertThat(status(laterChange)).isEqualTo(422);
        assertThat(code(laterChange)).isEqualTo("REVERSAL_BLOCKED_LATER_CHANGES");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1500");

        // the latest payment can be reversed, unless a payout already consumed the rotating funds it brought
        UUID period = periodOf(fx, NEXT);
        UUID payout = UUID.fromString(data(requestPayout(fx, fx.accA(), period, "750")).path("id").asText());
        approveAndDisburse(fx, payout, "BK-CONSUMED"); // rotating collected 750, all paid out
        UUID reversalOfSecond = UUID.fromString(data(payments(fx.accA(), fx, second, "/reversals", "{\"reason\":\"Duplicate\"}")).path("id").asText());
        MvcResult consumed = reversalAction(fx.president(), fx, reversalOfSecond, "/approve", null);
        assertThat(status(consumed)).isEqualTo(422);
        assertThat(code(consumed)).isEqualTo("REVERSAL_BLOCKED_DISTRIBUTED");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1500");
        assertThat(jdbc.queryForObject("select status from member_payment_reversals where id = ?", String.class, reversalOfSecond)).isEqualTo("REQUESTED");

        // a legacy officer edit that would also undercut the distribution is refused too, never forced
        MvcResult lowered = saveLine(fx, NEXT, "500");
        assertThat(status(lowered)).isEqualTo(422);
        assertThat(code(lowered)).isEqualTo("ROTATION_DISTRIBUTION_CONSISTENCY");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1500");
        // raising it is fine
        assertThat(status(saveLine(fx, NEXT, "1800"))).isEqualTo(200);
    }

    @Test
    void concurrentApprovalsOfOneReversalTakeTheAmountBackExactlyOnce() throws Exception {
        Fx fx = hybrid();
        UUID payment = payAndPost(fx, "1500");
        UUID reversal = UUID.fromString(data(payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"Wrong member\"}")).path("id").asText());

        List<Integer> statuses = concurrently(
                List.of(fx.president(), fx.president(), fx.president(), fx.president()), actor -> status(reversalAction(actor, fx, reversal, "/approve", null)));
        assertThat(statuses).containsOnly(200);
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("0");
        assertThat(contributionLedgerNet(fx, NEXT)).isEqualByComparingTo("0");
        assertThat(count("select count(*) from member_payment_posting_reversals where payment_id = ?", payment)).isEqualTo(1);
    }

    @Test
    void aDeclinedReversalChangesNothingAndMayBeRequestedAgain() throws Exception {
        Fx fx = hybrid();
        UUID payment = payAndPost(fx, "1000");
        UUID reversal = UUID.fromString(data(payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"Maybe wrong\"}")).path("id").asText());
        assertThat(status(reversalAction(fx.accA(), fx, reversal, "/decline", "{\"reason\":\"withdrawn\"}"))).isEqualTo(403);
        assertThat(status(reversalAction(fx.president(), fx, reversal, "/decline", "{\"reason\":\"The payment is correct\"}"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("POSTED");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1000");
        assertThat(status(payments(fx.accC(), fx, payment, "/reversals", "{\"reason\":\"Checked again\"}"))).isEqualTo(200);
    }

    // ============================================================ valuation and summary

    @Test
    void undistributedRotatingFundsAreALiabilityInTheShareValuationAndAnAccumulatingSchemeIsUnchanged() throws Exception {
        Fx fx = hybrid();
        payAndPost(fx, "1500"); // collected 1500, of which 750 rotating
        UUID period = periodOf(fx, NEXT);
        var valuation = shareValuationService.calculate(fx.scheme());
        assertThat(valuation.getAvailableFunds()).isEqualByComparingTo("1500");
        assertThat(valuation.getLiabilities()).isEqualByComparingTo("750");
        assertThat(valuation.getTotalIkiminaValue()).as("members' value excludes the recipients' money").isEqualByComparingTo("750");

        approveAndDisburse(fx, UUID.fromString(data(requestPayout(fx, fx.accA(), period, null)).path("id").asText()), "BK-NAV");
        var afterPayout = shareValuationService.calculate(fx.scheme());
        assertThat(afterPayout.getAvailableFunds()).isEqualByComparingTo("750");
        assertThat(afterPayout.getLiabilities()).isEqualByComparingTo("0");
        assertThat(afterPayout.getTotalIkiminaValue()).as("paying the recipient does not change the members' value").isEqualByComparingTo("750");

        Fx accumulating = fx("ACCUMULATING");
        row(accumulating, NOW);
        payAndPostAt(accumulating, NOW, "1500");
        var plain = shareValuationService.calculate(accumulating.scheme());
        assertThat(plain.getLiabilities()).isEqualByComparingTo("0");
        assertThat(plain.getTotalIkiminaValue()).isEqualByComparingTo(plain.getAvailableFunds());
    }

    @Test
    void theModelSummarySeparatesExpectedCollectedRetainedRotatingOwedAndPaidOut() throws Exception {
        Fx fx = hybrid();
        payAndPost(fx, "1500");
        UUID period = periodOf(fx, NEXT);
        approveAndDisburse(fx, UUID.fromString(data(requestPayout(fx, fx.accA(), period, "300")).path("id").asText()), "BK-SUM");

        JsonNode summary = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(summary.path("operatingModel").asText()).isEqualTo("HYBRID");
        assertThat(dec(summary, "expectedContributions")).isEqualByComparingTo("2000");
        assertThat(dec(summary, "postedCollections")).isEqualByComparingTo("1500");
        assertThat(dec(summary, "retainedCollections")).isEqualByComparingTo("750");
        assertThat(dec(summary, "rotatingCollected")).isEqualByComparingTo("750");
        assertThat(dec(summary, "rotatingExpected")).isEqualByComparingTo("1000");
        assertThat(dec(summary, "rotatingArrears")).isEqualByComparingTo("250");
        assertThat(dec(summary, "recipientEntitlementsOwed")).isEqualByComparingTo("450");
        assertThat(dec(summary, "payoutsDisbursed")).isEqualByComparingTo("300");
        assertThat(dec(summary, "availableFunds")).isEqualByComparingTo("1200");
        assertThat(dec(summary, "availableAfterEntitlements")).isEqualByComparingTo("750");
        assertThat(status(getAs(fx.payer(), fx, "/reports/model-summary"))).isEqualTo(403);
    }


    // ============================================================ finalization: valuation reconciles exactly once

    @Test
    void theValuationDeductsUndistributedRotatingMoneyExactlyOnceForEveryOperatingModel() throws Exception {
        // ACCUMULATING: nothing is rotating, nothing is deducted
        Fx accumulating = fx("ACCUMULATING");
        row(accumulating, NOW);
        payAndPostAt(accumulating, NOW, "1500");
        assertNav(accumulating, "1500", "0", "1500");

        // ROTATING (100% rotating): the members' value stays zero through collection, partial payout, late recovery and reversal
        Fx rotating = rotationFx("ROTATING", 10000, 0, "MEMBER", "MEMBER");
        UUID period = periodOf(rotating, NEXT);
        payAndPost(rotating, "1500");
        assertNav(rotating, "1500", "1500", "0");
        approveAndDisburse(rotating, UUID.fromString(data(requestPayout(rotating, rotating.accA(), period, "600")).path("id").asText()), "BK-R1");
        assertNav(rotating, "900", "900", "0");
        UUID late = payAndPost(rotating, "500"); // a late recovery on the same obligation
        assertNav(rotating, "1400", "1400", "0");
        reverse(rotating, late);
        assertNav(rotating, "900", "900", "0");

        // HYBRID (50/50): only the retained half is the members' value
        Fx hybrid = hybrid();
        UUID hybridPeriod = periodOf(hybrid, NEXT);
        payAndPost(hybrid, "1500"); // rotating 750, retained 750
        assertNav(hybrid, "1500", "750", "750");
        approveAndDisburse(hybrid, UUID.fromString(data(requestPayout(hybrid, hybrid.accA(), hybridPeriod, "300")).path("id").asText()), "BK-H1");
        assertNav(hybrid, "1200", "450", "750");
        UUID recovery = payAndPost(hybrid, "500"); // paid 2000: rotating 1000 in total, so +250
        assertNav(hybrid, "1700", "700", "1000");
        reverse(hybrid, recovery);
        assertNav(hybrid, "1200", "450", "750");
    }

    // ============================================================ finalization: legacy contributions are not eligible

    @Test
    void unverifiedLegacyContributionsAreNeverEligibleRotatingCashAndAreReportedForReconciliation() throws Exception {
        Fx fx = hybrid();
        UUID period = periodOf(fx, NEXT);
        // an officer records 1000 through the older direct path: ledger credit, but no verification evidence
        assertThat(status(saveLine(fx, NEXT, "1000"))).isEqualTo(200);
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1000");
        long ledgerRows = count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme());
        assertThat(ledgerRows).as("the historical record and balance are kept").isEqualTo(1);

        JsonNode legacy = entitlement(fx, period);
        assertThat(dec(legacy, "collectedRotating")).as("nothing verified").isEqualByComparingTo("0");
        assertThat(dec(legacy, "unreconciledRotating")).isEqualByComparingTo("500");
        assertThat(dec(legacy, "outstandingEntitlement")).isEqualByComparingTo("0");
        // arrears stay a contribution shortfall: expected 1000 rotating, 500 paid in any way
        assertThat(dec(legacy, "rotatingArrears")).isEqualByComparingTo("500");
        MvcResult none = requestPayout(fx, fx.accA(), period, null);
        assertThat(code(none)).isEqualTo("NO_ENTITLEMENT");
        // identifiable rotating money is held for its recipient while unreconciled: a liability, not retained capital,
        // and not payable (nothing is verified owed)
        var legacyValuation = shareValuationService.calculate(fx.scheme());
        assertThat(legacyValuation.getVerifiedRotatingLiabilities()).isEqualByComparingTo("0");
        assertThat(legacyValuation.getUnreconciledRotatingFunds()).isEqualByComparingTo("500");
        assertThat(legacyValuation.getLiabilities()).isEqualByComparingTo("500");

        JsonNode summary = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(summary, "rotatingRequiringReconciliation")).isEqualByComparingTo("500");
        assertThat(dec(summary, "rotatingCollected")).isEqualByComparingTo("0");
        assertThat(dec(summary, "recipientEntitlementsOwed")).isEqualByComparingTo("0");
        assertThat(dec(summary, "retainedCollections")).as("neither retained nor owed until reconciled").isEqualByComparingTo("500");

        // a verified payment on the same obligation makes only ITS part eligible
        payAndPost(fx, "1000"); // paid 2000: rotating 1000 in total, the payment's part is 500
        JsonNode mixed = entitlement(fx, period);
        assertThat(dec(mixed, "collectedRotating")).isEqualByComparingTo("500");
        assertThat(dec(mixed, "unreconciledRotating")).isEqualByComparingTo("500");
        assertThat(dec(mixed, "outstandingEntitlement")).isEqualByComparingTo("500");
        assertThat(dec(mixed, "rotatingArrears")).isEqualByComparingTo("0");
        JsonNode payout = data(requestPayout(fx, fx.accA(), period, null));
        assertThat(dec(payout, "amount")).as("only the verified part can be paid out").isEqualByComparingTo("500");
        approveAndDisburse(fx, UUID.fromString(payout.path("id").asText()), "BK-VERIFIED");

        // the legacy single-edit route cannot be used to undercut what was paid out (it used to bypass the guard)
        MvcResult lower = patchPaid(fx, NEXT, "500");
        assertThat(status(lower)).isEqualTo(422);
        assertThat(code(lower)).isEqualTo("ROTATION_DISTRIBUTION_CONSISTENCY");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("2000");
        // a correction that keeps the distribution covered, and a raise, are fine
        assertThat(status(patchPaid(fx, NEXT, "1500"))).isEqualTo(200);
    }

    // ============================================================ finalization: reversal authority and withdrawal

    @Test
    void onlyAnIndependentFundAuthorityApprovesOrDeclinesAndTenantsAreIsolated() throws Exception {
        Fx fx = hybrid();
        Fx other = hybrid();
        UUID payment = payAndPost(fx, "1000");
        UUID reversal = UUID.fromString(data(payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"Wrong period\"}")).path("id").asText());

        // contribution-write officers cannot decide; neither can the other scheme's fund authority
        assertThat(status(reversalAction(fx.accB(), fx, reversal, "/approve", null))).isEqualTo(403);
        assertThat(status(reversalAction(fx.accB(), fx, reversal, "/decline", "{\"reason\":\"No\"}"))).isEqualTo(403);
        int foreign = status(mockMvc.perform(post(base(fx) + "/payments/reversals/" + reversal + "/approve")
                .header("Authorization", bearer(other.president().token()))).andReturn());
        assertThat(foreign).isIn(403, 404);
        assertThat(status(mockMvc.perform(post(base(other) + "/payments/reversals/" + reversal + "/approve")
                .header("Authorization", bearer(other.president().token()))).andReturn())).isEqualTo(404);
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1000");
        // the authorized approver can read the queue and approve
        assertThat(data(getAs(fx.president(), fx, "/payments/reversals"))).hasSize(1);
        assertThat(status(reversalAction(fx.president(), fx, reversal, "/approve", null))).isEqualTo(200);
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("0");
    }

    @Test
    void theRequesterMayWithdrawAPendingReversalWithoutAnyFinancialMovement() throws Exception {
        Fx fx = hybrid();
        UUID payment = payAndPost(fx, "1500");
        UUID reversal = UUID.fromString(data(payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"Maybe wrong\"}")).path("id").asText());
        long ledger = count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme());

        // only the requester can withdraw
        assertThat(status(reversalAction(fx.accB(), fx, reversal, "/withdraw", null))).isEqualTo(403);
        assertThat(status(reversalAction(fx.payer(), fx, reversal, "/withdraw", null))).isEqualTo(403);
        MvcResult withdrawn = reversalAction(fx.accA(), fx, reversal, "/withdraw", "{\"note\":\"Checked, payment is right\"}");
        assertThat(status(withdrawn)).isEqualTo(200);
        assertThat(data(withdrawn).path("status").asText()).isEqualTo("WITHDRAWN");

        // no financial movement; the payment, postings and ledger are exactly as they were
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1500");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("POSTED");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme())).isEqualTo(ledger);
        assertThat(count("select count(*) from member_payment_posting_reversals where payment_id = ?", payment)).isZero();
        // immutable history: a payment event and an audit entry
        assertThat(count("select count(*) from member_payment_events where payment_id = ? and comment = 'REVERSAL_WITHDRAWN'", payment)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where entity_id = ? and action = 'PAYMENT_REVERSAL_WITHDRAW'", reversal)).isEqualTo(1);

        // idempotent; a withdrawn request can no longer be approved, and a new request is allowed
        assertThat(status(reversalAction(fx.accA(), fx, reversal, "/withdraw", null))).isEqualTo(200);
        assertThat(count("select count(*) from member_payment_events where payment_id = ? and comment = 'REVERSAL_WITHDRAWN'", payment)).isEqualTo(1);
        MvcResult approveWithdrawn = reversalAction(fx.president(), fx, reversal, "/approve", null);
        assertThat(code(approveWithdrawn)).isEqualTo("REVERSAL_NOT_OPEN");
        assertThat(status(payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"Now it is confirmed wrong\"}"))).isEqualTo(200);
    }

    @Test
    void anExecutedReversalCanNoLongerBeWithdrawn() throws Exception {
        Fx fx = hybrid();
        UUID payment = payAndPost(fx, "1000");
        UUID reversal = UUID.fromString(data(payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"Wrong member\"}")).path("id").asText());
        assertThat(status(reversalAction(fx.president(), fx, reversal, "/approve", null))).isEqualTo(200);
        MvcResult late = reversalAction(fx.accA(), fx, reversal, "/withdraw", null);
        assertThat(status(late)).isEqualTo(422);
        assertThat(code(late)).isEqualTo("REVERSAL_NOT_OPEN");
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("0");
    }

    // ============================================================ finalization: periods and recipients

    @Test
    void aCancelledRotationPeriodNeverReceivesAPayoutButItsCollectionsStayOwed() throws Exception {
        Fx fx = hybrid();
        payAndPost(fx, "1000"); // rotating 500
        UUID period = periodOf(fx, NEXT);
        jdbc.update("update rotation_periods set status = 'CANCELLED', slot_key = null where id = ?", period);

        MvcResult result = requestPayout(fx, fx.accA(), period, null);
        assertThat(status(result)).isEqualTo(422);
        assertThat(code(result)).isEqualTo("ROTATION_PERIOD_NOT_PAYABLE");
        assertThat(entitlement(fx, period).path("periodStatus").asText()).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from rotation_payouts where rotation_period_id = ?", period)).isZero();
        // the money stays a liability until a separately approved resolution exists
        assertThat(shareValuationService.calculate(fx.scheme()).getLiabilities()).isEqualByComparingTo("500");
    }

    @Test
    void aRecipientCanNeverRequestApproveOrConfirmTheirOwnPayout() throws Exception {
        // recipient 1 holds payout-write authority, recipient 2 is a President (fund authority)
        Fx fx = rotationFx("HYBRID", 5000, 5000, "ACCOUNTANT", "PRESIDENT");
        row(fx, NEXT.plusMonths(1));
        payAndPost(fx, "1000");
        payAndPostAt(fx, NEXT.plusMonths(1), "1000");
        UUID period1 = periodOf(fx, NEXT);
        UUID period2 = periodOf(fx, NEXT.plusMonths(1));

        // recipient 1 (accountant) cannot request their own payout, nor confirm it
        assertThat(status(requestPayout(fx, fx.r1(), period1, null))).isEqualTo(403);
        UUID first = UUID.fromString(data(requestPayout(fx, fx.accA(), period1, null)).path("id").asText());
        assertThat(status(payoutAction(fx.president(), fx, first, "/approve", null))).isEqualTo(200);
        assertThat(status(payoutAction(fx.r1(), fx, first, "/confirm-disbursement", bank("BK-OWN")))).isEqualTo(403);
        assertThat(jdbc.queryForObject("select status from rotation_payouts where id = ?", String.class, first)).isEqualTo("APPROVED");

        // recipient 2 (president) cannot approve their own payout, even with the fund authority
        UUID second = UUID.fromString(data(requestPayout(fx, fx.accA(), period2, null)).path("id").asText());
        assertThat(status(payoutAction(fx.r2(), fx, second, "/approve", null))).isEqualTo(403);
        assertThat(status(payoutAction(fx.r2(), fx, second, "/reject", "{\"reason\":\"mine\"}"))).isEqualTo(403);
        assertThat(jdbc.queryForObject("select status from rotation_payouts where id = ?", String.class, second)).isEqualTo("REQUESTED");
        // an independent officer can complete the first payout
        assertThat(status(payoutAction(fx.accB(), fx, first, "/confirm-disbursement", bank("BK-OK")))).isEqualTo(200);
    }

    // ============================================================ finalization: arrears are not entitlements

    @Test
    void contributionArrearsAndUnpaidRecipientEntitlementsAreComputedAndReportedSeparately() throws Exception {
        Fx fx = hybrid();
        UUID period = periodOf(fx, NEXT);
        payAndPost(fx, "1000"); // expected rotating 1000, verified 500
        approveAndDisburse(fx, UUID.fromString(data(requestPayout(fx, fx.accA(), period, "200")).path("id").asText()), "BK-SEP");

        JsonNode e = entitlement(fx, period);
        assertThat(dec(e, "rotatingArrears")).as("expected - paid: nobody is owed what was never collected").isEqualByComparingTo("500");
        assertThat(dec(e, "outstandingEntitlement")).as("verified - paid out").isEqualByComparingTo("300");
        JsonNode summary = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(summary, "rotatingArrears")).isEqualByComparingTo("500");
        assertThat(dec(summary, "recipientEntitlementsOwed")).isEqualByComparingTo("300");
        // paying the arrears raises the entitlement of the SAME period only, by the verified part
        payAndPost(fx, "1000");
        JsonNode after = entitlement(fx, period);
        assertThat(dec(after, "rotatingArrears")).isEqualByComparingTo("0");
        assertThat(dec(after, "outstandingEntitlement")).isEqualByComparingTo("800");
        assertThat(dec(entitlement(fx, periodOf(fx, NEXT.plusMonths(1))), "outstandingEntitlement")).isEqualByComparingTo("0");
    }

    // ============================================================ helpers

    private Fx hybrid() throws Exception {
        return rotationFx("HYBRID", 5000, 5000, "MEMBER", "MEMBER");
    }

    private Fx rotationFx(String model, int rotating, int retained, String r1Role, String r2Role) throws Exception {
        Fx fx = fx(model, r1Role, r2Role);
        configure(fx.scheme(), NEXT, rotating, retained);
        activateCycle(fx.scheme(), NEXT, List.of(fx.r1().id(), fx.r2().id()));
        row(fx, NEXT);
        return fx;
    }

    private Fx fx(String model) throws Exception {
        return fx(model, "MEMBER", "MEMBER");
    }

    private Fx fx(String model, String r1Role, String r2Role) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "P2 " + UUID.randomUUID().toString().substring(0, 8), "1000.0000", 1, model)))
                .andReturn();
        assertThat(status(created)).isEqualTo(200);
        UUID scheme = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText());
        Actor payer = register(scheme, "MEMBER");
        jdbc.update("update cooperative_memberships set share_count = 2 where cooperative_id = ? and user_id = ?", scheme, payer.id());
        return new Fx(
                scheme,
                payer,
                register(scheme, r1Role),
                register(scheme, r2Role),
                register(scheme, "PRESIDENT"),
                register(scheme, "ACCOUNTANT"),
                register(scheme, "ACCOUNTANT"),
                register(scheme, "ACCOUNTANT"));
    }

    private Actor register(UUID scheme, String role) throws Exception {
        String username = "p2_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Phase","lastName":"Two","username":"%s","email":"%s@test.local","roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andReturn();
        assertThat(status(result)).as("register " + role).isEqualTo(200);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Actor(UUID.fromString(data.path("userId").asText()), login(username, data.path("temporaryPassword").asText()));
    }

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
        return mockMvc.perform(put("/api/v1/cooperatives/" + fx.scheme() + "/contributions/period?year=" + month.getYear() + "&month=" + month.getMonthValue())
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
                                "effectiveYear", month.getYear(), "effectiveMonth", month.getMonthValue(),
                                "rotatingBps", rotating, "retainedBps", retained))))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
    }

    private void activateCycle(UUID scheme, YearMonth start, List<UUID> recipients) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/rotation/cycles")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startYear", start.getYear(), "startMonth", start.getMonthValue(), "recipientUserIds", recipients))))
                .andReturn();
        assertThat(status(created)).isEqualTo(200);
        String cycleId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        MvcResult activated = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/rotation/cycles/" + cycleId + "/activate")
                        .header("Authorization", bearer(superAdminToken)))
                .andReturn();
        assertThat(status(activated)).isEqualTo(200);
    }

    private UUID periodOf(Fx fx, YearMonth month) {
        return jdbc.queryForObject(
                "select id from rotation_periods where cooperative_id = ? and period_year = ? and period_month = ?",
                UUID.class, fx.scheme(), month.getYear(), month.getMonthValue());
    }

    /** Submit as the payer, review (C), verify (B), post (a different officer than B and C: A). Returns the payment id. */
    private UUID payAndPost(Fx fx, String amount) throws Exception {
        return payAndPostAt(fx, NEXT, amount);
    }

    private UUID payAndPostAt(Fx fx, YearMonth month, String amount) throws Exception {
        String body = "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\",\"transactionReference\":\"TX%s\",\"allocations\":[%s]}"
                .formatted(amount, LocalDate.now(KIGALI), UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                        "{\"type\":\"CONTRIBUTION\",\"amount\":%s,\"periodYear\":%d,\"periodMonth\":%d}".formatted(amount, month.getYear(), month.getMonthValue()));
        MvcResult submitted = mockMvc.perform(post(base(fx) + "/payments")
                        .header("Authorization", bearer(fx.payer().token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(status(submitted)).as(submitted.getResponse().getContentAsString()).isEqualTo(200);
        UUID id = UUID.fromString(data(submitted).path("id").asText());
        assertThat(status(payments(fx.accC(), fx, id, "/start-review", null))).isEqualTo(200);
        assertThat(status(payments(fx.accB(), fx, id, "/verify", PROVIDER_EVIDENCE))).isEqualTo(200);
        MvcResult posted = payments(fx.accA(), fx, id, "/post", null);
        assertThat(status(posted)).as(posted.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    private MvcResult requestPayout(Fx fx, Actor who, UUID period, String amount) throws Exception {
        String body = amount == null
                ? "{\"rotationPeriodId\":\"" + period + "\"}"
                : "{\"rotationPeriodId\":\"" + period + "\",\"amount\":" + amount + "}";
        return mockMvc.perform(post(base(fx) + "/rotation/payouts")
                        .header("Authorization", bearer(who.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult payoutAction(Actor who, Fx fx, UUID id, String suffix, String json) throws Exception {
        var request = post(base(fx) + "/rotation/payouts/" + id + suffix).header("Authorization", bearer(who.token()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private void approveAndDisburse(Fx fx, UUID id, String reference) throws Exception {
        assertThat(status(payoutAction(fx.president(), fx, id, "/approve", null))).isEqualTo(200);
        MvcResult confirmed = payoutAction(fx.accB(), fx, id, "/confirm-disbursement", bank(reference));
        assertThat(status(confirmed)).as(confirmed.getResponse().getContentAsString()).isEqualTo(200);
    }

    private static String bank(String reference) {
        return "{\"method\":\"BANK_TRANSFER\",\"reference\":\"" + reference + "\",\"note\":\"Transferred\"}";
    }

    private MvcResult payments(Actor who, Fx fx, UUID id, String suffix, String json) throws Exception {
        var request = post(base(fx) + "/payments/" + id + suffix).header("Authorization", bearer(who.token()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult reversalAction(Actor who, Fx fx, UUID reversalId, String suffix, String json) throws Exception {
        var request = post(base(fx) + "/payments/reversals/" + reversalId + suffix).header("Authorization", bearer(who.token()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult patchPaid(Fx fx, YearMonth month, String paid) throws Exception {
        UUID id = jdbc.queryForObject(
                "select id from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                UUID.class, fx.scheme(), fx.payer().id(), month.getYear(), month.getMonthValue());
        return mockMvc.perform(patch(base(fx) + "/contributions/" + id)
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidAmount\":" + paid + "}"))
                .andReturn();
    }

    /** Requests and approves the reversal of a posted payment through the two independent officers. */
    private void reverse(Fx fx, UUID payment) throws Exception {
        MvcResult requested = payments(fx.accA(), fx, payment, "/reversals", "{\"reason\":\"Reconciliation test\"}");
        assertThat(status(requested)).as(requested.getResponse().getContentAsString()).isEqualTo(200);
        UUID reversal = UUID.fromString(data(requested).path("id").asText());
        MvcResult approved = reversalAction(fx.president(), fx, reversal, "/approve", null);
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
    }

    /**
     * The valuation after a step. Cash and liabilities are the expected amounts, the members' value is exactly cash minus
     * liabilities (deducted once), and the liability equals what the summary says recipients are still owed.
     */
    private void assertNav(Fx fx, String cash, String liabilities, String membersValue) throws Exception {
        var v = shareValuationService.calculate(fx.scheme());
        assertThat(v.getAvailableFunds()).as("cash").isEqualByComparingTo(cash);
        assertThat(v.getLiabilities()).as("liabilities").isEqualByComparingTo(liabilities);
        assertThat(v.getTotalIkiminaValue()).as("members' value").isEqualByComparingTo(membersValue);
        assertThat(v.getTotalIkiminaValue())
                .as("value = cash + assets - liabilities, once")
                .isEqualByComparingTo(v.getAvailableFunds()
                        .add(v.getOutstandingLoans())
                        .add(v.getUnpaidInterest())
                        .add(v.getUnpaidPenalties())
                        .add(v.getOtherAssets())
                        .subtract(v.getLiabilities()));
        JsonNode summary = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(summary, "recipientEntitlementsOwed")).isEqualByComparingTo(liabilities);
        assertThat(dec(summary, "availableFunds")).isEqualByComparingTo(cash);
    }

    private MvcResult getAs(Actor who, Fx fx, String suffix) throws Exception {
        return mockMvc.perform(get(base(fx) + suffix).header("Authorization", bearer(who.token()))).andReturn();
    }

    private JsonNode entitlement(Fx fx, UUID period) throws Exception {
        for (JsonNode node : data(getAs(fx.accA(), fx, "/rotation/entitlements"))) {
            if (node.path("periodId").asText().equals(period.toString())) {
                return node;
            }
        }
        throw new AssertionError("No entitlement row for " + period);
    }

    private void expense(Fx fx, BigDecimal amount) {
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(fx.scheme())
                .memberUserId(fx.payer().id())
                .transactionType(LedgerTransactionType.GENERAL_EXPENSE)
                .debitAmount(amount)
                .creditAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now(KIGALI))
                .reference("EXP-" + UUID.randomUUID())
                .sourceEntityType(LedgerService.SOURCE_INCOME_EXPENSE)
                .sourceEntityId(UUID.randomUUID())
                .description("Test expense")
                .recordedBy(superAdminId)
                .approvedBy(superAdminId)
                .idempotencyKey("TEST-EXP-" + UUID.randomUUID())
                .build());
    }

    private BigDecimal availableFunds(Fx fx) {
        return jdbc.queryForObject("select coalesce(sum(credit_amount), 0) - coalesce(sum(debit_amount), 0) from financial_ledger"
                + " where cooperative_id = ? and status in ('APPROVED')", BigDecimal.class, fx.scheme());
    }

    private BigDecimal paid(Fx fx, YearMonth month) {
        return jdbc.queryForObject("select paid_amount from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, fx.scheme(), fx.payer().id(), month.getYear(), month.getMonthValue());
    }

    private BigDecimal contributionLedgerNet(Fx fx, YearMonth month) {
        return jdbc.queryForObject("select coalesce(sum(l.credit_amount), 0) - coalesce(sum(l.debit_amount), 0) from financial_ledger l"
                        + " join contributions c on c.id = l.source_entity_id where c.cooperative_id = ? and c.member_user_id = ?"
                        + " and c.\"year\" = ? and c.\"month\" = ? and l.source_entity_type = 'CONTRIBUTION'",
                BigDecimal.class, fx.scheme(), fx.payer().id(), month.getYear(), month.getMonthValue());
    }

    private long ledgerCount(Fx fx, String sourceType) {
        return count("select count(*) from financial_ledger where cooperative_id = ? and source_entity_type = ?", fx.scheme(), sourceType);
    }

    private BigDecimal ledgerDebits(Fx fx, String sourceType) {
        return jdbc.queryForObject("select coalesce(sum(debit_amount), 0) from financial_ledger where cooperative_id = ? and source_entity_type = ?",
                BigDecimal.class, fx.scheme(), sourceType);
    }

    @FunctionalInterface
    private interface Task<T> {
        int run(T input) throws Exception;
    }

    private <T> List<Integer> concurrently(List<T> inputs, Task<T> task) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(inputs.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (T input : inputs) {
                Callable<Integer> call = () -> {
                    go.await();
                    return task.run(input);
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String base(Fx fx) {
        return "/api/v1/cooperatives/" + fx.scheme();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.path(field).decimalValue();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
}
