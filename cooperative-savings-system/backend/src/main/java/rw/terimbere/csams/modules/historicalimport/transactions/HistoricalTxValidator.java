package rw.terimbere.csams.modules.historicalimport.transactions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.historicalimport.service.WorkbookCellReader;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTxWorkbook.RawRow;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Business validation of a historical workbook. It reads the database through the supplied {@link Context} only, so the
 * same code runs for the dry run and again, on the stored rows, just before posting.
 *
 * <p>Nothing is guessed: an unknown row type, an unknown member, a date that overlaps live records, a duplicate, a loan
 * that is not fully repaid within the history, or a missing opening or declared closing balance is an explicit error on
 * the row (or on the sheet) that explains it.
 */
public final class HistoricalTxValidator {

    public static final int FIRST_YEAR = 2000;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    public record Issue(String code, String field, String message) {}

    /** A row with its validation result and its typed values. */
    public static final class Checked {
        public final RawRow raw;
        public final List<Issue> issues = new ArrayList<>();
        public String rowType;
        public HistoricalTxType txType;
        public String username;
        public UUID memberUserId;
        public LocalDate date;
        public BigDecimal amount;
        public String reference;
        public Integer year;
        public Integer month;
        public String loanReference;
        public String model;
        public String description;
        public String settingKey;
        public String settingValue;
        public String fingerprint;

        Checked(RawRow raw) {
            this.raw = raw;
        }

        public boolean valid() {
            return issues.isEmpty();
        }

        void add(String code, String field, String message) {
            issues.add(new Issue(code, field, message));
        }
    }

    /** What the validator needs to know about the scheme. */
    public record Context(
            UUID cooperativeId,
            String currency,
            SchemeOperatingModel model,
            Map<String, UUID> memberIdsByUsername,
            Map<UUID, Integer> shareCounts,
            Set<String> existingContributionKeys,
            LocalDate earliestLiveDate,
            LocalDate today,
            Function<Collection<String>, Set<String>> postedLookup) {}

    public record YearTotals(int year, BigDecimal credits, BigDecimal debits, BigDecimal net, int rows) {}

    public record TypeTotals(String type, int rows, BigDecimal amount) {}

    public record LoanSummary(
            String reference, String username, BigDecimal disbursed, BigDecimal principalRepaid, BigDecimal interestPaid, boolean closed) {}

    /** @param amount contributions with no rotation attribution (never payout-eligible); {@code byModel} says what was stated */
    public record Unresolved(BigDecimal amount, int rows, Map<String, BigDecimal> byModel) {}

    public record Reconciliation(
            String currency,
            BigDecimal opening,
            BigDecimal declaredClosing,
            BigDecimal computedClosing,
            BigDecimal variance,
            boolean balanced,
            LocalDate historyEnd,
            List<YearTotals> byYear,
            List<TypeTotals> byType,
            List<LoanSummary> loans,
            Unresolved unresolvedAttribution) {}

    public record Result(
            List<Checked> members,
            List<Checked> transactions,
            List<Issue> sheetIssues,
            List<String> warnings,
            Reconciliation reconciliation) {

        public int errorCount() {
            int count = sheetIssues.size();
            for (Checked c : members) {
                count += c.issues.size();
            }
            for (Checked c : transactions) {
                count += c.issues.size();
            }
            return count;
        }

        public int errorRows() {
            return (int) (members.stream().filter(c -> !c.valid()).count()
                    + transactions.stream().filter(c -> !c.valid()).count());
        }
    }

    private HistoricalTxValidator() {}

