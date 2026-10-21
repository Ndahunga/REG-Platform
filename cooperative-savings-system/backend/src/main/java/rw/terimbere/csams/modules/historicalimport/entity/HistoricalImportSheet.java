package rw.terimbere.csams.modules.historicalimport.entity;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HistoricalImportSheet {
    MEMBERS(
            "Members",
            List.of(
                    "Username",
                    "First Name",
                    "Last Name",
                    "Email",
                    "Phone",
                    "National ID",
                    "Membership Date",
                    "Share Count", // opening/historical owned shares, not a period purchase
                    "Membership Status",
                    "Role"),
            List.of(
                    "hist_jane",
                    "Jane",
                    "Uwase",
                    "jane.uwase@example.com",
                    "0781234567",
                    "",
                    "2022-01-15",
                    "1",
                    "ACTIVE",
                    "MEMBER")),
    CONTRIBUTIONS(
            "Contributions",
            List.of(
                    "Username",
                    "Year",
                    "Month",
                    "Expected Amount",
                    "Paid Amount",
                    "Payment Date",
                    "Reference",
                    "Notes"),
            List.of("hist_jane", "2022", "3", "10000", "10000", "2022-03-05", "REF-2022-03", "")),
    SPECIAL_CAMPAIGNS(
            "SpecialCampaigns",
            List.of(
                    "Campaign Code",
                    "Name",
                    "Purpose",
                    "Suggested Amount",
                    "Target Amount",
                    "Start Date",
                    "End Date",
                    "Status"),
            List.of("CAMP-2022-01", "Emergency fund", "School fees", "5000", "200000", "2022-06-01", "2022-08-31", "CLOSED")),
    SPECIAL_CONTRIBUTIONS(
            "SpecialContributions",
            List.of("Username", "Campaign Code", "Amount", "Contribution Date", "Reference", "Notes"),
            List.of("hist_jane", "CAMP-2022-01", "5000", "2022-06-15", "SPEC-001", "")),
    SOCIAL_CONTRIBUTIONS(
            "SocialContributions",
            List.of("Username", "Amount", "Contribution Date", "Reference", "Notes"),
            List.of("hist_jane", "2000", "2022-04-10", "SOC-001", "")),
    SOCIAL_DISBURSEMENTS(
            "SocialDisbursements",
            List.of("Username", "Amount", "Disbursement Date", "Reason", "Notes"),
            List.of("hist_jane", "15000", "2022-09-01", "Funeral support", "")),
    LOANS(
            "Loans",
            List.of(
                    "Loan Code",
                    "Username",
                    "Requested Amount",
                    "Approved Amount",
                    "Principal Amount",
                    "Interest Rate Percent",
                    "Interest Type",
                    "Interest Amount",
                    "Term Months",
                    "Outstanding Principal",
                    "Outstanding Interest",
                    "Request Date",
                    "Approval Date",
                    "Disbursement Date",
                    "Due Date",
                    "Status",
                    "Purpose"),
            List.of(
                    "L-2022-001",
                    "hist_jane",
                    "100000",
                    "100000",
                    "100000",
                    "10",
                    "FLAT",
                    "10000",
                    "6",
                    "0",
                    "0",
                    "2022-05-01",
                    "2022-05-02",
                    "2022-05-10",
                    "2022-11-10",
                    "CLOSED",
                    "Business")),
    LOAN_REPAYMENTS(
            "LoanRepayments",
            List.of(
                    "Loan Code",
                    "Username",
                    "Payment Date",
                    "Amount Total",
                    "Principal Portion",
                    "Interest Portion",
                    "Reference",
                    "Notes"),
            List.of("L-2022-001", "hist_jane", "2022-08-10", "110000", "100000", "10000", "REP-001", "")),
    FINES(
            "Fines",
            List.of(
                    "Fine Code",
                    "Username",
                    "Fine Type",
                    "Total Amount",
                    "Paid Amount",
                    "Issued Date",
                    "Due Date",
                    "Status",
                    "Reason"),
            List.of("F-2022-001", "hist_jane", "MANUAL", "2000", "2000", "2022-07-01", "2022-07-15", "PAID", "Late meeting")),
    FINE_PAYMENTS(
            "FinePayments",
            List.of("Fine Code", "Username", "Amount", "Payment Date", "Reference", "Notes"),
            List.of("F-2022-001", "hist_jane", "2000", "2022-07-10", "FINE-PAY-001", "")),
    INVESTMENTS(
            "Investments",
            List.of(
                    "Investment Code",
                    "Name",
                    "Amount",
                    "Investment Date",
                    "Expected Return Amount",
                    "Expected Return Date",
                    "Remaining Capital",
                    "Total Capital Returned",
                    "Total Profit Returned",
                    "Status",
                    "Description"),
            List.of(
                    "INV-2022-01",
                    "Maize trade",
                    "50000",
                    "2022-02-01",
                    "10000",
                    "2022-12-01",
                    "0",
                    "50000",
                    "8000",
                    "COMPLETED",
                    "")),
    INVESTMENT_RETURNS(
            "InvestmentReturns",
            List.of(
                    "Investment Code",
                    "Return Date",
                    "Capital Portion",
                    "Profit Portion",
                    "Amount Total",
                    "Reference",
                    "Notes"),
            List.of("INV-2022-01", "2022-12-01", "50000", "8000", "58000", "INV-RET-001", "")),
    INCOME(
            "Income",
            List.of("Transaction Date", "Amount", "Category", "Reference", "Description", "Notes"),
            List.of("2022-10-01", "3000", "OTHER_INCOME", "INC-001", "Donation", "")),
    EXPENSES(
            "Expenses",
            List.of("Transaction Date", "Amount", "Category", "Reference", "Description", "Notes"),
            List.of("2022-10-15", "1500", "GENERAL_EXPENSE", "EXP-001", "Stationery", "")),
    PAYOUTS(
            "Payouts",
            List.of(
                    "Payout Code",
                    "Name",
                    "Period From",
                    "Period To",
                    "Payout Date",
                    "Pool Amount",
                    "Eligible Contributions",
                    "Status",
                    "Notes"),
            List.of(
                    "PAY-2022-01",
                    "Year-end share-out",
                    "2022-01-01",
                    "2022-12-31",
                    "2023-01-15",
                    "10000",
                    "10000",
                    "PAID",
                    "")),
    PAYOUT_LINES(
            "PayoutLines",
            List.of("Payout Code", "Username", "Eligible Amount", "Percentage", "Payout Amount", "Status"),
            List.of("PAY-2022-01", "hist_jane", "10000", "100", "10000", "PAID"));

    public static final String INSTRUCTIONS_SHEET = "Instructions";

    private final String sheetName;
    private final List<String> headers;
    private final List<String> sampleRow;

    public static Optional<HistoricalImportSheet> fromSheetName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.trim();
        return Arrays.stream(values())
                .filter(s -> s.sheetName.equalsIgnoreCase(normalized))
                .findFirst();
    }

    public static boolean isKnownSheetName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim();
        if (INSTRUCTIONS_SHEET.equalsIgnoreCase(normalized)) {
            return true;
        }
        return fromSheetName(normalized).isPresent();
    }

    public String header(int index) {
        return headers.get(index);
    }

    public static String normalizeHeader(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }
}
