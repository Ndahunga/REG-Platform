package rw.terimbere.csams.modules.historicalimport.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportConfirmResponse;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanGuaranteeMode;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanSettings;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.repository.LoanSettingsRepository;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator;
import rw.terimbere.csams.modules.loan.service.LoanAllocationOrder;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.entity.MemberOpeningBalance;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;

/**
 * Writes a validated opening-balance migration. Runs inside the caller's single transaction: any failure
 * rolls back users, memberships, opening balances, loans and installments together.
 *
 * <p>Nothing here creates a Contribution, SharePurchase, LoanRepayment or ledger entry: the imported
 * figures are opening <em>balances</em> at the cutover date, not transactions.
 */
@Service
@RequiredArgsConstructor
class MigrationPersistenceService {

    static final String ROLE_MEMBER = "MEMBER";
    static final String SHEET_LOAN_ENTITY = "Loan";
    private static final String PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%^&*";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final MemberOpeningBalanceRepository openingBalanceRepository;
    private final LoanRepository loanRepository;
    private final LoanInstallmentRepository loanInstallmentRepository;
    private final LoanSettingsRepository loanSettingsRepository;
    private final rw.terimbere.csams.modules.share.repository.SharePurchaseRepository sharePurchaseRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    record PersistResult(HistoricalImportConfirmResponse counts, Map<String, UUID> resultingEntityIds) {}

    PersistResult persist(Cooperative cooperative, ValidatedMigration migration, UUID importId, UUID actorId) {
        UUID cooperativeId = cooperative.getId();
        LocalDate cutover = migration.cutoverDate;
        Map<String, UUID> resultIds = new HashMap<>();
        Map<String, UUID> userIds = new HashMap<>();

        // 1. Users and memberships. Membership start = cutover (technical enrollment date, not a join date).
        int membersImported = 0;
        for (var row : migration.members) {
            if (!row.valid()) {
                continue;
            }
            ValidatedMigration.MemberDraft draft = row.draft();
            User user = draft.createUser()
                    ? userRepository.save(newUser(draft))
                    : userRepository.findByIdAndDeletedFalse(draft.existingUserId()).orElseThrow();
            userIds.put(lower(draft.username()), user.getId());
            resultIds.put(key(row.sheet().getSheetName(), row.rowNumber()), user.getId());
            if (membershipRepository
                    .findByCooperativeIdAndUserId(cooperativeId, user.getId())
                    .isEmpty()) {
                membershipRepository.save(CooperativeMembership.builder()
                        .userId(user.getId())
                        .cooperativeId(cooperativeId)
                        .membershipStatus("ACTIVE")
                        .membershipDate(cutover)
                        .roleInCooperative(ROLE_MEMBER)
                        .shareCount(0)
                        .build());
                membersImported++;
            }
        }

        // 2. Opening balances: one per cooperative + member, combining both sheets.
        Map<String, BigDecimal> contributions = new LinkedHashMap<>();
        Map<String, ValidatedMigration.ShareDraft> shares = new LinkedHashMap<>();
        Map<String, String> sourceRows = new HashMap<>();
        for (var row : migration.members) {
            if (row.valid()) {
                contributions.put(lower(row.draft().username()), row.draft().openingContribution());
            }
        }
        for (var row : migration.shares) {
            if (row.valid()) {
                shares.put(lower(row.draft().username()), row.draft());
                sourceRows.put(lower(row.draft().username()), key(row.sheet().getSheetName(), row.rowNumber()));
            }
        }
        Set<String> usernames = new java.util.LinkedHashSet<>(contributions.keySet());
        usernames.addAll(shares.keySet());

        BigDecimal totalContributions = zero();
        BigDecimal totalCapital = zero();
        long totalShares = 0;
        int openingBalances = 0;
        int shareBalances = 0;
        for (String username : usernames) {
            UUID memberId = userIds.get(username);
            if (memberId == null) {
                memberId = userRepository
                        .findByUsernameIgnoreCaseAndDeletedFalse(username)
                        .orElseThrow(() -> new IllegalStateException("Unresolved member " + username))
                        .getId();
                userIds.put(username, memberId);
            }
            if (openingBalanceRepository.existsByCooperativeIdAndMemberUserId(cooperativeId, memberId)) {
                throw new BusinessException(
                        "OPENING_BALANCE_EXISTS",
                        "Member \"" + username + "\" already has an opening balance in this Saving Scheme");
            }
            BigDecimal contribution = contributions.getOrDefault(username, zero());
            ValidatedMigration.ShareDraft share = shares.get(username);
            int shareCount = share == null ? 0 : share.shareCount();
            BigDecimal capital = share == null ? zero() : share.shareCapital();

            MemberOpeningBalance saved = openingBalanceRepository.save(MemberOpeningBalance.builder()
                    .cooperativeId(cooperativeId)
                    .memberUserId(memberId)
                    .openingContributionAmount(MoneyUtils.scaleForStorage(contribution))
                    .openingShareCount(shareCount)
                    .openingShareCapitalAmount(MoneyUtils.scaleForStorage(capital))
                    .cutoverDate(cutover)
                    .historicalImportId(importId)
                    .createdBy(actorId)
                    .build());
            openingBalances++;
            totalContributions = MoneyUtils.add(totalContributions, contribution);
            if (share != null) {
                shareBalances++;
                totalShares += shareCount;
                totalCapital = MoneyUtils.add(totalCapital, capital);
                resultIds.put(sourceRows.get(username), saved.getId());
                // Share Count is the ABSOLUTE holding at the cutover, not a delta. Shares approved after the
                // cutover (already in OuWealth) stay on top; the validator rejected any other existing shares.
                CooperativeMembership membership = membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberId)
                        .orElseThrow(() -> new IllegalStateException("Membership missing for share opening balance"));
                Number postCutover = sharePurchaseRepository.sumApprovedSharesAfter(
                        cooperativeId,
                        memberId,
                        cutover.plusDays(1).atStartOfDay(CooperativeFieldRules.ZONE).toInstant().minusNanos(1));
                int post = postCutover == null ? 0 : postCutover.intValue();
                int current = membership.getShareCount() == null ? 0 : membership.getShareCount();
                if (current - post != 0) {
                    throw new BusinessException(
                            "SHARES_NOT_SEPARABLE",
                            "Member \"" + username + "\" has shares that cannot be separated from the opening count");
                }
                membership.setShareCount(shareCount + post);
                membershipRepository.save(membership);
            }
        }

