package rw.terimbere.csams.modules.historicalimport.transactions;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * The historical events the workbook may carry. Anything else is refused explicitly rather than guessed (share
 * purchases, dividends, social-fund or investment movements, loan write-offs and reversals have their own rules and are
 * not importable as history).
 *
 * <p>Loan events and member payouts are recognised only so that they can be refused with a precise explanation: a loan
 * needs an instalment schedule and an interest/penalty breakdown, and a payout needs a rotation period and recipient,
 * none of which a dated list of cash movements contains. Importing them as ledger entries alone would leave the loan and
 * payout records empty while the fund looked reconciled, so they are rejected at validation instead of being guessed.
 * Loans still open belong in the opening-balance loan migration.
 */
public enum HistoricalTxType {
    /** A contribution paid for a member and a period. Recorded as a historical obligation with no rotation attribution. */
    CONTRIBUTION(true, true, LedgerTransactionType.REGULAR_CONTRIBUTION),
    LOAN_DISBURSEMENT(false, true, LedgerTransactionType.LOAN_DISBURSEMENT),
    LOAN_PRINCIPAL_REPAYMENT(true, true, LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT),
    LOAN_INTEREST_PAYMENT(true, true, LedgerTransactionType.LOAN_INTEREST_PAYMENT),
    FINE_PAYMENT(true, true, LedgerTransactionType.FINE_PAYMENT),
    /** A payout actually paid to a member. Never reduces any entitlement: attribution is unresolved for history. */
    MEMBER_PAYOUT(false, true, LedgerTransactionType.MEMBER_PAYOUT),
    OTHER_INCOME(true, false, LedgerTransactionType.OTHER_INCOME),
    GENERAL_EXPENSE(false, false, LedgerTransactionType.GENERAL_EXPENSE);

    private final boolean credit;
    private final boolean needsMember;
    private final LedgerTransactionType ledgerType;

    HistoricalTxType(boolean credit, boolean needsMember, LedgerTransactionType ledgerType) {
        this.credit = credit;
        this.needsMember = needsMember;
        this.ledgerType = ledgerType;
    }

    /** True when money comes into the fund. */
    public boolean credit() {
        return credit;
    }

    public boolean needsMember() {
        return needsMember;
    }

    /** False for the events that are recognised only to be refused (loans and payouts). */
    public boolean supported() {
        return !isLoanEvent() && this != MEMBER_PAYOUT;
    }

    public boolean isLoanEvent() {
        return this == LOAN_DISBURSEMENT || this == LOAN_PRINCIPAL_REPAYMENT || this == LOAN_INTEREST_PAYMENT;
    }

    public LedgerTransactionType ledgerType() {
        return ledgerType;
    }

    public static Optional<HistoricalTxType> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return Arrays.stream(values()).filter(t -> t.name().equals(normalized)).findFirst();
    }

    public static List<String> names() {
        return Arrays.stream(values()).filter(HistoricalTxType::supported).map(Enum::name).toList();
    }
}
