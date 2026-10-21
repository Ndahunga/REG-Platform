package rw.terimbere.csams.modules.historicalimport.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportError;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalMigrationSummary;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet.Fields;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.modules.loan.repository.LoanSettingsRepository;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator.RepaymentType;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;

/**
 * Validates the three migration sheets against the workbook itself and the live platform data. It never
 * writes. Row-level errors name the sheet, row, column and a human message.
 */
@Component
@RequiredArgsConstructor
class MigrationValidator {

    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
    private static final BigDecimal MAX_RATE = new BigDecimal("1000");
    private static final int MAX_TERM_MONTHS = 600;
    private static final int MAX_NAME_PART = 128;

    private final UserRepository userRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final MemberOpeningBalanceRepository openingBalanceRepository;
    private final LoanSettingsRepository loanSettingsRepository;
    private final ContributionRepository contributionRepository;
    private final SharePurchaseRepository sharePurchaseRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository loanRepository;
    private final HistoricalImportRepository historicalImportRepository;

    /** Ledger types that never touch Available Group Funds. */
    private static final Set<LedgerTransactionType> NOT_FUND_ACTIVITY =
            EnumSet.of(LedgerTransactionType.SOCIAL_CONTRIBUTION, LedgerTransactionType.SOCIAL_DISBURSEMENT);

    /** A username that the Share / Loan sheets may refer to. */
    private record KnownMember(boolean inWorkbook, UUID existingUserId, boolean hasMembership, int existingShareCount) {}

    /**
     * Cutover invariant: the workbook is the state THROUGH {@code cutoverDate}; OuWealth data is activity AFTER
     * it. Anything already recorded on or before the cutover would be counted twice, so it is rejected here
     * (at preview and again at confirm) instead of being guessed at.
     */
    ValidatedMigration validate(
            UUID cooperativeId, MigrationWorkbook workbook, LocalDate cutoverDate, BigDecimal openingAvailableFunds) {
        ValidatedMigration out = new ValidatedMigration(cutoverDate, openingAvailableFunds);
        out.errors.addAll(workbook.workbookErrors());
        boolean loansOnly = workbook.kind() == rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind.OPENING_LOANS;
        validateSchemeState(cooperativeId, out, cutoverDate, loansOnly);
        Map<String, UUID> workbookExisting = new HashMap<>();
        Set<String> workbookUsernames = new HashSet<>();
        validateMembers(cooperativeId, workbook, out, workbookUsernames, workbookExisting, cutoverDate);
        Map<String, KnownMember> cache = new HashMap<>();
        validateShares(cooperativeId, workbook, out, workbookUsernames, workbookExisting, cache, cutoverDate);
        validateLoans(cooperativeId, workbook, out, workbookUsernames, workbookExisting, cache, cutoverDate);
        out.summary = buildSummary(out);
        return out;
    }

    private void validateSchemeState(UUID cooperativeId, ValidatedMigration out, LocalDate cutoverDate, boolean loansOnly) {
        var kind = loansOnly
                ? rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind.OPENING_LOANS
                : rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind.OPENING_BALANCE;
        if (historicalImportRepository.existsByCooperativeIdAndImportKindAndStatus(
                cooperativeId, kind, HistoricalImportStatus.CONFIRMED)) {
            out.errors.add(workbookError(
                    loansOnly ? "LOANS_ALREADY_MIGRATED" : "ALREADY_MIGRATED",
                    loansOnly
                            ? "This Saving Scheme already has a confirmed opening-loans import. The loans outstanding at the"
                                    + " cutover are imported once, so a second import is not accepted."
                            : "This Saving Scheme already has a confirmed opening-balance migration. The cutover cash position"
                                    + " is a single scheme-level value, so a second migration is not accepted."));
        }
        if (ledgerEntryRepository.countActivityOnOrBefore(cooperativeId, cutoverDate, NOT_FUND_ACTIVITY) > 0) {
            out.errors.add(workbookError(
                    "LEDGER_ACTIVITY_BEFORE_CUTOVER",
                    "OuWealth already has financial transactions dated on or before " + cutoverDate
                            + ". The cutover cash position would count them twice. Choose a cutover date before"
                            + " OuWealth started recording this Saving Scheme."));
        }
    }

    private static HistoricalImportError workbookError(String code, String message) {
        return HistoricalImportError.builder()
                .sheet("Workbook")
                .field("Cutover")
                .code(code)
                .message(message)
                .build();
    }