        // 3. Loans still outstanding at cutover: an opening receivable with no historical transactions.
        LoanSettings settings = loanSettingsRepository.findByCooperativeId(cooperativeId).orElse(null);
        int loans = 0;
        int installments = 0;
        BigDecimal original = zero();
        BigDecimal outstanding = zero();
        BigDecimal paid = zero();
        for (var row : migration.loans) {
            if (!row.valid()) {
                continue;
            }
            ValidatedMigration.LoanDraft draft = row.draft();
            UUID memberId = userIds.get(lower(draft.username()));
            if (memberId == null) {
                memberId = userRepository
                        .findByUsernameIgnoreCaseAndDeletedFalse(draft.username())
                        .orElseThrow(() -> new IllegalStateException("Unresolved member " + draft.username()))
                        .getId();
            }
            Loan loan = loanRepository.save(newLoan(cooperativeId, memberId, draft, settings, importId));
            installments += persistInstallments(loan, draft.position());
            resultIds.put(key(row.sheet().getSheetName(), row.rowNumber()), loan.getId());
            loans++;
            original = MoneyUtils.add(original, draft.loanAmount());
            outstanding = MoneyUtils.add(outstanding, draft.position().outstandingPrincipal());
            paid = MoneyUtils.add(paid, draft.amountPaid());
        }

