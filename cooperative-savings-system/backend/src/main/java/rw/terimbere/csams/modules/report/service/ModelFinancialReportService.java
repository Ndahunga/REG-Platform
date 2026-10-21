package rw.terimbere.csams.modules.report.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.contribution.obligation.ContributionDueDates;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.report.service.ModelFinancialSummaryService.Summary;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService.PeriodEntitlement;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * The financial report of a Saving Scheme in the terms of its operating model, built only from actual posted records
 * (approved ledger movements, loans, obligations and payouts). Expectations are never reported as money received.
 *
 * <p>Earnings are reported as what they are (income less expenses of the period). Distributing them needs a policy the
 * scheme has not approved in this system, so no dividend, percentage or distribution is ever calculated: the report
 * carries {@code distributionStatus = UNAVAILABLE_PENDING_POLICY}. For a hybrid scheme the share of earnings that
 * belongs to retained capital is shown only as an indicative proportion of capital, labelled as such.
 */
@Service
@RequiredArgsConstructor
public class ModelFinancialReportService {

    public static final String DISTRIBUTION_UNAVAILABLE = "UNAVAILABLE_PENDING_POLICY";

    private static final int MAX_MEMBERS = 500;

    private final CooperativeRepository cooperativeRepository;
    private final ModelFinancialSummaryService summaryService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanRepository loanRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final RotationEntitlementService entitlementService;
    private final Clock clock;

    public record Earnings(
            BigDecimal interestIncome,
            BigDecimal fineIncome,
            BigDecimal investmentProfit,
            BigDecimal otherIncome,
            BigDecimal totalIncome,
            BigDecimal operatingExpenses,
            BigDecimal interestExpense,
            BigDecimal totalExpenses,
            BigDecimal netEarnings,
            /** Always UNAVAILABLE_PENDING_POLICY: no earnings distribution policy is approved in this system. */
            String distributionStatus) {}

    public record Movements(
            BigDecimal contributionsCollected,
            BigDecimal loansDisbursed,
            BigDecimal principalRepaid,
            BigDecimal memberPayouts) {}

    public record LoanBook(BigDecimal outstandingPrincipal, BigDecimal unpaidInterest) {}

    public record MemberShare(UUID userId, String name, int shares) {}

    public record Shares(long totalShares, int members, List<MemberShare> holders) {}

    public record RotationBlock(
            List<PeriodEntitlement> periods,
            BigDecimal verifiedCollected,
            BigDecimal unreconciled,
            BigDecimal unresolvedRotating,
            BigDecimal unpaidEntitlements,
            BigDecimal paidOut) {}

    /**
     * @param retainedCapitalShareBps retained capital as a share of retained capital plus everything held for recipients
     *     (basis points). Indicative only.
     * @param earningsAttributableToRetained net earnings multiplied by that share, or null when it is not meaningful.
     *     Indicative only: the attribution of earnings between retained and rotating money is not an approved policy.
     */
    public record HybridBlock(
            BigDecimal retainedCapital,
            BigDecimal rotatingHeld,
            Integer retainedCapitalShareBps,
            BigDecimal earningsAttributableToRetained,
            String attributionBasis) {}

    public record ModelReport(
            UUID cooperativeId,
            String currency,
            SchemeOperatingModel operatingModel,
            LocalDate from,
            LocalDate to,
            Instant generatedAt,
            Summary position,
            Movements movements,
            LoanBook loans,
            Shares shares,
            Earnings earnings,
            RotationBlock rotation,
            HybridBlock hybrid,
            List<String> notes) {}

    @Transactional(readOnly = true)
    public ModelReport report(UUID cooperativeId, LocalDate from, LocalDate to) {
        Summary position = summaryService.summary(cooperativeId); // also enforces the officer authority and membership
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        LocalDate today = ContributionDueDates.today(clock);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
        if (start.isAfter(end)) {
            throw new ValidationException("The start date must not be after the end date");
        }

        Map<LedgerTransactionType, BigDecimal[]> byType = new HashMap<>();
        for (Object[] row : ledgerEntryRepository.sumMovementByTypeInPeriod(
                cooperativeId, start, end, List.of(LedgerTransactionType.SHARE_PURCHASE))) {
            byType.put((LedgerTransactionType) row[0], new BigDecimal[] {(BigDecimal) row[1], (BigDecimal) row[2]});
        }
        BigDecimal interestIncome = credit(byType, LedgerTransactionType.LOAN_INTEREST_PAYMENT);
        BigDecimal fineIncome = credit(byType, LedgerTransactionType.FINE_PAYMENT);
        BigDecimal investmentProfit = credit(byType, LedgerTransactionType.INVESTMENT_PROFIT);
        BigDecimal otherIncome = credit(byType, LedgerTransactionType.OTHER_INCOME);
        BigDecimal income = interestIncome.add(fineIncome).add(investmentProfit).add(otherIncome);
        BigDecimal operating = debit(byType, LedgerTransactionType.GENERAL_EXPENSE);
        BigDecimal interestExpense = debit(byType, LedgerTransactionType.INTEREST_EXPENSE);
        BigDecimal expenses = operating.add(interestExpense);
        Earnings earnings = new Earnings(
                interestIncome, fineIncome, investmentProfit, otherIncome, scale(income), operating, interestExpense,
                scale(expenses), scale(income.subtract(expenses)), DISTRIBUTION_UNAVAILABLE);

        Movements movements = new Movements(
                credit(byType, LedgerTransactionType.REGULAR_CONTRIBUTION).add(credit(byType, LedgerTransactionType.SPECIAL_CONTRIBUTION)),
                debit(byType, LedgerTransactionType.LOAN_DISBURSEMENT),
                credit(byType, LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT),
                debit(byType, LedgerTransactionType.MEMBER_PAYOUT));

        var live = EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE);
        LoanBook loans = new LoanBook(
                scale(loanRepository.sumOutstandingPrincipalByStatuses(cooperativeId, live)),
                scale(loanRepository.sumOutstandingInterestByStatuses(cooperativeId, live)));

