package rw.terimbere.csams.modules.loan.entity;

/**
 * Ikimina-level rule for monthly installment due dates.
 *
 * <p>{@link #SAME_DAY_OF_MONTH} uses a full first month and the disbursement
 * calendar day as the cycle day. {@link #MONTH_END} uses calendar month-end
 * dates and prorates the first period.
 */
public enum LoanRepaymentDateModel {
    SAME_DAY_OF_MONTH,
    MONTH_END
}
