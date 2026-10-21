package rw.terimbere.csams.modules.dashboard.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Read-only installment timeliness classification for repayment-reliability analytics.
 *
 * <p>Fully paid matches {@code LoanInstallment.refreshStatus}: remaining obligation is zero (or
 * negative) via {@link MoneyUtils}, or persisted status is {@link LoanInstallmentStatus#PAID}.
 *
 * <p>Completion date is {@code MAX(LoanRepayment.paymentDate)} across allocations — not the first
 * payment — so partial early payments that finish late classify as late.
 */
public final class RepaymentReliabilityClassifier {

    private RepaymentReliabilityClassifier() {}

    public record InstallmentFact(
            UUID memberUserId,
            UUID installmentId,
            LocalDate dueDate,
            LoanInstallmentStatus status,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal penaltyDue,
            BigDecimal principalPaid,
            BigDecimal interestPaid,
            BigDecimal penaltyPaid,
            LocalDate completedOn) {}

    public static boolean isFullyPaid(InstallmentFact fact) {
        if (fact == null) {
            return false;
        }
        if (fact.status() == LoanInstallmentStatus.PAID) {
            return true;
        }
        BigDecimal remaining = remainingObligation(fact);
        return MoneyUtils.isZero(remaining) || remaining.compareTo(BigDecimal.ZERO) < 0;
    }

    public static BigDecimal remainingObligation(InstallmentFact fact) {
        BigDecimal due = MoneyUtils.add(
                MoneyUtils.add(nvl(fact.principalDue()), nvl(fact.interestDue())), nvl(fact.penaltyDue()));
        BigDecimal paid = MoneyUtils.add(
                MoneyUtils.add(nvl(fact.principalPaid()), nvl(fact.interestPaid())), nvl(fact.penaltyPaid()));
        return MoneyUtils.subtract(due, paid);
    }

    /**
     * Classify one installment as of {@code asOfDate} (cooperative-local today).
     *
     * <ul>
     *   <li>Fully paid with completion date on/before due → {@link RepaymentTimeliness#ON_TIME}
     *   <li>Fully paid with completion after due → {@link RepaymentTimeliness#PAID_LATE}
     *   <li>Fully paid without completion date → {@link RepaymentTimeliness#EXCLUDED} (data quality)
     *   <li>Not fully paid and dueDate &lt; asOf → {@link RepaymentTimeliness#UNPAID_PAST_DUE}
     *   <li>Otherwise (future or due-today unpaid) → {@link RepaymentTimeliness#EXCLUDED}
     * </ul>
     */
    public static RepaymentTimeliness classify(InstallmentFact fact, LocalDate asOfDate) {
        if (fact == null || fact.dueDate() == null || asOfDate == null) {
            return RepaymentTimeliness.EXCLUDED;
        }

        if (isFullyPaid(fact)) {
            LocalDate completedOn = fact.completedOn();
            if (completedOn == null) {
                return RepaymentTimeliness.EXCLUDED;
            }
            if (!completedOn.isAfter(fact.dueDate())) {
                return RepaymentTimeliness.ON_TIME;
            }
            return RepaymentTimeliness.PAID_LATE;
        }

        if (fact.dueDate().isBefore(asOfDate)) {
            return RepaymentTimeliness.UNPAID_PAST_DUE;
        }
        return RepaymentTimeliness.EXCLUDED;
    }

    /** True when a fully-paid installment lacked a repayment completion date. */
    public static boolean isDataQualityExcluded(InstallmentFact fact, LocalDate asOfDate) {
        return isFullyPaid(fact) && fact.completedOn() == null && classify(fact, asOfDate) == RepaymentTimeliness.EXCLUDED;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE) : MoneyUtils.scale(value);
    }
}