    public static Result validate(List<RawRow> memberRows, List<RawRow> txRows, Context ctx) {
        List<Issue> sheetIssues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ------------------------------------------------------------ sheet 1: roster and settings
        List<Checked> members = new ArrayList<>();
        Map<String, Checked> settings = new LinkedHashMap<>();
        Set<String> listedUsers = new HashSet<>();
        for (RawRow raw : memberRows) {
            Checked c = new Checked(raw);
            members.add(c);
            c.rowType = raw.get("Row Type").toUpperCase(Locale.ROOT);
            switch (c.rowType) {
                case "MEMBER" -> member(c, ctx, listedUsers, warnings);
                case "SETTING" -> setting(c, ctx, settings);
                default -> c.add("UNSUPPORTED_ROW_TYPE", "Row Type", "Row Type must be MEMBER or SETTING on this sheet.");
            }
        }
        BigDecimal opening = settingAmount(settings, "OPENING_BALANCE");
        BigDecimal declared = settingAmount(settings, "DECLARED_CLOSING_BALANCE");
        if (!settings.containsKey("OPENING_BALANCE")) {
            sheetIssues.add(new Issue("MISSING_SETTING", "OPENING_BALANCE", "The OPENING_BALANCE setting is required so the history can be reconciled."));
        }
        if (!settings.containsKey("DECLARED_CLOSING_BALANCE")) {
            sheetIssues.add(new Issue("MISSING_SETTING", "DECLARED_CLOSING_BALANCE", "The DECLARED_CLOSING_BALANCE setting is required so the history can be reconciled."));
        }
        LocalDate endDate = null;
        Checked endSetting = settings.get("HISTORY_END_DATE");
        if (endSetting != null && endSetting.valid()) {
            endDate = WorkbookCellReader.parseDate(endSetting.settingValue);
        }

        // ------------------------------------------------------------ sheet 2: events
        List<Checked> txs = new ArrayList<>();
        Set<String> seenFingerprints = new HashSet<>();
        Set<String> seenContributionKeys = new HashSet<>();
        for (RawRow raw : txRows) {
            Checked c = new Checked(raw);
            txs.add(c);
            transaction(c, ctx, endDate, listedUsers, memberRows.isEmpty(), seenFingerprints, seenContributionKeys, warnings);
        }
        loanRules(txs);
        Set<String> candidates = new HashSet<>();
        for (Checked c : txs) {
            if (c.valid() && c.fingerprint != null) {
                candidates.add(c.fingerprint);
            }
        }
        if (!candidates.isEmpty()) {
            Set<String> already = ctx.postedLookup().apply(candidates);
            for (Checked c : txs) {
                if (c.valid() && already.contains(c.fingerprint)) {
                    c.add("DUPLICATE_PREVIOUSLY_IMPORTED", "Row", "This exact event was already imported in an earlier batch.");
                }
            }
        }
        if (txRows.isEmpty()) {
            sheetIssues.add(new Issue("NO_TRANSACTIONS", "Historical Transactions", "The transactions sheet has no rows."));
        }

        Reconciliation reconciliation = reconcile(txs, opening, declared, endDate, ctx);
        return new Result(members, txs, sheetIssues, warnings, reconciliation);
    }

    // ------------------------------------------------------------------ sheet 1

    private static void member(Checked c, Context ctx, Set<String> listed, List<String> warnings) {
        c.username = lower(c.raw.get("Username"));
        if (c.username.isEmpty()) {
            c.add("USERNAME_REQUIRED", "Username", "A MEMBER row needs the username of an existing member.");
            return;
        }
        c.memberUserId = ctx.memberIdsByUsername().get(c.username);
        if (c.memberUserId == null) {
            c.add("MEMBER_NOT_FOUND", "Username", "\"" + c.username + "\" is not a member of this Saving Scheme. Members are never created by this import: add them first.");
            return;
        }
        if (!listed.add(c.username)) {
            c.add("DUPLICATE_IN_FILE", "Username", "This member is listed more than once.");
        }
        String shares = c.raw.get("Share Count");
        if (StringUtils.hasText(shares)) {
            try {
                int declared = Integer.parseInt(shares.replace(".0", ""));
                Integer current = ctx.shareCounts().get(c.memberUserId);
                if (declared < 0) {
                    c.add("INVALID_SHARE_COUNT", "Share Count", "Share Count cannot be negative.");
                } else if (current != null && current != declared) {
                    warnings.add("Member " + c.username + ": the workbook says " + declared + " shares, the scheme currently has "
                            + current + ". The import never changes share counts.");
                }
            } catch (NumberFormatException e) {
                c.add("INVALID_SHARE_COUNT", "Share Count", "Share Count must be a whole number.");
            }
        }
    }

