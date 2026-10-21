package rw.terimbere.csams.fundlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.investment.entity.Investment;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanSettingsService;
import rw.terimbere.csams.modules.payout.entity.PayoutLine;
import rw.terimbere.csams.modules.payout.entity.PayoutLineStatus;
import rw.terimbere.csams.modules.payout.entity.PayoutRun;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;
import rw.terimbere.csams.modules.payout.repository.PayoutLineRepository;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * Fixtures, operations and observations shared by the scheme-fund-lock concurrency scenarios. It is database
 * agnostic: {@code SchemeFundLockH2Test} runs the scenarios on H2 in the normal suite, and
 * {@code SchemeFundLockPostgresIT} runs the same scenarios on a Testcontainers PostgreSQL (opt-in, needs Docker).
 *
 * <p>Test methods are deliberately not {@code @Transactional}: worker threads must see committed fixtures, and each
 * operation under test runs in its own real transaction through the real endpoint and the real service.
 *
 * <p>Synchronization is built from latches, never from "sleep and hope". A {@link Hold} (a Mockito spy on
 * {@code LedgerService.appendApproved}, no production hook) parks the first protected operation after its funds check
 * and before it commits, so it provably still holds the scheme fund lock. The second operation is then started and
 * observed: it must not reach its own funds read (spy on {@code calculateAvailableGroupFund}) while the first is
 * parked, and on PostgreSQL it must be visible as a lock waiter. Only then is the first released. A timeout is never
 * treated as proof of anything: every conclusion is asserted from observed state and the final committed records.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class FundConcurrencySupport {

    protected static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    /** The development super admin seeded by the existing test profile; the same value every existing test uses. */
    protected static final String SUPER_ADMIN_USERNAME = "superadmin";

    protected static final String SUPER_ADMIN_PASSWORD = "ChangeMe@123!";
    protected static final long HOLD_TIMEOUT_SECONDS = 60;
    protected static final long RESULT_TIMEOUT_SECONDS = 90;

    protected static final Set<LedgerTransactionType> LOAN_POSTING = Set.of(LedgerTransactionType.LOAN_DISBURSEMENT);
    protected static final Set<LedgerTransactionType> PAYOUT_POSTING = Set.of(LedgerTransactionType.MEMBER_PAYOUT);
    protected static final Set<LedgerTransactionType> INVESTMENT_POSTING =
            Set.of(LedgerTransactionType.INVESTMENT_OUTFLOW);

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected LoanRepository loanRepository;
    @Autowired protected LoanSettingsService loanSettingsService;
    @Autowired protected PayoutRunRepository payoutRunRepository;
    @Autowired protected PayoutLineRepository payoutLineRepository;
    @Autowired protected InvestmentRepository investmentRepository;
    @Autowired protected LedgerFinancialCalculationService fundService;

    /** Spies stay real unless a test stubs them; they are reset after every test. */
    @SpyBean protected LedgerService ledgerService;

    @SpyBean protected AuditService auditService;
    @SpyBean protected LedgerFinancialCalculationService fundCalculationSpy;

    @PersistenceContext protected EntityManager entityManager;

    private String superAdminToken;

    // ------------------------------------------------------------------ fixtures

    protected String superAdminToken() throws Exception {
        if (superAdminToken == null) {
            superAdminToken = login(SUPER_ADMIN_USERNAME, SUPER_ADMIN_PASSWORD);
        }
        return superAdminToken;
    }

    protected UUID createScheme(String prefix) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                prefix + " " + UUID.randomUUID().toString().substring(0, 8), "10000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    protected UUID registerMember(UUID scheme) throws Exception {
        String username = "fl_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", "Bearer " + superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Fl","lastName":"Member","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"MEMBER"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return UUID.fromString(data.path("userId").asText());
    }

    /** Cash received into the scheme through the real ledger service (a credit the funds formula counts). */
    protected void seedAvailableFunds(UUID scheme, String amount) {
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(scheme)
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .creditAmount(new BigDecimal(amount))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now(KIGALI).minusMonths(1))
                .sourceEntityType("FUND_LOCK_TEST_SEED")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("fl-seed-" + UUID.randomUUID())
                .description("seed")
                .build());
    }

    /**
     * An APPROVED loan ready for disbursement, committed by the repository (its own transaction). A FLAT loan with a
     * fixed interest amount and no schedule fields makes {@code LoanService.disburse} skip schedule generation, so the
     * logic exercised is the lock, the funds check, the status change and the ledger posting.
     */
    protected UUID approvedLoan(UUID scheme, UUID borrower, String amount) {
        // A scheme's loan settings are created lazily on first use (get-or-create), and disbursement reaches that
        // code. Two first-ever concurrent calls would race on the unique loan_settings row for a reason unrelated to
        // the funds lock (seen in the Task 2J-A dry run), so the settings are created here, before any concurrency.
        loanSettingsService.requireSettings(scheme);
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository
                .saveAndFlush(Loan.builder()
                        .cooperativeId(scheme)
                        .memberUserId(borrower)
                        .requestedAmount(new BigDecimal(amount))
                        .approvedAmount(new BigDecimal(amount))
                        .interestRatePercent(new BigDecimal("2"))
                        .interestType(InterestType.FLAT)
                        .termMonths(6)
                        .interestAmount(BigDecimal.ZERO)
                        .outstandingPrincipal(BigDecimal.ZERO)
                        .outstandingInterest(BigDecimal.ZERO)
                        .requestDate(today.minusDays(5))
                        .approvalDate(today.minusDays(1))
                        .status(LoanStatus.APPROVED)
                        .build())
                .getId();
    }

    /** A PREVIEWED payout run with one frozen line for the member, as a real preview would leave it. */
    protected UUID previewedPayoutRun(UUID scheme, UUID member, String amount) {
        LocalDate today = LocalDate.now(KIGALI);
        BigDecimal pool = new BigDecimal(amount);
        PayoutRun run = payoutRunRepository.saveAndFlush(PayoutRun.builder()
                .cooperativeId(scheme)
                .name("fund-lock test run")
                .periodFrom(today.minusMonths(2))
                .periodTo(today.minusMonths(1))
                .includeRegular(true)
                .includeSpecial(false)
                .availableFundSnapshot(pool)
                .payoutPoolAmount(pool)
                .totalEligibleContributions(pool)
                .currency("RWF")
                .status(PayoutRunStatus.PREVIEWED)
                .build());
        payoutLineRepository.saveAndFlush(PayoutLine.builder()
                .payoutRunId(run.getId())
                .cooperativeId(scheme)
                .memberUserId(member)
                .eligibleContributionAmount(pool)
                .percentage(new BigDecimal("100"))
                .payoutAmount(pool)
                .status(PayoutLineStatus.PENDING)
                .build());
        return run.getId();
    }

    protected UUID plannedInvestment(UUID scheme, String amount) {
        return investmentRepository
                .saveAndFlush(Investment.builder()
                        .cooperativeId(scheme)
                        .name("fund-lock test investment")
                        .amount(new BigDecimal(amount))
                        .remainingCapital(BigDecimal.ZERO)
                        .totalCapitalReturned(BigDecimal.ZERO)
                        .totalProfitReturned(BigDecimal.ZERO)
                        .status(InvestmentStatus.PLANNED)
                        .build())
                .getId();
    }

    protected BigDecimal funds(UUID scheme) {
        return fundService.calculateAvailableGroupFund(scheme);
    }

    // ------------------------------------------------------------------ operations under test (real endpoints)

    protected Callable<Outcome> disburseOp(UUID scheme, UUID loanId) {
        return () -> postAsSuperAdmin("/api/v1/cooperatives/" + scheme + "/loans/" + loanId + "/disburse");
    }

    protected Callable<Outcome> confirmPayoutOp(UUID scheme, UUID runId) {
        return () -> postAsSuperAdmin("/api/v1/cooperatives/" + scheme + "/payouts/" + runId + "/confirm");
    }

    protected Callable<Outcome> activateInvestmentOp(UUID scheme, UUID investmentId) {
        return () -> postAsSuperAdmin("/api/v1/cooperatives/" + scheme + "/investments/" + investmentId + "/activate");
    }

    protected Outcome run(Callable<Outcome> op) {
        try {
            return op.call();
        } catch (Exception e) {
            return new Outcome(-1, null, null, e.toString());
        }
    }

    /** The real endpoint, in the calling thread, so each call is its own transaction. */
    private Outcome postAsSuperAdmin(String path) {
        try {
            MvcResult result = mockMvc.perform(post(path).header("Authorization", "Bearer " + superAdminToken()))
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            String code = null;
            try {
                code = objectMapper.readTree(body).path("code").asText(null);
            } catch (Exception ignored) {
                // non-JSON body
            }
            return new Outcome(result.getResponse().getStatus(), code, body, null);
        } catch (Throwable t) {
            return new Outcome(-1, null, null, t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** Runs the tasks at the same moment, each in its own thread and transaction; bounded waits; pool always stopped. */
    protected List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS))
                    .as("worker threads must not stay blocked")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ controlled interleaving

    /** Parks the first protected posting (inside its transaction, after its funds check) until released. */
    protected static final class Hold {
        final CountDownLatch inWindow = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean claimed = new AtomicBoolean();

        void arrive() {
            if (claimed.compareAndSet(false, true)) {
                inWindow.countDown();
                try {
                    if (!release.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("hold was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void release() {
            release.countDown();
        }
    }

    protected Hold installHold(Set<LedgerTransactionType> types) {
        Hold hold = new Hold();
        Mockito.doAnswer(invocation -> {
                    LedgerService.AppendRequest request = invocation.getArgument(0);
                    if (types.contains(request.getTransactionType())) {
                        hold.arrive();
                    }
                    return invocation.callRealMethod();
                })
                .when(ledgerService)
                .appendApproved(ArgumentMatchers.any());
        return hold;
    }

    /** Counts calls of the available-funds calculation made by the operations under test. */
    protected AtomicInteger countFundsReads() {
        AtomicInteger reads = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
                    reads.incrementAndGet();
                    return invocation.callRealMethod();
                })
                .when(fundCalculationSpy)
                .calculateAvailableGroupFund(ArgumentMatchers.any());
        return reads;
    }

    /**
     * Starts {@code first}, parks it inside its posting window (it holds the fund lock and has passed its funds check),
     * starts {@code second}, proves {@code second} is waiting rather than deciding, then releases {@code first}.
     */
    protected List<Outcome> runHeldPair(
            Set<LedgerTransactionType> holdTypes, Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        Hold hold = installHold(holdTypes);
        AtomicInteger fundsReads = countFundsReads();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> firstResult = pool.submit(first);
            assertThat(hold.inWindow.await(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the first operation reached its posting window")
                    .isTrue();
            int baseline = fundsReads.get();
            assertThat(baseline).as("the first operation made exactly one funds check").isEqualTo(1);

            Future<Outcome> secondResult = pool.submit(second);
            observeSecondOperationWaiting(fundsReads, baseline);

            hold.release();
            return List.of(
                    firstResult.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondResult.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            hold.release();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS))
                    .as("worker threads must not stay blocked")
                    .isTrue();
        }
    }

    /**
     * The second operation must not have read available funds while the first still holds the lock. Where the database
     * lets us see it (PostgreSQL), it must also be queued on the lock. Never concludes anything from a timeout.
     */
    private void observeSecondOperationWaiting(AtomicInteger fundsReads, int baseline) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockWaitObservationMillis());
        boolean waiterSeen = false;
        while (System.nanoTime() < deadline) {
            assertThat(fundsReads.get())
                    .as("the second operation read available funds while the first still held the fund lock")
                    .isEqualTo(baseline);
            Boolean visible = lockWaiterVisibleInDatabase();
            if (visible != null && visible) {
                waiterSeen = true;
                break;
            }
            Thread.sleep(25);
        }
        Boolean probe = lockWaiterVisibleInDatabase();
        if (probe != null) {
            assertThat(waiterSeen || probe)
                    .as("the second operation is queued on the scheme fund lock (pg_stat_activity)")
                    .isTrue();
        }
        assertThat(fundsReads.get()).as("still no funds read by the second operation").isEqualTo(baseline);
    }

    /** How long to watch the second operation. Bounded; H2's default lock wait is about two seconds. */
    protected abstract long lockWaitObservationMillis();

    /** {@code true}/{@code false} when the database can show lock waiters, {@code null} when it cannot (H2). */
    protected abstract Boolean lockWaiterVisibleInDatabase();

    // ------------------------------------------------------------------ results

    protected record Outcome(int status, String code, String body, String thrown) {
        boolean succeeded() {
            return status == 200;
        }

        @Override
        public String toString() {
            return "status=" + status + (code == null ? "" : " code=" + code) + (thrown == null ? "" : " thrown=" + thrown);
        }
    }

    // ------------------------------------------------------------------ database observations

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    protected long disbursementLedgerRows(UUID scheme) {
        return count(
                "select count(*) from financial_ledger where cooperative_id = ? and transaction_type = 'LOAN_DISBURSEMENT'",
                scheme);
    }

    protected long outflowLedgerRows(UUID scheme) {
        return count(
                "select count(*) from financial_ledger where cooperative_id = ? and status = 'APPROVED' and "
                        + "transaction_type in ('LOAN_DISBURSEMENT','MEMBER_PAYOUT','INVESTMENT_OUTFLOW')",
                scheme);
    }

    protected BigDecimal committedOutflowTotal(UUID scheme) {
        BigDecimal total = jdbc.queryForObject(
                "select coalesce(sum(debit_amount), 0) from financial_ledger where cooperative_id = ? and status = 'APPROVED' "
                        + "and transaction_type in ('LOAN_DISBURSEMENT','MEMBER_PAYOUT','INVESTMENT_OUTFLOW')",
                BigDecimal.class,
                scheme);
        return total == null ? BigDecimal.ZERO : total;
    }

    protected long outflowAuditRows(UUID scheme) {
        return count(
                "select count(*) from audit_logs where cooperative_id = ? and action in "
                        + "('DISBURSE','PAYOUT_CONFIRM','INVESTMENT_ACTIVATE')",
                scheme);
    }

    protected String loanStatus(UUID loanId) {
        return jdbc.queryForObject("select status from loans where id = ?", String.class, loanId);
    }

    protected String payoutRunStatus(UUID runId) {
        return jdbc.queryForObject("select status from payout_runs where id = ?", String.class, runId);
    }

    protected String investmentStatus(UUID investmentId) {
        return jdbc.queryForObject("select status from investments where id = ?", String.class, investmentId);
    }

    /**
     * Invariants for a scheme seeded with {@code seeded} funds after {@code successes} protected outflows committed:
     * the ledger, the audit trail and the funds all agree with exactly those outflows, and funds are not negative.
     */
    protected void assertOutflowRecordsMatch(UUID scheme, String seeded, long successes) {
        assertThat(outflowLedgerRows(scheme))
                .as("one approved ledger posting per successful outflow")
                .isEqualTo(successes);
        assertThat(outflowAuditRows(scheme)).as("one audit row per successful outflow").isEqualTo(successes);
        BigDecimal funds = funds(scheme);
        assertThat(funds)
                .as("available funds equal the seeded funds minus exactly the committed outflows")
                .isEqualByComparingTo(new BigDecimal(seeded).subtract(committedOutflowTotal(scheme)));
        assertThat(funds.signum()).as("available funds are never negative").isGreaterThanOrEqualTo(0);
        assertThat(count(
                        "select count(*) from loans l where l.cooperative_id = ? and l.status = 'ACTIVE' and "
                                + "(select count(*) from financial_ledger e where e.source_entity_id = l.id "
                                + "and e.transaction_type = 'LOAN_DISBURSEMENT' and e.status = 'APPROVED' "
                                + "and e.debit_amount = l.principal_amount and e.cooperative_id = l.cooperative_id) <> 1",
                        scheme))
                .as("every ACTIVE loan has exactly one approved disbursement of its principal")
                .isZero();
        assertThat(count(
                        "select count(*) from loans l where l.cooperative_id = ? and l.status <> 'ACTIVE' and exists "
                                + "(select 1 from financial_ledger e where e.source_entity_id = l.id "
                                + "and e.transaction_type = 'LOAN_DISBURSEMENT')",
                        scheme))
                .as("no disbursement exists for a loan that is not ACTIVE")
                .isZero();
        assertThat(count(
                        "select count(*) from (select source_entity_id from financial_ledger where cooperative_id = ? "
                                + "and transaction_type in ('LOAN_DISBURSEMENT','INVESTMENT_OUTFLOW') "
                                + "group by source_entity_id having count(*) > 1) d",
                        scheme))
                .as("no loan or investment is posted twice")
                .isZero();
        assertThat(count(
                        "select count(*) from financial_ledger e where e.cooperative_id = ? "
                                + "and e.transaction_type = 'LOAN_DISBURSEMENT' and not exists "
                                + "(select 1 from loans l where l.id = e.source_entity_id and l.cooperative_id = e.cooperative_id)",
                        scheme))
                .as("every disbursement row belongs to a loan of the same scheme")
                .isZero();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }
}
