package rw.terimbere.csams.modules.rotation.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.NEXT;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * The rotating pool: a scheme of ten members paying 30,000 a month schedules 300,000 for each recipient. What was
 * collected (verified), what was set aside beforehand, what is left, and any shortfall, are exact and never counted twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RotationPoolAccountingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LedgerService ledgerService;

    /** Ten members of 30 shares (30,000 a month each); the first one is the recipient of the first period. */
    private PaymentScenario rotating() throws Exception {
        return rotating(true);
    }

    /**
     * @param withOtherCash true adds 100,000 of other income to the scheme (cash that belongs to nobody in particular), so a
     *     pool can be set aside from money that is not already owed to a recipient
     */
    private PaymentScenario rotating(boolean withOtherCash) throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ROTATING", 30);
        if (withOtherCash) {
            ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                    .cooperativeId(p.id())
                    .transactionType(LedgerTransactionType.OTHER_INCOME)
                    .debitAmount(BigDecimal.ZERO)
                    .creditAmount(new BigDecimal("100000"))
                    .currency("RWF")
                    .transactionDate(java.time.LocalDate.now())
                    .reference("SEED")
                    .sourceEntityType(LedgerService.SOURCE_INCOME_EXPENSE)
                    .sourceEntityId(UUID.randomUUID())
                    .description("Cash that is not owed to any recipient")
                    .recordedBy(p.scheme.president().id())
                    .approvedBy(p.scheme.president().id())
                    .idempotencyKey("SEED:" + UUID.randomUUID())
                    .build());
        }
        for (int i = 1; i < 10; i++) {
            p.addMember(30);
        }
        p.configureRotation(NEXT, 10000, 0);
        p.activateCycle(NEXT, p.allMembers());
        p.rows(NEXT, p.allMembers());
        p.rows(NEXT.plusMonths(1), p.allMembers());
        return p;
    }

    /** The first {@code count} members each pay their 30,000 for the month, verified and posted. */
    private void payMonth(PaymentScenario p, java.time.YearMonth month, int count) throws Exception {
        List<Actor> members = p.allMembers();
        for (int i = 0; i < count; i++) {
            p.postedFor(members.get(i), "30000", contribution(month, "30000"));
        }
    }

    // ============================================================ the figures

    @Test
    void tenMembersOf30000ScheduleOneRecipientForThreeHundredThousandAndNineCollectedLeaveAShortfallOf30000() throws Exception {
        PaymentScenario p = rotating();
        payMonth(p, NEXT, 9);

        JsonNode period = periodPool(p, NEXT);
        assertThat(dec(period, "scheduledPayout")).isEqualByComparingTo("300000");
        assertThat(dec(period, "periodCollected")).isEqualByComparingTo("270000");
        assertThat(dec(period, "collectedPool")).isEqualByComparingTo("270000");
        assertThat(dec(period, "openingPool")).isEqualByComparingTo("0");
        assertThat(dec(period, "availablePool")).isEqualByComparingTo("270000");
        assertThat(dec(period, "payoutShortfall")).isEqualByComparingTo("30000");
        assertThat(dec(period, "payableNow")).as("with no reserve only the collected money can be paid").isEqualByComparingTo("270000");

        // without a reserve the recipient cannot be paid more than was collected
        MvcResult tooMuch = p.requestPayout(p.reviewer, p.period(NEXT), "300000");
        assertThat(status(tooMuch)).isEqualTo(422);
        assertThat(p.code(tooMuch)).isEqualTo("PAYOUT_EXCEEDS_ENTITLEMENT");
    }

    @Test
    void anOpeningPoolOf30000CoversTheShortfallAndTheLateContributionReleasesItAgain() throws Exception {
        PaymentScenario p = rotating();
        payMonth(p, NEXT, 9);
        assertThat(status(poolEntry(p, p.scheme.president(), "OPENING", "30000", "Opening pool carried from the old books", null))).isEqualTo(200);

        JsonNode before = periodPool(p, NEXT);
        assertThat(dec(before, "openingPool")).isEqualByComparingTo("30000");
        assertThat(dec(before, "availablePool")).isEqualByComparingTo("300000");
        assertThat(dec(before, "payoutShortfall")).isEqualByComparingTo("0");
        assertThat(dec(before, "reserveCover")).isEqualByComparingTo("30000");
        assertThat(dec(before, "payableNow")).isEqualByComparingTo("300000");

        MvcResult requested = p.requestPayout(p.reviewer, p.period(NEXT), "300000");
        assertThat(status(requested)).as(requested.getResponse().getContentAsString()).isEqualTo(200);
        p.approveAndDisburse(UUID.fromString(p.data(requested).path("id").asText()), "BK-300000");

        // the whole 300,000 left the scheme once, 30,000 of it from the reserve
        JsonNode scheme = schemePool(p);
        assertThat(dec(scheme, "previousPayouts")).isEqualByComparingTo("300000");
        assertThat(dec(scheme, "availablePool")).isEqualByComparingTo("0");
        assertThat(dec(scheme, "reserveInUse")).isEqualByComparingTo("30000");
        assertThat(dec(scheme, "unusedReserve")).isEqualByComparingTo("0");
        assertThat(p.availableFunds()).as("cash after the payout: 100,000 other + 270,000 collected - 300,000").isEqualByComparingTo("70000");

        // the late tenth contribution arrives: the period is whole and the reserve is released, nothing is paid twice
        p.postedFor(p.allMembers().get(9), "30000", contribution(NEXT, "30000"));
        JsonNode after = schemePool(p);
        assertThat(dec(after, "collectedPool")).isEqualByComparingTo("300000");
        assertThat(dec(after, "reserveInUse")).isEqualByComparingTo("0");
        assertThat(dec(after, "unusedReserve")).isEqualByComparingTo("30000");
        JsonNode summary = p.data(p.getAs(p.reviewer, "/reports/model-summary"));
        assertThat(summary.path("recipientEntitlementsOwed").decimalValue()).as("nothing more is owed to the first recipient").isEqualByComparingTo("0");
        assertThat(status(p.requestPayout(p.reviewer, p.period(NEXT), null))).as("nothing left to pay for that period").isEqualTo(422);
    }

    @Test
    void aReserveFundedPayoutNeverReducesWhatAnotherRecipientIsStillOwed() throws Exception {
        PaymentScenario p = rotating();
        payMonth(p, NEXT, 9);
        payMonth(p, NEXT.plusMonths(1), 10);
        poolEntry(p, p.scheme.president(), "OPENING", "30000", "Opening pool carried from the old books", null);

        MvcResult first = p.requestPayout(p.reviewer, p.period(NEXT), "300000");
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(200);
        p.approveAndDisburse(UUID.fromString(p.data(first).path("id").asText()), "BK-P1");

        // the second period's recipient is owed all of their own 300,000, whatever the first one was paid from
        JsonNode second = periodPool(p, NEXT.plusMonths(1));
        assertThat(dec(second, "periodCollected")).isEqualByComparingTo("300000");
        assertThat(dec(second, "payableNow")).isEqualByComparingTo("300000");
        JsonNode summary = p.data(p.getAs(p.reviewer, "/reports/model-summary"));
        assertThat(summary.path("recipientEntitlementsOwed").decimalValue())
                .as("owed per period, not netted across periods").isEqualByComparingTo("300000");
        // the reserve is already used up by the first period: the second cannot borrow it
        assertThat(dec(schemePool(p), "unusedReserve")).isEqualByComparingTo("0");
        MvcResult tooMuch = p.requestPayout(p.reviewer, p.period(NEXT.plusMonths(1)), "300001");
        assertThat(p.code(tooMuch)).isEqualTo("PAYOUT_EXCEEDS_ENTITLEMENT");
    }

    // ============================================================ recording the pool

    @Test
    void thePoolIsAnEarmarkBackedByFreeCashAndOnlyOneOpeningPoolIsAllowed() throws Exception {
        // with no cash beyond what recipients are owed nothing can be set aside
        PaymentScenario tight = rotating(false);
        payMonth(tight, NEXT, 9);
        MvcResult none = poolEntry(tight, tight.scheme.president(), "OPENING", "1000", "Nothing is free to set aside", null);
        assertThat(status(none)).isEqualTo(422);
        assertThat(tight.code(none)).isEqualTo("POOL_EXCEEDS_AVAILABLE_FUNDS");

        PaymentScenario p = rotating();
        payMonth(p, NEXT, 9);
        long ledger = p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id());
        BigDecimal funds = p.availableFunds();

        MvcResult beyondCash = poolEntry(p, p.scheme.president(), "OPENING", "100001", "More than the free cash", null);
        assertThat(status(beyondCash)).isEqualTo(422);
        assertThat(p.code(beyondCash)).isEqualTo("POOL_EXCEEDS_AVAILABLE_FUNDS");

        assertThat(status(poolEntry(p, p.scheme.president(), "OPENING", "30000", "Opening pool from the old books", null))).isEqualTo(200);
        MvcResult second = poolEntry(p, p.scheme.president(), "OPENING", "10000", "A second opening pool", null);
        assertThat(status(second)).isEqualTo(409);
        assertThat(p.code(second)).isEqualTo("POOL_OPENING_EXISTS");

        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id()))
                .as("an earmark is not a cash movement").isEqualTo(ledger);
        assertThat(p.availableFunds()).as("it adds no money").isEqualByComparingTo(funds);
        assertThat(poolEntries(p)).hasSize(1);
    }

    @Test
    void anExpenseCanOnlyReleaseReserveThatIsNotAlreadyCoveringPayouts() throws Exception {
        PaymentScenario p = rotating();
        payMonth(p, NEXT, 9);
        poolEntry(p, p.scheme.president(), "OPENING", "30000", "Opening pool from the old books", null);

        assertThat(status(poolEntry(p, p.scheme.president(), "EXPENSE", "10000", "Transfer fees of the pool", null))).isEqualTo(200);
        assertThat(dec(schemePool(p), "unusedReserve")).isEqualByComparingTo("20000");
        MvcResult tooMuch = poolEntry(p, p.scheme.president(), "EXPENSE", "20001", "More than is left", null);
        assertThat(p.code(tooMuch)).isEqualTo("POOL_EXPENSE_EXCEEDS_RESERVE");

        // reserve of 20,000 now covers 20,000 of the 30,000 shortfall
        assertThat(dec(periodPool(p, NEXT), "reserveCover")).isEqualByComparingTo("20000");
        assertThat(dec(periodPool(p, NEXT), "payableNow")).isEqualByComparingTo("290000");
        MvcResult requested = p.requestPayout(p.reviewer, p.period(NEXT), "290000");
        assertThat(status(requested)).isEqualTo(200);
        p.approveAndDisburse(UUID.fromString(p.data(requested).path("id").asText()), "BK-290000");
        // the reserve in use cannot be released
        MvcResult inUse = poolEntry(p, p.scheme.president(), "EXPENSE", "1", "Nothing is free any more", null);
        assertThat(p.code(inUse)).isEqualTo("POOL_EXPENSE_EXCEEDS_RESERVE");
    }

    @Test
    void theOpeningPoolMustBeRecordedBeforeTheFirstPayoutAndEntriesAreIdempotent() throws Exception {
        PaymentScenario p = rotating();
        payMonth(p, NEXT, 9);
        MvcResult requested = p.requestPayout(p.reviewer, p.period(NEXT), "270000");
        assertThat(status(requested)).isEqualTo(200);
        MvcResult late = poolEntry(p, p.scheme.president(), "OPENING", "30000", "Recorded after the first payout", null);
        assertThat(status(late)).isEqualTo(422);
        assertThat(p.code(late)).isEqualTo("POOL_OPENING_TOO_LATE");

        // a top-up with the same key is recorded once
        MvcResult one = poolEntry(p, p.scheme.president(), "TOP_UP", "5000", "Member contribution to the pool", "key-1");
        MvcResult two = poolEntry(p, p.scheme.president(), "TOP_UP", "5000", "Member contribution to the pool", "key-1");
        assertThat(status(one)).isEqualTo(200);
        assertThat(status(two)).isEqualTo(200);
        assertThat(p.data(two).path("id").asText()).isEqualTo(p.data(one).path("id").asText());
        assertThat(poolEntries(p)).hasSize(1);
        MvcResult reused = poolEntry(p, p.scheme.president(), "TOP_UP", "6000", "A different amount", "key-1");
        assertThat(status(reused)).isEqualTo(409);
        assertThat(p.code(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aReasonIsRequiredAndOnlyTheFundAuthorityOfTheSchemeCanRecordOrReadThePool() throws Exception {
        PaymentScenario p = rotating();
        PaymentScenario other = rotating();
        assertThat(p.code(poolEntry(p, p.scheme.president(), "TOP_UP", "1000", "", null))).isEqualTo("POOL_REASON_REQUIRED");
        assertThat(status(poolEntry(p, p.reviewer, "TOP_UP", "1000", "An accountant cannot", null))).as("no fund authority").isEqualTo(403);
        assertThat(status(poolEntry(p, p.scheme.memberB(), "TOP_UP", "1000", "A member cannot", null))).isEqualTo(403);
        assertThat(status(poolEntry(p, other.scheme.president(), "TOP_UP", "1000", "Another scheme", null))).as("another scheme's president").isEqualTo(403);
        assertThat(status(p.getAs(p.scheme.memberB(), "/rotation/pool"))).isEqualTo(403);
        assertThat(status(p.getAs(other.scheme.president(), "/rotation/pool"))).isEqualTo(403);
        assertThat(status(p.getAs(p.reviewer, "/rotation/pool"))).isEqualTo(200);
    }

    @Test
    void theDatabaseAllowsOneOpeningPoolPerSchemeAndOnlyPositiveAmounts() throws Exception {
        PaymentScenario p = rotating();
        assertThat(status(poolEntry(p, p.scheme.president(), "OPENING", "1000", "Opening pool from the old books", null))).isEqualTo(200);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into rotation_pool_entries (id, cooperative_id, entry_type, amount, effective_date, reason, opening_key, recorded_by)"
                                + " values (?, ?, 'OPENING', 5, CURRENT_DATE, 'again', ?, ?)",
                        UUID.randomUUID(), p.id(), p.id(), p.scheme.president().id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into rotation_pool_entries (id, cooperative_id, entry_type, amount, effective_date, reason, recorded_by)"
                                + " values (?, ?, 'TOP_UP', -5, CURRENT_DATE, 'negative', ?)",
                        UUID.randomUUID(), p.id(), p.scheme.president().id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into rotation_pool_entries (id, cooperative_id, entry_type, amount, effective_date, reason, recorded_by)"
                                + " values (?, ?, 'OPENING', 5, CURRENT_DATE, 'no key', ?)",
                        UUID.randomUUID(), p.id(), p.scheme.president().id()))
                .as("an opening entry must carry the opening key")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theModelSummaryCarriesThePoolOfARotatingSchemeAndNoneForAnAccumulatingOne() throws Exception {
        PaymentScenario p = rotating();
        payMonth(p, NEXT, 9);
        poolEntry(p, p.scheme.president(), "OPENING", "30000", "Opening pool from the old books", null);
        JsonNode pool = p.data(p.getAs(p.reviewer, "/reports/model-summary")).path("rotationPool");
        assertThat(pool.path("openingPool").decimalValue()).isEqualByComparingTo("30000");
        assertThat(pool.path("collectedPool").decimalValue()).isEqualByComparingTo("270000");
        assertThat(pool.path("availablePool").decimalValue()).isEqualByComparingTo("300000");

        PaymentScenario acc = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        assertThat(acc.data(acc.getAs(acc.reviewer, "/reports/model-summary")).path("rotationPool").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult poolEntry(PaymentScenario p, Actor who, String type, String amount, String reason, String key) throws Exception {
        var request = post(p.base() + "/rotation/pool/entries")
                .header("Authorization", PaymentScenario.bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"amount\":" + amount + ",\"reason\":\"" + reason + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode periodPool(PaymentScenario p, java.time.YearMonth month) throws Exception {
        MvcResult result = p.getAs(p.reviewer, "/rotation/pool/periods/" + p.period(month));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return p.data(result);
    }

    private JsonNode schemePool(PaymentScenario p) throws Exception {
        MvcResult result = p.getAs(p.reviewer, "/rotation/pool");
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return p.data(result);
    }

    private JsonNode poolEntries(PaymentScenario p) throws Exception {
        return p.data(p.getAs(p.reviewer, "/rotation/pool/entries"));
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.path(field).decimalValue();
    }
}