    private static void setting(Checked c, Context ctx, Map<String, Checked> settings) {
        c.settingKey = c.raw.get("Setting").toUpperCase(Locale.ROOT);
        c.settingValue = c.raw.get("Value");
        switch (c.settingKey) {
            case "OPENING_BALANCE", "DECLARED_CLOSING_BALANCE" -> {
                BigDecimal value = amount(c.settingValue);
                if (value == null || value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0) {
                    c.add("INVALID_AMOUNT", "Value", c.settingKey + " must be an amount of zero or more.");
                }
            }
            case "HISTORY_END_DATE" -> {
                LocalDate date = WorkbookCellReader.parseDate(c.settingValue);
                if (date == null) {
                    c.add("INVALID_DATE", "Value", "HISTORY_END_DATE must be a date (yyyy-mm-dd).");
                } else if (date.isAfter(ctx.today())) {
                    c.add("DATE_IN_FUTURE", "Value", "HISTORY_END_DATE cannot be in the future.");
                }
            }
            case "CURRENCY" -> {
                if (!c.settingValue.equalsIgnoreCase(ctx.currency())) {
                    c.add("CURRENCY_MISMATCH", "Value", "The history is in " + c.settingValue + " but this Saving Scheme uses " + ctx.currency() + ".");
                }
            }
            case "OPERATING_MODEL" -> {
                if (ctx.model() == null || !c.settingValue.equalsIgnoreCase(ctx.model().name())) {
                    c.add("OPERATING_MODEL_MISMATCH", "Value", "The workbook says " + c.settingValue + "; this Saving Scheme is "
                            + (ctx.model() == null ? "not classified" : ctx.model().name()) + ".");
                }
            }
            default -> {
                c.add("UNSUPPORTED_SETTING", "Setting", "Unknown setting \"" + c.settingKey
                        + "\". Supported: OPENING_BALANCE, DECLARED_CLOSING_BALANCE, HISTORY_END_DATE, CURRENCY, OPERATING_MODEL.");
                return;
            }
        }
        if (settings.putIfAbsent(c.settingKey, c) != null) {
            c.add("DUPLICATE_SETTING", "Setting", c.settingKey + " is set more than once.");
        }
    }

    private static BigDecimal settingAmount(Map<String, Checked> settings, String key) {
        Checked c = settings.get(key);
        return c == null || !c.valid() ? null : amount(c.settingValue);
    }

    // ------------------------------------------------------------------ sheet 2

