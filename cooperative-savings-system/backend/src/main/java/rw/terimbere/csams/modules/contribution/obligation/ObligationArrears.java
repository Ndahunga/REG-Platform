package rw.terimbere.csams.modules.contribution.obligation;

import java.math.BigDecimal;
import java.time.LocalDate;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Arrears arithmetic for one contribution obligation.
 *
 * <p>{@code paid} is the amount the existing accounting currently recognizes as paid for the period (the running
 * {@code contributions.paid_amount}); it is not a statement that a verified receipt exists. Expected and outstanding
 * amounts are obligations, never collected cash.
 *
 * <p>An obligation is overdue only AFTER its due date: on the due date itself it is still on time. With no due date
 * (a scheme that never configured one, or a row that predates the setting) nothing is ever reported overdue.
 */
public final class ObligationArrears {

    private ObligationArrears() {}

    public record Result(BigDecimal outstanding, boolean overdue, BigDecimal overdueAmount) {}

    /**
     * @param closed true for a waived or cancelled obligation, which owes nothing
     */
    public static Result calculate(
            BigDecimal expected, BigDecimal paid, boolean closed, LocalDate dueDate, LocalDate today) {
        BigDecimal outstanding = closed
                ? BigDecimal.ZERO
                : MoneyUtils.scaleForStorage(
                        (expected == null ? BigDecimal.ZERO : expected)
                                .subtract(paid == null ? BigDecimal.ZERO : paid)
                                .max(BigDecimal.ZERO));
        boolean overdue = dueDate != null && today.isAfter(dueDate) && outstanding.signum() > 0;
        BigDecimal overdueAmount = overdue ? outstanding : MoneyUtils.scaleForStorage(BigDecimal.ZERO);
        return new Result(MoneyUtils.scaleForStorage(outstanding), overdue, overdueAmount);
    }
}
