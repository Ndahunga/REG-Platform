package rw.terimbere.csams.modules.report.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReportType {
    MEMBERS("Members"),
    CONTRIBUTIONS("Contributions"),
    SPECIAL_CONTRIBUTIONS("Special Contributions"),
    LOANS("Loans"),
    REPAYMENTS("Loan Repayments"),
    FINES("Fines"),
    FINE_PAYMENTS("Fine Payments"),
    SOCIAL_FUND("Social Fund"),
    INVESTMENTS("Investments"),
    INCOME("Income"),
    EXPENSES("Expenses"),
    PAYOUTS("Payouts"),
    FINANCIAL_LEDGER("Financial Ledger"),
    AUDIT_LOGS("Audit Logs"),
    FULL_FINANCIAL("Full Financial Report"),
    MEMBER_STATEMENT("Member Statement"),
    RECONCILIATION("Reconciliation"),
    COLLECTION_BY_MONTH("Collection by Month"),
    ROTATION_SCHEDULE("Rotation Schedule"),
    MEMBER_POSITIONS("Member Positions"),
    ARREARS_AND_PENALTIES("Arrears and Penalties"),
    LOAN_BOOK("Loan Book"),
    INCOME_AND_EXPENSES("Income and Expenses"),
    YEAR_END_CLOSE("Year-end Close");

    private final String label;

    public boolean requiresAuditRead() {
        return this == AUDIT_LOGS;
    }
}