    private static void transaction(
            Checked c,
            Context ctx,
            LocalDate endDate,
            Set<String> listedUsers,
            boolean rosterEmpty,
            Set<String> seenFingerprints,
            Set<String> seenContributionKeys,
            List<String> warnings) {
        c.rowType = c.raw.get("Row Type").toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        c.txType = HistoricalTxType.parse(c.rowType).orElse(null);
        if (c.txType == null) {
            c.add("UNSUPPORTED_ROW_TYPE", "Row Type", c.rowType.isEmpty()
                    ? "Row Type is required. Supported: " + String.join(", ", HistoricalTxType.names()) + "."
                    : "\"" + c.rowType + "\" is not a supported historical event. Supported: "
                            + String.join(", ", HistoricalTxType.names()) + ".");
            return;
        }
        if (!c.txType.supported()) {
            c.add("UNSUPPORTED_ROW_TYPE", "Row Type", c.txType == HistoricalTxType.MEMBER_PAYOUT
                    ? "MEMBER_PAYOUT rows cannot be imported: a payout needs a rotation period and recipient that a dated list does not contain, so it would exist only in the ledger. Supported: "
                            + String.join(", ", HistoricalTxType.names()) + "."
                    : c.rowType + " rows cannot be imported: a loan needs an instalment schedule and an interest and penalty breakdown that a dated list does not contain, so it would exist only in the ledger. Use the opening-balance loan migration for loans. Supported: "
                            + String.join(", ", HistoricalTxType.names()) + ".");
            return;
        }
        // date
        c.date = WorkbookCellReader.parseDate(c.raw.get("Date"));
        if (c.date == null) {
            c.add("INVALID_DATE", "Date", "Date must be a real date (yyyy-mm-dd).");
        } else if (c.date.isAfter(ctx.today())) {
            c.add("DATE_IN_FUTURE", "Date", "Date cannot be in the future.");
        } else if (c.date.getYear() < FIRST_YEAR) {
            c.add("DATE_TOO_OLD", "Date", "Dates before " + FIRST_YEAR + " are not accepted.");
        } else if (endDate != null && c.date.isAfter(endDate)) {
            c.add("DATE_AFTER_HISTORY_END", "Date", "This date is after HISTORY_END_DATE (" + endDate + ").");
        } else if (ctx.earliestLiveDate() != null && !c.date.isBefore(ctx.earliestLiveDate())) {
            c.add("DATE_OVERLAPS_LIVE_RECORDS", "Date", "This date is on or after the first live record of the scheme ("
                    + ctx.earliestLiveDate() + "). History must end before live records begin, or it would be counted twice.");
        }
        // amount
        c.amount = amount(c.raw.get("Amount"));
        if (c.amount == null || c.amount.signum() <= 0) {
            c.add("INVALID_AMOUNT", "Amount", "Amount must be a positive number.");
            c.amount = null;
        } else if (c.amount.compareTo(MAX_AMOUNT) > 0) {
            c.add("INVALID_AMOUNT", "Amount", "Amount is too large.");
        } else if (c.amount.stripTrailingZeros().scale() > 4) {
            c.add("INVALID_AMOUNT", "Amount", "Amount can have at most four decimals.");
        }
        // member
        c.username = lower(c.raw.get("Member Username"));
        if (c.txType.needsMember()) {
            if (c.username.isEmpty()) {
                c.add("MEMBER_REQUIRED", "Member Username", c.rowType + " rows need a member.");
            } else {
                c.memberUserId = ctx.memberIdsByUsername().get(c.username);
                if (c.memberUserId == null) {
                    c.add("MEMBER_NOT_FOUND", "Member Username", "\"" + c.username + "\" is not a member of this Saving Scheme.");
                } else if (!rosterEmpty && !listedUsers.contains(c.username)) {
                    warnings.add("Row " + c.raw.rowNumber() + ": " + c.username + " is not listed on the Members sheet.");
                }
            }
        } else if (!c.username.isEmpty()) {
            c.memberUserId = ctx.memberIdsByUsername().get(c.username);
            if (c.memberUserId == null) {
                c.add("MEMBER_NOT_FOUND", "Member Username", "\"" + c.username + "\" is not a member of this Saving Scheme.");
            }
        }
        // reference
        c.reference = c.raw.get("Original Reference");
        if (c.reference.length() > 128) {
            c.add("REFERENCE_TOO_LONG", "Original Reference", "Original Reference is at most 128 characters.");
        }
        if (c.reference.isEmpty() && c.txType != HistoricalTxType.CONTRIBUTION) {
            c.add("REFERENCE_REQUIRED", "Original Reference", "Original Reference is required for " + c.rowType + " rows.");
        }
        c.description = c.raw.get("Description");
        if (c.description.length() > 500) {
            c.add("DESCRIPTION_TOO_LONG", "Description", "Description is at most 500 characters.");
        }
        // type-specific
        c.loanReference = c.raw.get("Loan Reference");
        if (c.txType.isLoanEvent()) {
            if (c.loanReference.isEmpty()) {
                c.add("LOAN_REFERENCE_REQUIRED", "Loan Reference", "Loan rows need a Loan Reference that groups the loan.");
            } else if (c.loanReference.length() > 64) {
                c.add("REFERENCE_TOO_LONG", "Loan Reference", "Loan Reference is at most 64 characters.");
            }
        } else if (!c.loanReference.isEmpty()) {
            c.add("LOAN_REFERENCE_NOT_APPLICABLE", "Loan Reference", "Only loan rows carry a Loan Reference.");
        }
        c.model = c.raw.get("Operating Model At The Time").toUpperCase(Locale.ROOT);
        if (c.txType == HistoricalTxType.CONTRIBUTION) {
            contribution(c, ctx, seenContributionKeys);
        } else {
            if (!c.raw.get("Period Year").isEmpty() || !c.raw.get("Period Month").isEmpty()) {
                c.add("PERIOD_NOT_APPLICABLE", "Period Year", "Only CONTRIBUTION rows carry a period.");
            }
            if (!c.model.isEmpty()) {
                c.add("MODEL_NOT_APPLICABLE", "Operating Model At The Time", "Only CONTRIBUTION rows carry an operating model.");
            }
        }
        // duplicates inside the file
        if (c.valid()) {
            c.fingerprint = fingerprint(c);
            if (!seenFingerprints.add(c.fingerprint)) {
                c.add("DUPLICATE_IN_FILE", "Row", "This exact event appears more than once in the file.");
            }
        }
    }

