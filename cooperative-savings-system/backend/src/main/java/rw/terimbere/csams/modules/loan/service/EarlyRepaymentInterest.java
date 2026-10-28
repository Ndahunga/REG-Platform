package rw.terimbere.csams.modules.loan.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Interest charged only for the months the money was used. Months are counted from the disbursement date; a started
 * month counts as a whole month and at least one month is charged, never more than the term. For a flat-rate loan the
 * interest earned is the original principal × the monthly rate × those months, capped at the contract's interest.
 *
 * <p>Example: 500,000 at 2% a month for 5 months, disbursed 22 June, repaid 21 September: 3 months (the third one
 * started), 30,000 interest. Settling then takes 500,000 + 30,000; the other 20,000 of the contract is not owed.
 */
public final class EarlyRepaymentInterest {

    private EarlyRepaymentInterest() {}

    /** What repaying the whole loan on a date takes. */
    public record Settlement(
            LocalDate asOf,
            int monthsCharged,
            BigDecimal interestEarned,
            BigDecimal interestPaid,
            BigDecimal interestToPay,
            BigDecimal principal,
            BigDecimal penalty,
            BigDecimal total,
            BigDecimal interestWaived) {}

    public static LocalDate start(Loan loan) {
        if (loan.getDisbursementDate() != null) return loan.getDisbursementDate();
        if (loan.getApprovalDate() != null) return loan.getApprovalDate();
        return loan.getRequestDate();
    }

    /** Months charged when repaying on {@code date}: started months count whole, at least 1, at most the term. */
    public static int monthsCharged(LocalDate start, LocalDate date, int termMonths) {
        int term = Math.max(1, termMonths);
        if (start == null || date == null) return term;
        int months = 0;
        while (months < term && start.plusMonths(months).isBefore(date)) {
            months++;
        }
        return Math.min(term, Math.max(1, months));
    }

    /** Interest the loan has earned by {@code date}; the whole contract interest for a loan that is not flat-rate. */
    public static BigDecimal interestEarned(Loan loan, LocalDate date) {
        BigDecimal contract = loan.getInterestAmount() == null ? null : MoneyUtils.scale(loan.getInterestAmount());
        BigDecimal principal = loan.getPrincipalAmount() != null ? loan.getPrincipalAmount() : loan.getApprovedAmount();
        if (loan.getInterestType() != InterestType.FLAT || principal == null || loan.getInterestRatePercent() == null) {
            return contract == null ? MoneyUtils.scale(BigDecimal.ZERO) : contract;
        }
        int months = monthsCharged(start(loan), date, loan.getTermMonths());
        BigDecimal earned = principal
                .multiply(loan.getInterestRatePercent())
                .multiply(BigDecimal.valueOf(months))
                .divide(BigDecimal.valueOf(100), MoneyUtils.MONEY_SCALE, RoundingMode.HALF_UP);
        return contract == null ? earned : earned.min(contract);
    }

    /**
     * Repaying in full on {@code date}: the outstanding principal (and penalty when {@code withPenalty}), plus the
     * interest earned by then that was not paid yet. The rest of the outstanding interest is waived.
     */
    public static Settlement settlement(
            Loan loan, LocalDate date, BigDecimal outstandingPrincipal, BigDecimal outstandingInterest,
            BigDecimal outstandingPenalty, boolean withPenalty) {
        BigDecimal paid = MoneyUtils.scale(nz(loan.getTotalRepaidInterest()));
        BigDecimal earned = interestEarned(loan, date);
        BigDecimal outstanding = MoneyUtils.scale(nz(outstandingInterest));
        BigDecimal toPay = earned.subtract(paid).max(BigDecimal.ZERO).min(outstanding);
        BigDecimal principal = MoneyUtils.scale(nz(outstandingPrincipal));
        BigDecimal penalty = withPenalty ? MoneyUtils.scale(nz(outstandingPenalty)) : MoneyUtils.scale(BigDecimal.ZERO);
        BigDecimal total = principal.add(toPay).add(penalty);
        return new Settlement(
                date,
                monthsCharged(start(loan), date, loan.getTermMonths()),
                MoneyUtils.scale(earned),
                paid,
                MoneyUtils.scale(toPay),
                principal,
                penalty,
                MoneyUtils.scale(total),
                MoneyUtils.scale(outstanding.subtract(toPay)));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