    /** Last instant of the cutover day in Kigali time: later approvals are post-cutover OuWealth activity. */
    private static java.time.Instant endOfCutover(LocalDate cutoverDate) {
        return cutoverDate.plusDays(1).atStartOfDay(CooperativeFieldRules.ZONE).toInstant().minusNanos(1);
    }

    // ------------------------------------------------------------------ members

    private void validateMembers(
            UUID cooperativeId,
            MigrationWorkbook workbook,
            ValidatedMigration out,
            Set<String> workbookUsernames,
            Map<String, UUID> workbookExisting,
            LocalDate cutoverDate) {
        Set<String> seenUsernames = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();
        Set<String> seenNationalIds = new HashSet<>();
        for (MigrationWorkbook.Row row : workbook.rows(HistoricalMigrationSheet.MEMBER_REGISTRATION)) {
            List<HistoricalImportError> errors = new ArrayList<>();

            String fullName = row.get(Fields.FULL_NAME);
            MemberNameSplitter.Name name = MemberNameSplitter.split(fullName);
            if (!StringUtils.hasText(fullName)) {
                errors.add(err(row, Fields.FULL_NAME, "REQUIRED", "Full Name is required."));
            } else if (name.firstName().length() > MAX_NAME_PART || name.lastName().length() > MAX_NAME_PART) {
                errors.add(err(row, Fields.FULL_NAME, "TOO_LONG", "Full Name is too long."));
            }

            String username = username(row, errors);
            String email = row.get(Fields.EMAIL).toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(email)) {
                errors.add(err(row, Fields.EMAIL, "REQUIRED", "Email is required."));
            } else if (!CooperativeFieldRules.isValidEmail(email)) {
                errors.add(err(row, Fields.EMAIL, "INVALID_EMAIL", "Email is not a valid email address."));
            }

            String rawPhone = row.get(Fields.PHONE);
            String phone = null;
            if (!StringUtils.hasText(rawPhone)) {
                errors.add(err(row, Fields.PHONE, "REQUIRED", "Phone Number is required."));
            } else if (!CooperativeFieldRules.isValidRwandanPhone(rawPhone)) {
                errors.add(err(
                        row,
                        Fields.PHONE,
                        "INVALID_PHONE",
                        "Phone Number must be a Rwandan mobile number such as 0781234567."));
            } else {
                phone = CooperativeFieldRules.normalizePhone(rawPhone);
            }

            String nationalId = StringUtils.hasText(row.get(Fields.NATIONAL_ID)) ? row.get(Fields.NATIONAL_ID) : null;
            if (nationalId != null && !nationalId.matches("^\\d{16}$")) {
                errors.add(err(
                        row, Fields.NATIONAL_ID, "INVALID_NATIONAL_ID", "National ID must be exactly 16 digits."));
            }

            BigDecimal contributions = money(row, Fields.TOTAL_CONTRIBUTIONS, true, errors);

            String usernameKey = lower(username);
            if (StringUtils.hasText(username) && !seenUsernames.add(usernameKey)) {
                errors.add(err(row, Fields.USERNAME, "DUPLICATE_USERNAME", "Username is duplicated in this workbook."));
            }
            if (StringUtils.hasText(email) && !seenEmails.add(email)) {
                errors.add(err(row, Fields.EMAIL, "DUPLICATE_EMAIL", "Email is duplicated in this workbook."));
            }
            if (nationalId != null && !seenNationalIds.add(nationalId)) {
                errors.add(err(
                        row,
                        Fields.NATIONAL_ID,
                        "DUPLICATE_NATIONAL_ID",
                        "National ID is duplicated in this workbook."));
            }
            if (StringUtils.hasText(username)) {
                workbookUsernames.add(usernameKey);
            }

            UUID existingUserId = null;
            if (errors.isEmpty()) {
                existingUserId = matchIdentity(cooperativeId, row, username, email, nationalId, errors);
                if (existingUserId != null) {
                    workbookExisting.put(usernameKey, existingUserId);
                    checkNoContributionsThrough(cooperativeId, existingUserId, cutoverDate, row, errors);
                }
            }

            boolean ok = errors.isEmpty();
            if (!ok) {
                out.errors.addAll(errors);
            }
            out.members.add(new ValidatedMigration.ValidatedRow<>(
                    HistoricalMigrationSheet.MEMBER_REGISTRATION,
                    row.rowNumber(),
                    username,
                    HistoricalFingerprint.of(cooperativeId, "OPENING_MEMBER", usernameKey),
                    ok,
                    errors,
                    ok
                            ? new ValidatedMigration.MemberDraft(
                                    username,
                                    name.firstName(),
                                    name.lastName(),
                                    email,
                                    phone,
                                    nationalId,
                                    contributions,
                                    existingUserId)
                            : null));
        }
    }

    /** Opening contributions already include anything paid up to the cutover; live rows would double count. */
    private void checkNoContributionsThrough(
            UUID cooperativeId,
            UUID memberId,
            LocalDate cutoverDate,
            MigrationWorkbook.Row row,
            List<HistoricalImportError> errors) {
        BigDecimal recorded = contributionRepository.sumPaidByMemberOnOrBefore(cooperativeId, memberId, cutoverDate);
        if (recorded != null && recorded.signum() > 0) {
            errors.add(err(
                    row,
                    Fields.TOTAL_CONTRIBUTIONS,
                    "CONTRIBUTIONS_BEFORE_CUTOVER",
                    "OuWealth already records " + MoneyUtils.scale(recorded) + " paid by this member on or before "
                            + cutoverDate + ". The opening total would count it twice."));
        }
    }

    /**
     * Identity rules (no username aliasing): a row may reuse an existing user only when its username AND
     * email resolve to that very user (and its National ID, when both sides have one, agrees). Anything
     * else that touches an existing user is an IDENTITY_CONFLICT. Returns the safely matched user id.
     */
    private UUID matchIdentity(
            UUID cooperativeId,
            MigrationWorkbook.Row row,
            String username,
            String email,
            String nationalId,
            List<HistoricalImportError> errors) {
        Optional<User> byUsername = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username);
        Optional<User> byEmail = userRepository.findByEmailIgnoreCaseAndDeletedFalse(email);
        Optional<User> byNationalId =
                nationalId == null ? Optional.empty() : userRepository.findByNationalIdAndDeletedFalse(nationalId);
        Set<UUID> identities = new LinkedHashSet<>();
        byUsername.ifPresent(u -> identities.add(u.getId()));
        byEmail.ifPresent(u -> identities.add(u.getId()));
        byNationalId.ifPresent(u -> identities.add(u.getId()));
        if (identities.isEmpty()) {
            return null;
        }
        if (identities.size() > 1) {
            errors.add(err(
                    row,
                    Fields.USERNAME,
                    "IDENTITY_CONFLICT",
                    "Username, email and National ID belong to different existing users."));
            return null;
        }
        User existing = byUsername.or(() -> byEmail).or(() -> byNationalId).orElseThrow();
        if (byUsername.isEmpty()) {
            errors.add(err(
                    row,
                    Fields.USERNAME,
                    "IDENTITY_CONFLICT",
                    "The email or National ID already belongs to existing user \"" + existing.getUsername()
                            + "\". Use that username, or correct the row."));
            return null;
        }
        if (byEmail.isEmpty()) {
            errors.add(err(
                    row,
                    Fields.EMAIL,
                    "IDENTITY_CONFLICT",
                    "Username \"" + existing.getUsername()
                            + "\" already exists with a different email. Existing profiles are not overwritten."));
            return null;
        }
        if (nationalId != null && byNationalId.isEmpty() && StringUtils.hasText(existing.getNationalId())) {
            errors.add(err(
                    row,
                    Fields.NATIONAL_ID,
                    "IDENTITY_CONFLICT",
                    "Username \"" + existing.getUsername()
                            + "\" already exists with a different National ID. Existing profiles are not overwritten."));
            return null;
        }
        Optional<CooperativeMembership> membership =
                membershipRepository.findByCooperativeIdAndUserId(cooperativeId, existing.getId());
        if (membership.isPresent()
                && openingBalanceRepository.existsByCooperativeIdAndMemberUserId(cooperativeId, existing.getId())) {
            errors.add(err(
                    row,
                    Fields.USERNAME,
                    "OPENING_BALANCE_EXISTS",
                    "Member \"" + existing.getUsername()
                            + "\" already has an opening balance in this Saving Scheme. Balances are not added together."));
            return null;
        }
        return existing.getId();
    }

    // ------------------------------------------------------------------ share capital

    private void validateShares(
            UUID cooperativeId,
            MigrationWorkbook workbook,
            ValidatedMigration out,
            Set<String> workbookUsernames,
            Map<String, UUID> workbookExisting,
            Map<String, KnownMember> cache,
            LocalDate cutoverDate) {
        Set<String> seen = new HashSet<>();
        for (MigrationWorkbook.Row row : workbook.rows(HistoricalMigrationSheet.SHARE_CAPITAL_REGISTRATION)) {
            List<HistoricalImportError> errors = new ArrayList<>();
            String username = username(row, errors);
            String key = lower(username);
            KnownMember known = null;
            if (StringUtils.hasText(username)) {
                if (!seen.add(key)) {
                    errors.add(err(
                            row,
                            Fields.USERNAME,
                            "DUPLICATE_USERNAME",
                            "Username is duplicated in this sheet. Only one row per member is allowed."));
                }
                known = resolve(cooperativeId, username, workbookUsernames, workbookExisting, cache);
                if (known == null) {
                    errors.add(unknownMember(row, username));
                } else if (!known.inWorkbook() && known.existingUserId() != null
                        && openingBalanceRepository.existsByCooperativeIdAndMemberUserId(
                                cooperativeId, known.existingUserId())) {
                    errors.add(err(
                            row,
                            Fields.USERNAME,
                            "OPENING_BALANCE_EXISTS",
                            "Member \"" + username
                                    + "\" already has an opening balance in this Saving Scheme. Balances are not added together."));
                }
            }

            Integer shareCount = null;
            String rawShares = row.get(Fields.SHARE_COUNT);
            if (!StringUtils.hasText(rawShares)) {
                errors.add(err(row, Fields.SHARE_COUNT, "REQUIRED", "Share Count is required (use 0 for none)."));
            } else {
                BigDecimal parsed = decimal(rawShares);
                if (parsed == null) {
                    errors.add(err(row, Fields.SHARE_COUNT, "INVALID_NUMBER", "Share Count must be a whole number."));
                } else if (parsed.stripTrailingZeros().scale() > 0) {
                    errors.add(err(row, Fields.SHARE_COUNT, "NOT_INTEGER", "Share Count must be a whole number."));
                } else if (parsed.signum() < 0) {
                    errors.add(err(row, Fields.SHARE_COUNT, "NEGATIVE", "Share Count cannot be negative."));
                } else if (parsed.compareTo(BigDecimal.valueOf(ShareAmountCalculator.MAX_SHARE_COUNT)) > 0) {
                    errors.add(err(
                            row,
                            Fields.SHARE_COUNT,
                            "TOO_MANY_SHARES",
                            "Share Count cannot exceed " + ShareAmountCalculator.MAX_SHARE_COUNT + "."));
                } else {
                    shareCount = parsed.intValueExact();
                    if (known != null && known.hasMembership()) {
                        checkExistingShares(cooperativeId, known, shareCount, cutoverDate, row, errors);
                    }
                }
            }
            BigDecimal capital = money(row, Fields.SHARE_CAPITAL, true, errors);

            boolean ok = errors.isEmpty();
            if (!ok) {
                out.errors.addAll(errors);
            }
            out.shares.add(new ValidatedMigration.ValidatedRow<>(
                    HistoricalMigrationSheet.SHARE_CAPITAL_REGISTRATION,
                    row.rowNumber(),
                    username,
                    HistoricalFingerprint.of(cooperativeId, "OPENING_SHARES", key),
                    ok,
                    errors,
                    ok ? new ValidatedMigration.ShareDraft(username, shareCount, capital) : null));
        }
    }

    /**
     * Share Count is the member's ABSOLUTE holding at the cutover. For a member already in OuWealth the stored
     * count may already include approved purchases made after the cutover; those are kept on top of the
     * imported figure. Any other existing shares (purchased before the cutover, or set by an earlier
     * process) cannot be told apart from the opening position, so the row is rejected instead of guessed.
     */
    private void checkExistingShares(
            UUID cooperativeId,
            KnownMember known,
            int importedShares,
            LocalDate cutoverDate,
            MigrationWorkbook.Row row,
            List<HistoricalImportError> errors) {
        UUID memberId = known.existingUserId();
        java.time.Instant cutoverEnd = endOfCutover(cutoverDate);
        int post = toInt(sharePurchaseRepository.sumApprovedSharesAfter(cooperativeId, memberId, cutoverEnd));
        int through = toInt(sharePurchaseRepository.sumApprovedSharesUpTo(cooperativeId, memberId, cutoverEnd));
        if (through > 0) {
            errors.add(err(
                    row,
                    Fields.SHARE_COUNT,
                    "SHARE_PURCHASES_BEFORE_CUTOVER",
                    "OuWealth already records " + through + " approved share purchase(s) made on or before "
                            + cutoverDate + " for this member. The opening share count would count them twice."));
        } else if (known.existingShareCount() - post > 0) {
            errors.add(err(
                    row,
                    Fields.SHARE_COUNT,
                    "SHARES_NOT_SEPARABLE",
                    "This member already holds " + (known.existingShareCount() - post)
                            + " share(s) that are not post-cutover purchases, so the opening count cannot be"
                            + " combined safely. Resolve the existing share balance first."));
        } else if (importedShares + post > ShareAmountCalculator.MAX_SHARE_COUNT) {
            errors.add(err(
                    row,
                    Fields.SHARE_COUNT,
                    "TOO_MANY_SHARES",
                    "With " + post + " share(s) bought after the cutover, this would exceed the maximum of "
                            + ShareAmountCalculator.MAX_SHARE_COUNT + " shares."));
        }
    }

    private static int toInt(Number value) {
        return value == null ? 0 : value.intValue();
    }

    // ------------------------------------------------------------------ loans

    private void validateLoans(
            UUID cooperativeId,
            MigrationWorkbook workbook,
            ValidatedMigration out,
            Set<String> workbookUsernames,
            Map<String, UUID> workbookExisting,
            Map<String, KnownMember> cache,
            LocalDate cutoverDate) {
        LoanRepaymentDateModel model = loanSettingsRepository
                .findByCooperativeId(cooperativeId)
                .map(s -> s.getRepaymentDateModel())
                .orElse(LoanRepaymentDateModel.SAME_DAY_OF_MONTH);
        Set<String> seen = new HashSet<>();
        for (MigrationWorkbook.Row row : workbook.rows(HistoricalMigrationSheet.LOAN_REGISTRATION)) {
            List<HistoricalImportError> errors = new ArrayList<>();
            String username = username(row, errors);
            KnownMember member = StringUtils.hasText(username)
                    ? resolve(cooperativeId, username, workbookUsernames, workbookExisting, cache)
                    : null;
            if (StringUtils.hasText(username) && member == null) {
                errors.add(unknownMember(row, username));
            }

            RepaymentType type = null;
            String rawType = row.get(Fields.REPAYMENT_TYPE);
            if (!StringUtils.hasText(rawType)) {
                errors.add(err(
                        row, Fields.REPAYMENT_TYPE, "REQUIRED", "Repayment Type is required (END_OF_TERM or MONTHLY)."));
            } else {
                try {
                    type = RepaymentType.valueOf(rawType.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
                } catch (IllegalArgumentException ex) {
                    errors.add(err(
                            row,
                            Fields.REPAYMENT_TYPE,
                            "INVALID_REPAYMENT_TYPE",
                            "Repayment Type must be END_OF_TERM or MONTHLY."));
                }
            }

            BigDecimal loanAmount = money(row, Fields.LOAN_AMOUNT, true, errors);
            if (loanAmount != null && loanAmount.signum() <= 0) {
                errors.add(err(row, Fields.LOAN_AMOUNT, "NOT_POSITIVE", "Loan Amount must be greater than zero."));
                loanAmount = null;
            }

            BigDecimal rate = null;
            String rawRate = row.get(Fields.INTEREST_RATE).replace("%", "").trim();
            if (!StringUtils.hasText(rawRate)) {
                errors.add(err(row, Fields.INTEREST_RATE, "REQUIRED", "Interest Rate is required (use 0 for none)."));
            } else {
                BigDecimal parsed = decimal(rawRate);
                if (parsed == null) {
                    errors.add(err(row, Fields.INTEREST_RATE, "INVALID_NUMBER", "Interest Rate must be a number."));
                } else if (parsed.signum() < 0) {
                    errors.add(err(row, Fields.INTEREST_RATE, "NEGATIVE", "Interest Rate cannot be negative."));
                } else if (parsed.compareTo(MAX_RATE) > 0) {
                    errors.add(err(row, Fields.INTEREST_RATE, "TOO_LARGE", "Interest Rate is not realistic."));
                } else {
                    rate = parsed.setScale(4, java.math.RoundingMode.HALF_UP);
                }
            }

            LocalDate issued = date(row, Fields.ISSUED_DATE, true, errors);
            if (issued != null && issued.isAfter(cutoverDate)) {
                errors.add(err(
                        row,
                        Fields.ISSUED_DATE,
                        "AFTER_CUTOVER",
                        "Loan Issued Date cannot be after the cutover date (" + cutoverDate
                                + "). Loans issued after it are recorded in OuWealth, not imported."));
                issued = null;
            }

            Integer term = null;
            String rawTerm = row.get(Fields.TERM);
            if (!StringUtils.hasText(rawTerm)) {
                errors.add(err(row, Fields.TERM, "REQUIRED", "Loan Term is required."));
            } else {
                BigDecimal parsed = decimal(rawTerm);
                if (parsed == null || parsed.stripTrailingZeros().scale() > 0) {
                    errors.add(err(row, Fields.TERM, "NOT_INTEGER", "Loan Term must be a whole number of months."));
                } else if (parsed.signum() <= 0) {
                    errors.add(err(row, Fields.TERM, "NOT_POSITIVE", "Loan Term must be greater than zero."));
                } else if (parsed.compareTo(BigDecimal.valueOf(MAX_TERM_MONTHS)) > 0) {
                    errors.add(err(row, Fields.TERM, "TOO_LARGE", "Loan Term is not realistic."));
                } else {
                    term = parsed.intValueExact();
                }
            }

            boolean monthly = type == RepaymentType.MONTHLY;
            boolean paidBlank = !StringUtils.hasText(row.get(Fields.AMOUNT_PAID));
            boolean outstandingBlank = !StringUtils.hasText(row.get(Fields.OUTSTANDING_PRINCIPAL));
            BigDecimal amountPaid = money(row, Fields.AMOUNT_PAID, monthly, errors);
            if (amountPaid == null && paidBlank && !monthly) {
                amountPaid = MoneyUtils.scale(BigDecimal.ZERO);
            }
            BigDecimal outstanding = money(row, Fields.OUTSTANDING_PRINCIPAL, monthly, errors);
            if (outstanding == null && outstandingBlank && !monthly && loanAmount != null) {
                outstanding = loanAmount;
            }

            LocalDate lastPayment = date(row, Fields.LAST_PAYMENT_DATE, false, errors);
            if (lastPayment != null) {
                if (issued != null && lastPayment.isBefore(issued)) {
                    errors.add(err(
                            row,
                            Fields.LAST_PAYMENT_DATE,
                            "BEFORE_ISSUE",
                            "Last Payment Date cannot be before the Loan Issued Date."));
                } else if (lastPayment.isAfter(cutoverDate)) {
                    errors.add(err(
                            row,
                            Fields.LAST_PAYMENT_DATE,
                            "AFTER_CUTOVER",
                            "Last Payment Date cannot be after the cutover date (" + cutoverDate + ")."));
                }
            } else if (amountPaid != null && amountPaid.signum() > 0 && !hasError(errors, Fields.LAST_PAYMENT_DATE)) {
                errors.add(err(
                        row,
                        Fields.LAST_PAYMENT_DATE,
                        "REQUIRED",
                        "Last Payment Date is required when Amount Paid to Date is greater than zero."));
            }

            ImportedLoanPositionCalculator.Position position = null;
            if (errors.isEmpty()) {
                try {
                    position = ImportedLoanPositionCalculator.calculate(new ImportedLoanPositionCalculator.Input(
                            type, loanAmount, rate, issued, term, amountPaid, outstanding, cutoverDate, model));
                } catch (ImportedLoanPositionCalculator.PositionException ex) {
                    errors.add(err(row, ex.field(), "INCONSISTENT_LOAN", ex.getMessage()));
                } catch (ValidationException ex) {
                    errors.add(err(row, Fields.LOAN_AMOUNT, "INVALID_LOAN", ex.getMessage()));
                }
            }

            if (errors.isEmpty() && member != null && member.existingUserId() != null) {
                checkNotAlreadyInSystem(cooperativeId, member.existingUserId(), loanAmount, rate, issued, term, row, errors);
            }

            if (errors.isEmpty()) {
                String duplicateKey = String.join(
                        "|", lower(username), String.valueOf(type), String.valueOf(loanAmount),
                        String.valueOf(rate), String.valueOf(issued), String.valueOf(term));
                if (!seen.add(duplicateKey)) {
                    errors.add(err(
                            row,
                            Fields.USERNAME,
                            "DUPLICATE_LOAN",
                            "This loan is duplicated in the workbook (same member, type, amount, rate, date and term)."));
                }
            }

            boolean ok = errors.isEmpty();
            if (!ok) {
                out.errors.addAll(errors);
            }
            out.loans.add(new ValidatedMigration.ValidatedRow<>(
                    HistoricalMigrationSheet.LOAN_REGISTRATION,
                    row.rowNumber(),
                    username,
                    HistoricalFingerprint.of(cooperativeId, "OPENING_LOAN", lower(username), String.valueOf(row.rowNumber())),
                    ok,
                    errors,
                    ok
                            ? new ValidatedMigration.LoanDraft(
                                    username, type, loanAmount, rate, issued, term, amountPaid, lastPayment, position)
                            : null));
        }
    }

    /** An equivalent loan (same member, amount, rate, term and issue date) must not be created a second time. */
    private void checkNotAlreadyInSystem(
            UUID cooperativeId,
            UUID memberId,
            BigDecimal amount,
            BigDecimal rate,
            LocalDate issued,
            int term,
            MigrationWorkbook.Row row,
            List<HistoricalImportError> errors) {
        boolean exists = loanRepository
                .findByCooperativeIdAndMemberUserIdOrderByRequestDateDescCreatedAtDesc(cooperativeId, memberId)
                .stream()
                .filter(l -> l.getStatus() != LoanStatus.REJECTED && l.getStatus() != LoanStatus.PENDING)
                .anyMatch(l -> l.getTermMonths() == term
                        && issued.equals(l.getDisbursementDate() != null ? l.getDisbursementDate() : l.getRequestDate())
                        && l.getInterestRatePercent() != null
                        && l.getInterestRatePercent().compareTo(rate) == 0
                        && l.getPrincipalAmount() != null
                        && l.getPrincipalAmount().compareTo(amount) == 0);
        if (exists) {
            errors.add(err(
                    row,
                    Fields.USERNAME,
                    "LOAN_ALREADY_MIGRATED",
                    "An equivalent loan (same member, amount, rate, term and issue date) already exists in OuWealth."
                            + " It is not created again."));
        }
    }

    // ------------------------------------------------------------------ summary

    private HistoricalMigrationSummary buildSummary(ValidatedMigration out) {
        BigDecimal contributions = zero();
        int memberValid = 0;
        for (var row : out.members) {
            if (row.valid()) {
                memberValid++;
                contributions = MoneyUtils.add(contributions, row.draft().openingContribution());
            }
        }
        long shares = 0;
        BigDecimal capital = zero();
        int shareValid = 0;
        for (var row : out.shares) {
            if (row.valid()) {
                shareValid++;
                shares += row.draft().shareCount();
                capital = MoneyUtils.add(capital, row.draft().shareCapital());
            }
        }
        int loanValid = 0;
        int endOfTerm = 0;
        int monthly = 0;
        BigDecimal original = zero();
        BigDecimal outstanding = zero();
        BigDecimal paid = zero();
        for (var row : out.loans) {
            if (row.valid()) {
                loanValid++;
                var d = row.draft();
                if (d.type() == RepaymentType.MONTHLY) {
                    monthly++;
                } else {
                    endOfTerm++;
                }
                original = MoneyUtils.add(original, d.loanAmount());
                outstanding = MoneyUtils.add(outstanding, d.position().outstandingPrincipal());
                paid = MoneyUtils.add(paid, d.amountPaid());
            }
        }
        return HistoricalMigrationSummary.builder()
                .cutoverDate(out.cutoverDate)
                .openingAvailableFunds(out.openingAvailableFunds)
                .members(HistoricalMigrationSummary.MemberSection.builder()
                        .totalRows(out.members.size())
                        .validRows(memberValid)
                        .invalidRows(out.members.size() - memberValid)
                        .totalOpeningContributions(contributions)
                        .build())
                .shareCapital(HistoricalMigrationSummary.ShareSection.builder()
                        .totalRows(out.shares.size())
                        .validRows(shareValid)
                        .invalidRows(out.shares.size() - shareValid)
                        .totalOpeningShares(shares)
                        .totalOpeningShareCapital(capital)
                        .build())
                .loans(HistoricalMigrationSummary.LoanSection.builder()
                        .totalRows(out.loans.size())
                        .validRows(loanValid)
                        .invalidRows(out.loans.size() - loanValid)
                        .endOfTermCount(endOfTerm)
                        .monthlyCount(monthly)
                        .totalOriginalPrincipal(original)
                        .totalOutstandingPrincipal(outstanding)
                        .totalAmountPaid(paid)
                        .build())
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private KnownMember resolve(
            UUID cooperativeId,
            String username,
            Set<String> workbookUsernames,
            Map<String, UUID> workbookExisting,
            Map<String, KnownMember> cache) {
        String key = lower(username);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        boolean inWorkbook = workbookUsernames.contains(key);
        UUID userId = inWorkbook
                ? workbookExisting.get(key)
                : userRepository
                        .findByUsernameIgnoreCaseAndDeletedFalse(username)
                        .map(User::getId)
                        .orElse(null);
        KnownMember resolved = null;
        if (userId != null) {
            var membership = membershipRepository.findByCooperativeIdAndUserId(cooperativeId, userId);
            if (membership.isPresent()) {
                resolved = new KnownMember(
                        inWorkbook,
                        userId,
                        true,
                        ShareAmountCalculator.ownedShareCount(membership.get().getShareCount()));
            } else if (inWorkbook) {
                resolved = new KnownMember(true, userId, false, 0);
            }
        } else if (inWorkbook) {
            resolved = new KnownMember(true, null, false, 0);
        }
        cache.put(key, resolved);
        return resolved;
    }

    private HistoricalImportError unknownMember(MigrationWorkbook.Row row, String username) {
        return err(
                row,
                Fields.USERNAME,
                "UNKNOWN_MEMBER",
                "Member \"" + username + "\" does not exist in Member Registration or this Saving Scheme.");
    }

    private String username(MigrationWorkbook.Row row, List<HistoricalImportError> errors) {
        String username = row.get(Fields.USERNAME);
        if (!StringUtils.hasText(username)) {
            errors.add(err(row, Fields.USERNAME, "REQUIRED", "Username is required."));
            return null;
        }
        if (username.length() < 3 || username.length() > 64) {
            errors.add(err(row, Fields.USERNAME, "INVALID_USERNAME", "Username must be between 3 and 64 characters."));
        } else if (username.matches(".*\\s.*")) {
            errors.add(err(row, Fields.USERNAME, "INVALID_USERNAME", "Username cannot contain spaces."));
        }
        return username;
    }

    /** Parses a non-negative RWF amount rounded with {@link MoneyUtils}; adds an error and returns null otherwise. */
    private BigDecimal money(MigrationWorkbook.Row row, String field, boolean required, List<HistoricalImportError> errors) {
        String raw = row.get(field);
        if (!StringUtils.hasText(raw)) {
            if (required) {
                errors.add(err(row, field, "REQUIRED", field + " is required (use 0 for none)."));
            }
            return null;
        }
        BigDecimal parsed = decimal(raw);
        if (parsed == null) {
            errors.add(err(row, field, "INVALID_NUMBER", field + " must be a number."));
            return null;
        }
        if (parsed.signum() < 0) {
            errors.add(err(row, field, "NEGATIVE", field + " cannot be negative."));
            return null;
        }
        BigDecimal scaled = MoneyUtils.scale(parsed);
        if (scaled.compareTo(MAX_MONEY) > 0) {
            errors.add(err(row, field, "TOO_LARGE", field + " is too large."));
            return null;
        }
        return scaled;
    }

    private LocalDate date(MigrationWorkbook.Row row, String field, boolean required, List<HistoricalImportError> errors) {
        String raw = row.get(field);
        if (!StringUtils.hasText(raw)) {
            if (required) {
                errors.add(err(row, field, "REQUIRED", field + " is required."));
            }
            return null;
        }
        LocalDate parsed = WorkbookCellReader.parseDate(raw);
        if (parsed == null) {
            errors.add(err(row, field, "INVALID_DATE", field + " must be a valid date (YYYY-MM-DD or dd/MM/yyyy)."));
        }
        return parsed;
    }

    private static BigDecimal decimal(String raw) {
        try {
            return new BigDecimal(raw.trim().replace(",", "").replace(" ", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean hasError(List<HistoricalImportError> errors, String field) {
        return errors.stream().anyMatch(e -> field.equals(e.getField()));
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal zero() {
        return MoneyUtils.scale(BigDecimal.ZERO);
    }

    private static HistoricalImportError err(MigrationWorkbook.Row row, String field, String code, String message) {
        return HistoricalImportError.builder()
                .sheet(row.sheet().getSheetName())
                .rowNumber(row.rowNumber())
                .field(field)
                .code(code)
                .message(message)
                .build();
    }
}
