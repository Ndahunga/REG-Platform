package rw.terimbere.csams.fundlock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import rw.terimbere.csams.shared.auditing.AuditableAction;

/**
 * Behaviour of the scheme fund lock on the three protected outflows (loan disbursement, payout confirmation,
 * investment activation). Database agnostic: run on H2 by {@code SchemeFundLockH2Test} (normal suite) and on
 * PostgreSQL by {@code SchemeFundLockPostgresIT} (opt-in, needs Docker).
 *
 * <p>Every scheme here starts with RWF 100,000 of available funds. Pairs are 80,000 + 70,000, so one outflow fits and
 * two do not. The held-pair tests are the deterministic proof (see {@link FundConcurrencySupport}); the free-race
 * test only adds confidence and does not assume a fixed winner.
 *
 * <p>Scope: only these three operations take the lock. Expense approval, contribution corrections and social fund
 * disbursements do not, so none of this proves that available funds can never become negative overall.
 */
@Timeout(300)
abstract class SchemeFundLockScenarios extends FundConcurrencySupport {

    // ------------------------------------------------------------------ sequential behaviour is unchanged

    @Test
    void loanDisbursementSucceedsWithEnoughFundsAndIsRejectedWithoutThem() throws Exception {
        UUID scheme = createScheme("FL Seq Loan");
        UUID loanA = approvedLoan(scheme, registerMember(scheme), "80000");
        UUID loanB = approvedLoan(scheme, registerMember(scheme), "70000");
        seedAvailableFunds(scheme, "100000");

        Outcome first = run(disburseOp(scheme, loanA));
        Outcome second = run(disburseOp(scheme, loanB));

        assertThat(first.succeeded()).as(first.toString()).isTrue();
        assertThat(second.status()).as(second.toString()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("INSUFFICIENT_GROUP_FUND");
        assertThat(loanStatus(loanA)).isEqualTo("ACTIVE");
        assertThat(loanStatus(loanB)).isEqualTo("APPROVED");
        assertOutflowRecordsMatch(scheme, "100000", 1);
    }

    @Test
    void payoutConfirmationKeepsItsBehaviour() throws Exception {
        UUID scheme = createScheme("FL Seq Payout");
        UUID member = registerMember(scheme);
        seedAvailableFunds(scheme, "100000");
        UUID fits = previewedPayoutRun(scheme, member, "60000");
        UUID tooBig = previewedPayoutRun(scheme, member, "60000");

        Outcome first = run(confirmPayoutOp(scheme, fits));
        Outcome second = run(confirmPayoutOp(scheme, tooBig));

        assertThat(first.succeeded()).as(first.toString()).isTrue();
        assertThat(second.status()).as(second.toString()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("INSUFFICIENT_GROUP_FUND");
        assertThat(payoutRunStatus(fits)).isEqualTo("CONFIRMED");
        assertThat(payoutRunStatus(tooBig)).isEqualTo("PREVIEWED");
        assertOutflowRecordsMatch(scheme, "100000", 1);
    }

    @Test
    void investmentActivationKeepsItsBehaviour() throws Exception {
        UUID scheme = createScheme("FL Seq Invest");
        seedAvailableFunds(scheme, "100000");
        UUID fits = plannedInvestment(scheme, "60000");
        UUID tooBig = plannedInvestment(scheme, "60000");

        Outcome first = run(activateInvestmentOp(scheme, fits));
        Outcome second = run(activateInvestmentOp(scheme, tooBig));

        assertThat(first.succeeded()).as(first.toString()).isTrue();
        assertThat(second.status()).as(second.toString()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("INSUFFICIENT_GROUP_FUND");
        assertThat(investmentStatus(fits)).isEqualTo("ACTIVE");
        assertThat(investmentStatus(tooBig)).isEqualTo("PLANNED");
        assertOutflowRecordsMatch(scheme, "100000", 1);
    }

    // ------------------------------------------------------------------ same scheme: the second waits, then sees the first

    @Test
    void twoDifferentLoansCompetingForInsufficientFundsAdmitExactlyOne() throws Exception {
        UUID scheme = createScheme("FL Loans Held");
        UUID loanA = approvedLoan(scheme, registerMember(scheme), "80000");
        UUID loanB = approvedLoan(scheme, registerMember(scheme), "70000");
        seedAvailableFunds(scheme, "100000");

        List<Outcome> outcomes =
                runHeldPair(LOAN_POSTING, disburseOp(scheme, loanA), disburseOp(scheme, loanB));

        assertThat(outcomes.get(0).succeeded()).as(outcomes.toString()).isTrue();
        assertRejectedForFunds(outcomes.get(1));
        assertThat(loanStatus(loanA)).isEqualTo("ACTIVE");
        assertThat(loanStatus(loanB)).isEqualTo("APPROVED");
        assertOutflowRecordsMatch(scheme, "100000", 1);
    }

    @Test
    void aFreeRaceBetweenTwoLoansNeverOvercommitsWhoeverWins() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID scheme = createScheme("FL Loans Race " + round);
            UUID loanA = approvedLoan(scheme, registerMember(scheme), "80000");
            UUID loanB = approvedLoan(scheme, registerMember(scheme), "70000");
            seedAvailableFunds(scheme, "100000");

            List<Outcome> outcomes =
                    runConcurrently(List.of(disburseOp(scheme, loanA), disburseOp(scheme, loanB)));

            long successes = outcomes.stream().filter(Outcome::succeeded).count();
            assertThat(successes).as("round %d: %s", round, outcomes).isEqualTo(1);
            outcomes.stream().filter(o -> !o.succeeded()).forEach(this::assertRejectedForFunds);
            assertOutflowRecordsMatch(scheme, "100000", 1);
        }
    }

    @Test
    void aLoanDisbursementAndAPayoutConfirmationCannotSpendTheSameFunds() throws Exception {
        UUID scheme = createScheme("FL Loan v Payout");
        UUID loan = approvedLoan(scheme, registerMember(scheme), "80000");
        UUID run = previewedPayoutRun(scheme, registerMember(scheme), "80000");
        seedAvailableFunds(scheme, "100000");

        List<Outcome> outcomes = runHeldPair(LOAN_POSTING, disburseOp(scheme, loan), confirmPayoutOp(scheme, run));

        assertThat(outcomes.get(0).succeeded()).as(outcomes.toString()).isTrue();
        assertRejectedForFunds(outcomes.get(1));
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertThat(payoutRunStatus(run)).isEqualTo("PREVIEWED");
        assertOutflowRecordsMatch(scheme, "100000", 1);
    }

    @Test
    void aLoanDisbursementAndAnInvestmentActivationCannotSpendTheSameFunds() throws Exception {
        UUID scheme = createScheme("FL Loan v Invest");
        UUID loan = approvedLoan(scheme, registerMember(scheme), "80000");
        UUID investment = plannedInvestment(scheme, "70000");
        seedAvailableFunds(scheme, "100000");

        List<Outcome> outcomes =
                runHeldPair(LOAN_POSTING, disburseOp(scheme, loan), activateInvestmentOp(scheme, investment));

        assertThat(outcomes.get(0).succeeded()).as(outcomes.toString()).isTrue();
        assertRejectedForFunds(outcomes.get(1));
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertThat(investmentStatus(investment)).isEqualTo("PLANNED");
        assertOutflowRecordsMatch(scheme, "100000", 1);
    }

    @Test
    void twoInvestmentActivationsCannotSpendTheSameFunds() throws Exception {
        UUID scheme = createScheme("FL Two Invest");
        UUID first = plannedInvestment(scheme, "80000");
        UUID second = plannedInvestment(scheme, "70000");
        seedAvailableFunds(scheme, "100000");

        List<Outcome> outcomes = runHeldPair(
                INVESTMENT_POSTING, activateInvestmentOp(scheme, first), activateInvestmentOp(scheme, second));

        assertThat(outcomes.get(0).succeeded()).as(outcomes.toString()).isTrue();
        assertRejectedForFunds(outcomes.get(1));
        assertThat(investmentStatus(first)).isEqualTo("ACTIVE");
        assertThat(investmentStatus(second)).isEqualTo("PLANNED");
        assertOutflowRecordsMatch(scheme, "100000", 1);
    }

    @Test
    void aDuplicateDisbursementRequestWaitsThenIsRefusedCleanly() throws Exception {
        UUID scheme = createScheme("FL Duplicate");
        UUID loan = approvedLoan(scheme, registerMember(scheme), "80000");
        seedAvailableFunds(scheme, "100000");

        List<Outcome> outcomes = runHeldPair(LOAN_POSTING, disburseOp(scheme, loan), disburseOp(scheme, loan));

        assertThat(outcomes.get(0).succeeded()).as(outcomes.toString()).isTrue();
        // Because the lock is taken before the loan is read, the duplicate sees ACTIVE and gets an ordinary business
        // error instead of a version conflict or a unique-key violation.
        assertThat(outcomes.get(1).status()).as(outcomes.toString()).isEqualTo(422);
        assertThat(outcomes.get(1).body()).contains("Only APPROVED loans can be disbursed");
        assertThat(loanStatus(loan)).isEqualTo("ACTIVE");
        assertThat(disbursementLedgerRows(scheme)).isEqualTo(1);
        assertOutflowRecordsMatch(scheme, "100000", 1);
    }

    // ------------------------------------------------------------------ different schemes stay independent

    @Test
    void anOperationInAnotherSchemeCompletesWhileThisSchemesLockIsHeld() throws Exception {
        UUID schemeA = createScheme("FL Tenant A");
        UUID schemeB = createScheme("FL Tenant B");
        UUID loanA = approvedLoan(schemeA, registerMember(schemeA), "80000");
        UUID loanB = approvedLoan(schemeB, registerMember(schemeB), "80000");
        seedAvailableFunds(schemeA, "100000");
        seedAvailableFunds(schemeB, "100000");

        Hold hold = installHold(LOAN_POSTING);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> heldA = pool.submit(disburseOp(schemeA, loanA));
            assertThat(hold.inWindow.await(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("scheme A's operation is parked inside its window, holding scheme A's lock")
                    .isTrue();

            // Scheme B finishes completely while scheme A is still parked: eventual success alone would not prove
            // this, completion before A's release does.
            Future<Outcome> otherB = pool.submit(disburseOp(schemeB, loanB));
            Outcome b = otherB.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(heldA.isDone()).as("scheme A is still parked when scheme B has finished").isFalse();
            assertThat(b.succeeded()).as(b.toString()).isTrue();
            assertThat(loanStatus(loanB)).isEqualTo("ACTIVE");

            hold.release();
            Outcome a = heldA.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(a.succeeded()).as(a.toString()).isTrue();
        } finally {
            hold.release();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(loanStatus(loanA)).isEqualTo("ACTIVE");
        assertOutflowRecordsMatch(schemeA, "100000", 1);
        assertOutflowRecordsMatch(schemeB, "100000", 1);
        assertThat(count(
                        "select count(*) from financial_ledger where cooperative_id = ? and source_entity_id = ?",
                        schemeA,
                        loanB))
                .as("scheme A's ledger holds nothing about scheme B's loan")
                .isZero();
        assertThat(count(
                        "select count(*) from financial_ledger where cooperative_id = ? and source_entity_id = ?",
                        schemeB,
                        loanA))
                .isZero();
    }

    // ------------------------------------------------------------------ rollback releases the lock

    @Test
    void aFailureAfterTheLockAndEveryWriteRollsBackAndReleasesTheLock() throws Exception {
        UUID scheme = createScheme("FL Rollback");
        UUID failing = approvedLoan(scheme, registerMember(scheme), "80000");
        UUID healthy = approvedLoan(scheme, registerMember(scheme), "70000");
        seedAvailableFunds(scheme, "100000");

        // Test-controlled failure (a spy on an existing bean, no production hook): the real audit write happens, the
        // persistence context is flushed so the loan UPDATE, the ledger INSERT and the audit INSERT are all sent to
        // the database inside the open transaction, and then the request fails while it still holds the fund lock.
        Mockito.doAnswer(invocation -> {
                    invocation.callRealMethod();
                    entityManager.flush();
                    throw new IllegalStateException("injected failure after flush");
                })
                .when(auditService)
                .record(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(AuditableAction.DISBURSE),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());

        Outcome failed = run(disburseOp(scheme, failing));

        assertThat(failed.status()).as("the injected failure is an unexpected error: %s", failed).isEqualTo(500);
        assertThat(loanStatus(failing)).isEqualTo("APPROVED");
        assertThat(disbursementLedgerRows(scheme)).isZero();
        assertThat(outflowAuditRows(scheme)).isZero();
        assertThat(count("select count(*) from loan_installments where loan_id = ?", failing)).isZero();
        assertThat(funds(scheme)).isEqualByComparingTo("100000");

        // Lock release: another transaction on the same scheme must get the lock and finish, within a bounded time.
        Mockito.reset(auditService);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<Outcome> next = disburseOp(scheme, healthy);
            Outcome afterFailure = pool.submit(next).get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(afterFailure.succeeded())
                    .as("the failed transaction released the fund lock: %s", afterFailure)
                    .isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(loanStatus(healthy)).isEqualTo("ACTIVE");
        assertOutflowRecordsMatch(scheme, "100000", 1);

        // And the loan that failed can still be disbursed (it needs 80,000; only 30,000 is left, so it is refused for
        // funds, not blocked): the rollback left it fully intact.
        Outcome retry = run(disburseOp(scheme, failing));
        assertRejectedForFunds(retry);
        assertThat(loanStatus(failing)).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ helpers

    private void assertRejectedForFunds(Outcome outcome) {
        assertThat(outcome.status()).as("rejected after seeing the updated funds: %s", outcome).isEqualTo(422);
        assertThat(outcome.code()).isEqualTo("INSUFFICIENT_GROUP_FUND");
    }
}
