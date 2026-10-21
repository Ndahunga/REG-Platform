package rw.terimbere.csams.modules.historicalimport.entity;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The three sheets of the opening-balance (cutover) migration workbook. The workbook has exactly
 * these sheets, in this order. Legacy 16-sheet workbooks are described by {@link HistoricalImportSheet}
 * and are no longer accepted for new uploads.
 */
public enum HistoricalMigrationSheet {
    MEMBER_REGISTRATION(
            "Member Registration",
            List.of(
                    Fields.FULL_NAME,
                    Fields.USERNAME,
                    Fields.EMAIL,
                    Fields.PHONE,
                    Fields.NATIONAL_ID,
                    Fields.TOTAL_CONTRIBUTIONS)),
    SHARE_CAPITAL_REGISTRATION(
            "Share Capital Registration", List.of(Fields.USERNAME, Fields.SHARE_COUNT, Fields.SHARE_CAPITAL)),
    LOAN_REGISTRATION(
            "Loan Registration",
            List.of(
                    Fields.USERNAME,
                    Fields.REPAYMENT_TYPE,
                    Fields.LOAN_AMOUNT,
                    Fields.INTEREST_RATE,
                    Fields.ISSUED_DATE,
                    Fields.TERM,
                    Fields.AMOUNT_PAID,
                    Fields.OUTSTANDING_PRINCIPAL,
                    Fields.LAST_PAYMENT_DATE));

    /** Exact column headers of the template. */
    public static final class Fields {
        public static final String FULL_NAME = "Full Name";
        public static final String USERNAME = "Username";
        public static final String EMAIL = "Email";
        public static final String PHONE = "Phone Number";
        public static final String NATIONAL_ID = "National ID (Optional)";
        public static final String TOTAL_CONTRIBUTIONS = "Total Contributions to Date (RWF)";
        public static final String SHARE_COUNT = "Share Count";
        public static final String SHARE_CAPITAL = "Share Capital Contributed to Date (RWF)";
        public static final String REPAYMENT_TYPE = "Repayment Type";
        public static final String LOAN_AMOUNT = "Loan Amount (RWF)";
        public static final String INTEREST_RATE = "Interest Rate (%)";
        public static final String ISSUED_DATE = "Loan Issued Date";
        public static final String TERM = "Loan Term (Months)";
        public static final String AMOUNT_PAID = "Amount Paid to Date (RWF)";
        public static final String OUTSTANDING_PRINCIPAL = "Outstanding Principal (RWF)";
        public static final String LAST_PAYMENT_DATE = "Last Payment Date";

        private Fields() {}
    }

    private final String sheetName;
    private final List<String> headers;

    HistoricalMigrationSheet(String sheetName, List<String> headers) {
        this.sheetName = sheetName;
        this.headers = headers;
    }

    public String getSheetName() {
        return sheetName;
    }

    public List<String> getHeaders() {
        return headers;
    }

    public static Optional<HistoricalMigrationSheet> fromSheetName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.trim();
        return Arrays.stream(values())
                .filter(s -> s.sheetName.equalsIgnoreCase(normalized))
                .findFirst();
    }

    public static String normalizeHeader(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }
}
