package rw.terimbere.csams.modules.dashboard.support;

/**
 * Timeliness classification for a single loan installment relative to its due date.
 *
 * <p>{@link #EXCLUDED} covers future/open installments, due-today unpaid installments, and
 * data-quality gaps (paid without a completion payment date).
 */
public enum RepaymentTimeliness {
    ON_TIME,
    PAID_LATE,
    UNPAID_PAST_DUE,
    EXCLUDED
}
