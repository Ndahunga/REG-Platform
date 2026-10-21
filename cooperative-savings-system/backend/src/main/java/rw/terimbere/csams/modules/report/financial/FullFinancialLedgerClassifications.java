package rw.terimbere.csams.modules.report.financial;

import java.util.EnumSet;
import java.util.Set;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * Classification of ledger types for Full Financial statements.
 *
 * <p>Social fund types are excluded from main-fund cash flow and opening/closing ledger balances.
 * Contributions / share purchases are capital inflows, never operating income. Member credit (money a member paid
 * that is held for them) is in neither the inflow nor the outflow set: it is not group money until it is applied to a
 * contribution or a loan, and then the contribution or repayment entry is the inflow.
 */
public final class FullFinancialLedgerClassifications {

    public static final Set<LedgerTransactionType> SOCIAL_FUND_TYPES = EnumSet.of(
            LedgerTransactionType.SOCIAL_CONTRIBUTION, LedgerTransactionType.SOCIAL_DISBURSEMENT);

    /** Operating income (approved credit activity). */
    public static final Set<LedgerTransactionType> OPERATING_INCOME_TYPES = EnumSet.of(
            LedgerTransactionType.LOAN_INTEREST_PAYMENT,
            LedgerTransactionType.FINE_PAYMENT,
            LedgerTransactionType.INVESTMENT_PROFIT,
            LedgerTransactionType.OTHER_INCOME);

    /** Operating expenses (approved debit activity). */
    public static final Set<LedgerTransactionType> OPERATING_EXPENSE_TYPES =
            EnumSet.of(LedgerTransactionType.GENERAL_EXPENSE, LedgerTransactionType.INTEREST_EXPENSE);

    /** Main-fund cash inflows (credits). Capital and operating cash receipts. */
    public static final Set<LedgerTransactionType> CASH_INFLOW_TYPES = EnumSet.of(
            LedgerTransactionType.REGULAR_CONTRIBUTION,
            LedgerTransactionType.SPECIAL_CONTRIBUTION,
            LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT,
            LedgerTransactionType.LOAN_INTEREST_PAYMENT,
            LedgerTransactionType.FINE_PAYMENT,
            LedgerTransactionType.INVESTMENT_CAPITAL_RETURN,
            LedgerTransactionType.INVESTMENT_PROFIT,
            LedgerTransactionType.OTHER_INCOME,
            LedgerTransactionType.SHARE_PURCHASE);

    /** Main-fund cash outflows (debits). */
    public static final Set<LedgerTransactionType> CASH_OUTFLOW_TYPES = EnumSet.of(
            LedgerTransactionType.LOAN_DISBURSEMENT,
            LedgerTransactionType.INVESTMENT_OUTFLOW,
            LedgerTransactionType.GENERAL_EXPENSE,
            LedgerTransactionType.INTEREST_EXPENSE,
            LedgerTransactionType.MEMBER_PAYOUT);

    private FullFinancialLedgerClassifications() {}

    public static String label(LedgerTransactionType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case REGULAR_CONTRIBUTION -> "Regular Contributions";
            case SPECIAL_CONTRIBUTION -> "Special Contributions";
            case LOAN_DISBURSEMENT -> "Loan Disbursements";
            case LOAN_PRINCIPAL_REPAYMENT -> "Loan Principal Repayments";
            case LOAN_INTEREST_PAYMENT -> "Loan Interest Income";
            case FINE_PAYMENT -> "Fine / Penalty Income";
            case SOCIAL_CONTRIBUTION -> "Social Fund Contributions";
            case SOCIAL_DISBURSEMENT -> "Social Fund Disbursements";
            case INVESTMENT_OUTFLOW -> "Investments Made";
            case INVESTMENT_CAPITAL_RETURN -> "Investment Capital Returns";
            case INVESTMENT_PROFIT -> "Investment Profit";
            case OTHER_INCOME -> "Other Income";
            case GENERAL_EXPENSE -> "General Expenses";
            case INTEREST_EXPENSE -> "Interest Expense";
            case MEMBER_PAYOUT -> "Member Payouts";
            case SHARE_PURCHASE -> "Additional Share Purchases";
            case ADJUSTMENT -> "Adjustments";
            case REVERSAL -> "Reversals";
            case MEMBER_CREDIT_RECEIVED -> "Member Credit Held (not group funds)";
            case MEMBER_CREDIT_APPLIED -> "Member Credit Applied";
            case MEMBER_CREDIT_REVERSED -> "Member Credit Reversed";
        };
    }
}
