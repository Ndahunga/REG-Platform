package rw.terimbere.csams.modules.loan.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;

/**
 * Authoritative overdue predicates shared by operational status refresh and read-only paths.
 *
 * <p>{@link #ACTIVE_PAST_DUE_WITH_BALANCE} is the WHERE clause used by {@code LoanRepository.markOverdue}
 * (ACTIVE → OVERDUE write). {@link #CURRENTLY_OVERDUE_FOR_ANALYTICS} is the non-mutating SELECT
 * equivalent. Java helpers mirror those rules for response mapping without mutating entities.
 */
public final class LoanOverdueRules {

    private LoanOverdueRules() {}

    /**
     * Predicate for transitioning ACTIVE loans to OVERDUE.
     *
     * <p>Requires bind parameter {@code :today}.
     */
    public static final String ACTIVE_PAST_DUE_WITH_BALANCE =
            """
            l.status = rw.terimbere.csams.modules.loan.entity.LoanStatus.ACTIVE
              AND l.dueDate IS NOT NULL
              AND l.dueDate < :today
              AND (l.outstandingPrincipal + l.outstandingInterest) > 0
            """;

    /**
     * Read-only overdue follow-up predicate (no status mutation).
     *
     * <p>Requires bind parameter {@code :today}.
     */
    public static final String CURRENTLY_OVERDUE_FOR_ANALYTICS =
            """
            (
                l.status = rw.terimbere.csams.modules.loan.entity.LoanStatus.OVERDUE
                OR (
                  l.status = rw.terimbere.csams.modules.loan.entity.LoanStatus.ACTIVE
                  AND l.dueDate IS NOT NULL
                  AND l.dueDate < :today
                )
              )
              AND (l.outstandingPrincipal + l.outstandingInterest) > 0
            """;

    /**
     * ACTIVE loans that are not currently past-due with a balance (effective ACTIVE after markOverdue).
     *
     * <p>Requires bind parameter {@code :today}.
     */
    public static final String ACTIVE_NOT_CURRENTLY_OVERDUE =
            """
            l.status = rw.terimbere.csams.modules.loan.entity.LoanStatus.ACTIVE
              AND NOT (
                l.dueDate IS NOT NULL
                AND l.dueDate < :today
                AND (l.outstandingPrincipal + l.outstandingInterest) > 0
              )
            """;

    /** True when an ACTIVE loan would be transitioned by {@code markOverdue}. */
    public static boolean matchesActivePastDueWithBalance(Loan loan, LocalDate today) {
        if (loan == null || today == null || loan.getStatus() != LoanStatus.ACTIVE) {
            return false;
        }
        if (loan.getDueDate() == null || !loan.getDueDate().isBefore(today)) {
            return false;
        }
        return outstandingBalance(loan).compareTo(BigDecimal.ZERO) > 0;
    }

    /** True when the loan is overdue for analytics / follow-up (stored OVERDUE or ACTIVE past-due). */
    public static boolean isCurrentlyOverdue(Loan loan, LocalDate today) {
        if (loan == null || today == null) {
            return false;
        }
        if (outstandingBalance(loan).compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (loan.getStatus() == LoanStatus.OVERDUE) {
            return true;
        }
        return matchesActivePastDueWithBalance(loan, today);
    }

    /**
     * Status for API responses: ACTIVE past-due with balance is presented as OVERDUE without mutating
     * the entity. All other statuses are returned as stored.
     */
    public static LoanStatus effectiveStatus(Loan loan, LocalDate today) {
        if (matchesActivePastDueWithBalance(loan, today)) {
            return LoanStatus.OVERDUE;
        }
        return loan.getStatus();
    }

    private static BigDecimal outstandingBalance(Loan loan) {
        BigDecimal principal =
                loan.getOutstandingPrincipal() == null ? BigDecimal.ZERO : loan.getOutstandingPrincipal();
        BigDecimal interest =
                loan.getOutstandingInterest() == null ? BigDecimal.ZERO : loan.getOutstandingInterest();
        return principal.add(interest);
    }
}
