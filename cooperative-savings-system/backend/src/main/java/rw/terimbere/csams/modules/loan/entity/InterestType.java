package rw.terimbere.csams.modules.loan.entity;

/**
 * Interest calculation type snapshotted onto each loan at creation.
 * <p>FLAT is fully supported: monthly interest = original principal × rate/100,
 * charged for each installment (not declining balance). Optional first-period
 * prorata uses a 30-day financial month. See {@code LoanScheduleCalculator}.
 * REDUCING is blocked for new loans; legacy rows keep their stored formula.
 */
public enum InterestType {
    FLAT,
    REDUCING
}
