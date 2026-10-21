package rw.terimbere.csams.shared.financial;

public enum LedgerTransactionType {
    REGULAR_CONTRIBUTION,
    SPECIAL_CONTRIBUTION,
    LOAN_DISBURSEMENT,
    LOAN_PRINCIPAL_REPAYMENT,
    LOAN_INTEREST_PAYMENT,
    FINE_PAYMENT,
    SOCIAL_CONTRIBUTION,
    SOCIAL_DISBURSEMENT,
    INVESTMENT_OUTFLOW,
    INVESTMENT_CAPITAL_RETURN,
    INVESTMENT_PROFIT,
    OTHER_INCOME,
    GENERAL_EXPENSE,
    INTEREST_EXPENSE,
    MEMBER_PAYOUT,
    SHARE_PURCHASE,
    ADJUSTMENT,
    REVERSAL,
    /** Excess money a member paid, held for them: never income and never part of the group's available funds. */
    MEMBER_CREDIT_RECEIVED,
    /** Held credit applied to a contribution or a loan: the credit leaves the member's balance. */
    MEMBER_CREDIT_APPLIED,
    /** Held credit taken back because the payment that created it was reversed. */
    MEMBER_CREDIT_REVERSED
}