        return new PersistResult(
                HistoricalImportConfirmResponse.builder()
                        .cutoverDate(cutover)
                        .openingAvailableFunds(migration.openingAvailableFunds)
                        .membersImported(membersImported)
                        .memberOpeningBalancesImported(openingBalances)
                        .shareOpeningBalancesImported(shareBalances)
                        .loansImported(loans)
                        .loanInstallmentsCreated(installments)
                        .totalHistoricalContributions(totalContributions)
                        .totalHistoricalShares(totalShares)
                        .totalHistoricalShareCapital(totalCapital)
                        .totalOriginalLoanAmount(original)
                        .totalOutstandingLoanPrincipal(outstanding)
                        .totalHistoricalLoanPayments(paid)
                        .build(),
                resultIds);
    }

    private User newUser(ValidatedMigration.MemberDraft draft) {
        Role memberRole = roleRepository
                .findByCode(ROLE_MEMBER)
                .orElseThrow(() -> new IllegalStateException("Missing role " + ROLE_MEMBER));
        Set<Role> roles = new HashSet<>();
        roles.add(memberRole);
        return User.builder()
                .username(draft.username())
                .email(draft.email())
                // Unknown random password: the member signs in through "Forgot password".
                .passwordHash(passwordEncoder.encode(randomPassword()))
                .firstName(draft.firstName())
                .lastName(draft.lastName())
                .phone(CooperativeFieldRules.normalizePhone(draft.phone()))
                .nationalId(draft.nationalId())
                .accountStatus(AccountStatus.ACTIVE)
                .roles(roles)
                .build();
    }

    /**
     * The loan keeps the original contract (principal, rate, term, issue date) and the position at
     * cutover. Approval/disbursement actors stay empty: the import actor did not issue this loan, the
     * original manual loan was never approved in OuWealth ({@code openingImportId} identifies it).
     */
    private Loan newLoan(
            UUID cooperativeId,
            UUID memberId,
            ValidatedMigration.LoanDraft draft,
            LoanSettings settings,
            UUID importId) {
        ImportedLoanPositionCalculator.Position position = draft.position();
        Loan.LoanBuilder builder = Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .requestedAmount(MoneyUtils.scaleForStorage(draft.loanAmount()))
                .approvedAmount(MoneyUtils.scaleForStorage(draft.loanAmount()))
                .principalAmount(MoneyUtils.scaleForStorage(position.principalAmount()))
                .interestRatePercent(draft.interestRatePercent())
                .interestType(InterestType.FLAT)
                .termMonths(draft.termMonths())
                .interestAmount(MoneyUtils.scaleForStorage(position.totalInterest()))
                .prorataEnabled(position.prorataEnabled())
                .firstPeriodDays(position.firstPeriodDays())
                .equalInstallmentAmount(MoneyUtils.scaleForStorage(position.equalInstallmentAmount()))
                .repaymentDateModel(position.repaymentDateModel())
                .outstandingPrincipal(MoneyUtils.scaleForStorage(position.outstandingPrincipal()))
                .outstandingInterest(MoneyUtils.scaleForStorage(position.outstandingInterest()))
                .totalRepaidPrincipal(MoneyUtils.scaleForStorage(position.principalPaid()))
                .totalRepaidInterest(MoneyUtils.scaleForStorage(position.interestPaid()))
                .requestDate(draft.issuedDate())
                .approvalDate(draft.issuedDate())
                .disbursementDate(draft.issuedDate())
                .dueDate(position.dueDate())
                .status(position.status())
                .guaranteeMode(LoanGuaranteeMode.SELF)
                .purpose("Opening-balance migration: loan outstanding at the cutover date")
                .openingImportId(importId);
        if (settings != null) {
            builder.loanPenaltyEnabled(settings.isLateFeeEnabled())
                    .penaltyType(settings.getPenaltyType())
                    .penaltyRateOrAmount(settings.getPenaltyRateOrAmount())
                    .penaltyFrequency(settings.getPenaltyFrequency())
                    .gracePeriodDays(settings.getGracePeriodDays());
        }
        builder.allocationOrder(LoanAllocationOrder.serialize(
                LoanAllocationOrder.parse(settings == null ? null : settings.getAllocationOrder())));
        return builder.build();
    }

    private int persistInstallments(Loan loan, ImportedLoanPositionCalculator.Position position) {
        LocalDate today = LocalDate.now(CooperativeFieldRules.ZONE);
        List<LoanInstallment> rows = new ArrayList<>();
        for (ImportedLoanPositionCalculator.RemainingInstallment item : position.installments()) {
            LoanInstallment row = LoanInstallment.builder()
                    .loanId(loan.getId())
                    .cooperativeId(loan.getCooperativeId())
                    .installmentNumber(item.installmentNumber())
                    .dueDate(item.dueDate())
                    .openingPrincipalBalance(MoneyUtils.scaleForStorage(item.openingPrincipalBalance()))
                    .principalDue(MoneyUtils.scaleForStorage(item.principalDue()))
                    .interestDue(MoneyUtils.scaleForStorage(item.interestDue()))
                    .scheduledInstallmentAmount(MoneyUtils.scaleForStorage(item.totalDue()))
                    .build();
            row.refreshStatus(today);
            rows.add(row);
        }
        loanInstallmentRepository.saveAll(rows);
        return rows.size();
    }

    private String randomPassword() {
        StringBuilder builder = new StringBuilder(48);
        for (int i = 0; i < 48; i++) {
            builder.append(PASSWORD_CHARS.charAt(secureRandom.nextInt(PASSWORD_CHARS.length())));
        }
        return builder.toString();
    }

    static String key(String sheetName, int rowNumber) {
        return sheetName + ":" + rowNumber;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal zero() {
        return MoneyUtils.scale(BigDecimal.ZERO);
    }
}