        Shares shares = shares(cooperativeId);

        SchemeOperatingModel model = cooperative.getOperatingModel();
        boolean rotating = model == SchemeOperatingModel.ROTATING || model == SchemeOperatingModel.HYBRID;
        RotationBlock rotation = null;
        if (rotating) {
            List<PeriodEntitlement> periods = entitlementService.overview(cooperativeId);
            rotation = new RotationBlock(
                    periods,
                    position.rotatingCollected(),
                    position.rotatingRequiringReconciliation(),
                    position.unresolvedRotatingCollections(),
                    position.recipientEntitlementsOwed(),
                    position.payoutsDisbursed());
        }
        HybridBlock hybrid = null;
        if (model == SchemeOperatingModel.HYBRID) {
            BigDecimal held = position.totalRotatingLiabilities();
            BigDecimal retained = position.retainedCollections();
            BigDecimal capital = retained.add(held);
            Integer bps = capital.signum() > 0
                    ? retained.multiply(BigDecimal.valueOf(10_000)).divide(capital, 0, java.math.RoundingMode.HALF_UP).intValue()
                    : null;
            BigDecimal attributable = bps == null
                    ? null
                    : scale(earnings.netEarnings().multiply(BigDecimal.valueOf(bps)).divide(BigDecimal.valueOf(10_000), 4, java.math.RoundingMode.HALF_UP));
            hybrid = new HybridBlock(retained, held, bps, attributable, "PROPORTIONAL_TO_CAPITAL_INDICATIVE_ONLY");
        }

        List<String> notes = new ArrayList<>();
        notes.add("Figures come from approved ledger movements, loans, obligations and payouts. Expected contributions are never counted as money received.");
        notes.add("Earnings are income less expenses of the period. No distribution or dividend is calculated: it is " + DISTRIBUTION_UNAVAILABLE + ".");
        if (position.memberCreditHeld().signum() > 0) {
            notes.add("Member credit held: money members paid in excess is held for them. It is not income, not member"
                    + " capital and not part of the available funds, until an officer applies it to a contribution or a loan.");
        }
        if (position.unclassifiedCollections().signum() > 0) {
            notes.add("Some collections cannot be classified and are assigned to nobody until they are resolved.");
        }
        if (position.unresolvedRotatingCollections().signum() > 0) {
            notes.add("Some collections are known to be rotating but their original period is unresolved: they are held, not payable and not member capital.");
        }
        return new ModelReport(
                cooperativeId, cooperative.getCurrency(), model, start, end, Instant.now(clock), position, movements, loans, shares,
                earnings, rotation, hybrid, notes);
    }

    private Shares shares(UUID cooperativeId) {
        List<CooperativeMembership> active = membershipRepository.findActiveByCooperativeId(cooperativeId);
        Map<UUID, User> users = new HashMap<>();
        userRepository.findAllById(active.stream().map(CooperativeMembership::getUserId).toList()).forEach(u -> users.put(u.getId(), u));
        long total = 0;
        List<MemberShare> holders = new ArrayList<>();
        for (CooperativeMembership m : active) {
            int count = m.getShareCount() == null ? 0 : m.getShareCount();
            total += count;
            if (holders.size() < MAX_MEMBERS) {
                User u = users.get(m.getUserId());
                holders.add(new MemberShare(m.getUserId(), u == null ? "" : u.getFullName(), count));
            }
        }
        holders.sort(java.util.Comparator.comparing(MemberShare::name, String.CASE_INSENSITIVE_ORDER));
        return new Shares(total, active.size(), holders);
    }

    private static BigDecimal credit(Map<LedgerTransactionType, BigDecimal[]> byType, LedgerTransactionType type) {
        BigDecimal[] v = byType.get(type);
        return scale(v == null ? BigDecimal.ZERO : v[0]);
    }

    private static BigDecimal debit(Map<LedgerTransactionType, BigDecimal[]> byType, LedgerTransactionType type) {
        BigDecimal[] v = byType.get(type);
        return scale(v == null ? BigDecimal.ZERO : v[1]);
    }

    private static BigDecimal scale(BigDecimal value) {
        return MoneyUtils.scaleForStorage(value == null ? BigDecimal.ZERO : value);
    }
}