    private static void contribution(Checked c, Context ctx, Set<String> seenKeys) {
        Integer year = integer(c.raw.get("Period Year"));
        Integer month = integer(c.raw.get("Period Month"));
        if (year == null || year < FIRST_YEAR || year > ctx.today().getYear()) {
            c.add("INVALID_PERIOD", "Period Year", "Period Year must be between " + FIRST_YEAR + " and " + ctx.today().getYear() + ".");
        }
        if (month == null || month < 1 || month > 12) {
            c.add("INVALID_PERIOD", "Period Month", "Period Month must be 1 to 12.");
        }
        c.year = year;
        c.month = month;
        if (!c.model.isEmpty()) {
            try {
                SchemeOperatingModel.valueOf(c.model);
            } catch (IllegalArgumentException e) {
                c.add("INVALID_MODEL", "Operating Model At The Time", "Use ACCUMULATING, ROTATING or HYBRID, or leave it blank when unknown.");
            }
        }
        if (c.memberUserId != null && year != null && month != null && month >= 1 && month <= 12) {
            String key = c.memberUserId + ":" + year + ":" + month;
            if (ctx.existingContributionKeys().contains(key)) {
                c.add("DUPLICATE_EXISTING_CONTRIBUTION", "Period Year",
                        "This member already has a contribution recorded for " + year + "-" + String.format("%02d", month)
                                + ". Historical contributions never merge into or replace existing records.");
            } else if (!seenKeys.add(key)) {
                c.add("DUPLICATE_IN_FILE", "Period Year", "This member and period appear more than once. Enter one row per member and period.");
            }
        }
    }

    /**
     * Retained from the earlier loan support: loan rows are now refused before they reach this point (see
     * {@link HistoricalTxType#supported()}), so it only ever sees rows of supported types.
     */
    private static void loanRules(List<Checked> txs) {
        Map<String, List<Checked>> byLoan = new TreeMap<>();
        for (Checked c : txs) {
            if (c.txType != null && c.txType.supported() && c.txType.isLoanEvent() && !c.loanReference.isEmpty()) {
                byLoan.computeIfAbsent(c.loanReference.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
            }
        }
        for (List<Checked> rows : byLoan.values()) {
            List<Checked> disbursements = rows.stream().filter(r -> r.txType == HistoricalTxType.LOAN_DISBURSEMENT).toList();
            if (disbursements.size() != 1) {
                for (Checked r : rows) {
                    r.add(disbursements.isEmpty() ? "LOAN_DISBURSEMENT_MISSING" : "LOAN_DISBURSED_TWICE", "Loan Reference",
                            disbursements.isEmpty()
                                    ? "This loan has no LOAN_DISBURSEMENT row in the file."
                                    : "A loan reference needs exactly one LOAN_DISBURSEMENT row.");
                }
                continue;
            }
            Checked disbursement = disbursements.get(0);
            if (disbursement.amount == null) {
                continue;
            }
            BigDecimal principal = BigDecimal.ZERO;
            for (Checked r : rows) {
                if (r == disbursement) {
                    continue;
                }
                if (r.memberUserId != null && disbursement.memberUserId != null && !r.memberUserId.equals(disbursement.memberUserId)) {
                    r.add("LOAN_MEMBER_MISMATCH", "Member Username", "Every row of a loan must name the borrower of its disbursement.");
                }
                if (r.date != null && disbursement.date != null && r.date.isBefore(disbursement.date)) {
                    r.add("REPAYMENT_BEFORE_DISBURSEMENT", "Date", "A repayment cannot be dated before the loan was disbursed.");
                }
                if (r.txType == HistoricalTxType.LOAN_PRINCIPAL_REPAYMENT && r.amount != null) {
                    principal = principal.add(r.amount);
                }
            }
            int comparison = principal.compareTo(disbursement.amount);
            if (comparison > 0) {
                for (Checked r : rows) {
                    if (r.txType == HistoricalTxType.LOAN_PRINCIPAL_REPAYMENT) {
                        r.add("LOAN_OVERPAID", "Amount", "Principal repayments exceed the amount disbursed.");
                    }
                }
            } else if (comparison < 0) {
                disbursement.add("LOAN_NOT_CLOSED", "Loan Reference",
                        "This loan still has " + MoneyUtils.scaleForStorage(disbursement.amount.subtract(principal)).toPlainString()
                                + " of principal outstanding at the end of the history. Loans that are still open must be entered through the "
                                + "opening-balance loan migration so the receivable is recorded; they cannot be imported as transactions.");
            }
        }
    }

    // ------------------------------------------------------------------ reconciliation

    private static Reconciliation reconcile(
            List<Checked> txs, BigDecimal opening, BigDecimal declared, LocalDate endDate, Context ctx) {
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        Map<Integer, BigDecimal[]> byYear = new TreeMap<>();
        Map<String, Object[]> byType = new TreeMap<>();
        Map<String, BigDecimal[]> loans = new TreeMap<>();
        Map<String, String> loanOwner = new HashMap<>();
        BigDecimal unresolved = BigDecimal.ZERO;
        int unresolvedRows = 0;
        Map<String, BigDecimal> unresolvedByModel = new TreeMap<>();
        LocalDate latest = null;
        for (Checked c : txs) {
            if (!c.valid() || c.txType == null || c.amount == null || c.date == null) {
                continue;
            }
            BigDecimal amount = MoneyUtils.scaleForStorage(c.amount);
            BigDecimal[] year = byYear.computeIfAbsent(c.date.getYear(), y -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            if (c.txType.credit()) {
                credits = credits.add(amount);
                year[0] = year[0].add(amount);
            } else {
                debits = debits.add(amount);
                year[1] = year[1].add(amount);
            }
            year[2] = year[2].add(BigDecimal.ONE);
            Object[] type = byType.computeIfAbsent(c.rowType, t -> new Object[] {0, BigDecimal.ZERO});
            type[0] = (Integer) type[0] + 1;
            type[1] = ((BigDecimal) type[1]).add(amount);
            if (latest == null || c.date.isAfter(latest)) {
                latest = c.date;
            }
            if (c.txType.isLoanEvent()) {
                String key = c.loanReference.toLowerCase(Locale.ROOT);
                BigDecimal[] loan = loans.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                loanOwner.putIfAbsent(key, c.loanReference + "|" + c.username);
                switch (c.txType) {
                    case LOAN_DISBURSEMENT -> loan[0] = loan[0].add(amount);
                    case LOAN_PRINCIPAL_REPAYMENT -> loan[1] = loan[1].add(amount);
                    default -> loan[2] = loan[2].add(amount);
                }
            }
            if (c.txType == HistoricalTxType.CONTRIBUTION && !"ACCUMULATING".equals(c.model)) {
                // no original-period rotation attribution exists for history, so none of it is payout-eligible
                unresolved = unresolved.add(amount);
                unresolvedRows++;
                unresolvedByModel.merge(c.model.isEmpty() ? "UNSTATED" : c.model, amount, BigDecimal::add);
            }
        }
        BigDecimal computed = null;
        BigDecimal variance = null;
        if (opening != null) {
            computed = MoneyUtils.scaleForStorage(opening.add(credits).subtract(debits));
            if (declared != null) {
                variance = MoneyUtils.scaleForStorage(declared.subtract(computed));
            }
        }
        List<YearTotals> years = new ArrayList<>();
        byYear.forEach((year, v) -> years.add(new YearTotals(year, MoneyUtils.scaleForStorage(v[0]), MoneyUtils.scaleForStorage(v[1]),
                MoneyUtils.scaleForStorage(v[0].subtract(v[1])), v[2].intValue())));
        List<TypeTotals> types = new ArrayList<>();
        byType.forEach((name, v) -> types.add(new TypeTotals(name, (Integer) v[0], MoneyUtils.scaleForStorage((BigDecimal) v[1]))));
        List<LoanSummary> loanSummaries = new ArrayList<>();
        loans.forEach((key, v) -> {
            String[] owner = loanOwner.get(key).split("\\|", 2);
            loanSummaries.add(new LoanSummary(owner[0], owner.length > 1 ? owner[1] : "", MoneyUtils.scaleForStorage(v[0]),
                    MoneyUtils.scaleForStorage(v[1]), MoneyUtils.scaleForStorage(v[2]), v[0].signum() > 0 && v[0].compareTo(v[1]) == 0));
        });
        Map<String, BigDecimal> models = new LinkedHashMap<>();
        unresolvedByModel.forEach((k, v) -> models.put(k, MoneyUtils.scaleForStorage(v)));
        return new Reconciliation(
                ctx.currency(),
                opening == null ? null : MoneyUtils.scaleForStorage(opening),
                declared == null ? null : MoneyUtils.scaleForStorage(declared),
                computed,
                variance,
                variance != null && variance.signum() == 0,
                endDate != null ? endDate : latest,
                years,
                types,
                loanSummaries,
                new Unresolved(MoneyUtils.scaleForStorage(unresolved), unresolvedRows, models));
    }

    // ------------------------------------------------------------------ helpers

    /** Identifies one event independent of row order and formatting. */
    static String fingerprint(Checked c) {
        String basis = String.join("|",
                c.txType.name(),
                c.username == null ? "" : c.username,
                String.valueOf(c.date),
                c.amount == null ? "" : c.amount.stripTrailingZeros().toPlainString(),
                c.reference == null ? "" : c.reference.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""),
                c.year == null ? "" : String.valueOf(c.year),
                c.month == null ? "" : String.valueOf(c.month),
                c.loanReference == null ? "" : c.loanReference.toLowerCase(Locale.ROOT));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal amount(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
