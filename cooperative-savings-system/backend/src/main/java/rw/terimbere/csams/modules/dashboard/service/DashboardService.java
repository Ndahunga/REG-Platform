package rw.terimbere.csams.modules.dashboard.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.contribution.dto.MonthlyContributionChartPoint;
import rw.terimbere.csams.modules.contribution.entity.ContributionReviewStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.dashboard.dto.DashboardAdvancedInsightsResponse;
import rw.terimbere.csams.modules.dashboard.dto.DashboardInsightsResponse;
import rw.terimbere.csams.modules.dashboard.dto.DashboardMemberInsightsResponse;
import rw.terimbere.csams.modules.dashboard.dto.DashboardSummaryResponse;
import rw.terimbere.csams.modules.dashboard.dto.InvestmentsByMonthPoint;
import rw.terimbere.csams.modules.dashboard.dto.LoansDisbursedByMonthPoint;
import rw.terimbere.csams.modules.dashboard.dto.PlatformOverviewResponse;
import rw.terimbere.csams.modules.dashboard.support.MonthOverMonthCalculator;
import rw.terimbere.csams.modules.dashboard.support.RepaymentReliabilityAggregator;
import rw.terimbere.csams.modules.dashboard.support.RepaymentReliabilityClassifier;
import rw.terimbere.csams.modules.dashboard.support.RepaymentReliabilityClassifier.InstallmentFact;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.investment.service.InvestmentService;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanService;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.modules.payout.service.PayoutService;
import rw.terimbere.csams.modules.settings.entity.CooperativeSettings;
import rw.terimbere.csams.modules.settings.repository.CooperativeSettingsRepository;
import rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus;
import rw.terimbere.csams.modules.socialfund.repository.SocialContributionRepository;
import rw.terimbere.csams.modules.socialfund.service.SocialFundBalanceService;
import rw.terimbere.csams.modules.socialfund.service.SocialFundService;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final String DEFAULT_TIMEZONE = "Africa/Kigali";
    private static final EnumSet<LoanStatus> DISBURSED_STATUSES =
            EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.CLOSED, LoanStatus.WRITTEN_OFF);
    private static final EnumSet<InvestmentStatus> DEPLOYED_INVESTMENT_STATUSES = EnumSet.of(
            InvestmentStatus.ACTIVE,
            InvestmentStatus.PARTIALLY_RETURNED,
            InvestmentStatus.COMPLETED,
            InvestmentStatus.LOSS_RECORDED);
    private static final EnumSet<InvestmentStatus> ACTIVE_INVESTMENT_STATUSES =
            EnumSet.of(InvestmentStatus.ACTIVE, InvestmentStatus.PARTIALLY_RETURNED);
    private static final int ADVANCED_INSIGHTS_LIMIT = 5;

    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final ContributionRepository contributionRepository;
    private final SpecialContributionRepository specialContributionRepository;
    private final ContributionService contributionService;
    private final LoanService loanService;
    private final FineService fineService;
    private final SocialFundBalanceService socialFundBalanceService;
    private final SocialFundService socialFundService;
    private final InvestmentService investmentService;
    private final InvestmentRepository investmentRepository;
    private final PayoutService payoutService;
    private final LedgerFinancialCalculationService financialCalculationService;
    private final CooperativeAuthorizationService authorizationService;
    private final UserRepository userRepository;
    private final LoanRepository loanRepository;
    private final LoanInstallmentRepository loanInstallmentRepository;
    private final LoanRepaymentRepository loanRepaymentRepository;
    private final FineRepository fineRepository;
    private final FinePaymentRepository finePaymentRepository;
    private final SocialContributionRepository socialContributionRepository;
    private final PayoutRunRepository payoutRunRepository;
    private final CooperativeSettingsRepository cooperativeSettingsRepository;

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary(UUID cooperativeId) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        authorizationService.requireMembership(cooperativeId);

        String timezone = resolveTimezone(cooperativeId);
        LocalDate today = LocalDate.now(ZoneId.of(timezone));

        long totalMembers = membershipRepository.countByCooperativeId(cooperativeId);
        long activeMembers = membershipRepository.countByCooperativeIdAndMembershipStatus(cooperativeId, "ACTIVE");

        BigDecimal regularFromTable = contributionRepository.sumPaidForPeriod(cooperativeId, null, null);
        // Lifetime regular contributions include the opening balance imported at the migration cutover.
        BigDecimal regular = MoneyUtils.add(
                MoneyUtils.scale(regularFromTable == null ? BigDecimal.ZERO : regularFromTable),
                financialCalculationService.sumOpeningContributions(cooperativeId));

        BigDecimal special = financialCalculationService.sumApprovedCreditsByType(
                cooperativeId, LedgerTransactionType.SPECIAL_CONTRIBUTION);

        BigDecimal actual = MoneyUtils.add(regular, special);
        BigDecimal available = financialCalculationService.calculateAvailableGroupFund(cooperativeId);
        long pending = specialContributionRepository.countByCooperativeIdAndStatus(
                cooperativeId, SpecialContributionStatus.PENDING);

        BigDecimal totalLoanPrincipal = loanService.sumDisbursedPrincipal(cooperativeId);
        BigDecimal outstandingLoanPrincipal = loanService.sumOutstandingPrincipalActiveOverdue(cooperativeId);
        BigDecimal loanInterestEarned = financialCalculationService.sumApprovedCreditsByType(
                cooperativeId, LedgerTransactionType.LOAN_INTEREST_PAYMENT);
        // Read-only effective overdue count (ACTIVE past-due + OVERDUE) — no status mutation.
        long overdueLoansCount = loanRepository.countCurrentlyOverdue(cooperativeId, today);

        long totalFines = fineService.countTotal(cooperativeId);
        long unpaidFines = fineService.countUnpaid(cooperativeId);
        long paidFines = fineService.countPaid(cooperativeId);
        long membersWithFines = fineService.countMembersWithOpenFines(cooperativeId);
        BigDecimal approvedFineIncome = financialCalculationService.sumApprovedCreditsByType(
                cooperativeId, LedgerTransactionType.FINE_PAYMENT);
        long pendingFinePayments = fineService.countPendingPayments(cooperativeId);
        long approvedFinePayments =
                fineService.countPaymentsByStatus(cooperativeId, rw.terimbere.csams.modules.fine.entity.FinePaymentStatus.APPROVED);
        long rejectedFinePayments =
                fineService.countPaymentsByStatus(cooperativeId, rw.terimbere.csams.modules.fine.entity.FinePaymentStatus.REJECTED);

        BigDecimal socialContributionsTotal = socialFundBalanceService.sumApprovedContributions(cooperativeId);
        BigDecimal socialDisbursementsTotal = socialFundBalanceService.sumApprovedDisbursements(cooperativeId);
        BigDecimal socialFundBalance = socialFundBalanceService.calculateBalance(cooperativeId);
        long pendingSocialApprovals = socialFundService.countPendingApprovals(cooperativeId);

        long activeInvestmentsCount = investmentService.countActiveInvestments(cooperativeId);
        BigDecimal investmentCapital = financialCalculationService.sumActiveInvestmentCapital(cooperativeId);
        BigDecimal investmentProfits = financialCalculationService.sumApprovedCreditsByType(
                cooperativeId, LedgerTransactionType.INVESTMENT_PROFIT);
        BigDecimal otherIncomeTotal = financialCalculationService.sumApprovedCreditsByType(
                cooperativeId, LedgerTransactionType.OTHER_INCOME);
        BigDecimal generalExpensesTotal = financialCalculationService.sumApprovedDebitsByType(
                cooperativeId, LedgerTransactionType.GENERAL_EXPENSE);
        BigDecimal interestExpensesTotal = financialCalculationService.sumApprovedDebitsByType(
                cooperativeId, LedgerTransactionType.INTEREST_EXPENSE);
        BigDecimal availableInterest = financialCalculationService.calculateAvailableInterest(cooperativeId);

        long pendingPayoutsCount = payoutService.countPendingPreviews(cooperativeId);
        BigDecimal totalConfirmedPayouts = financialCalculationService.sumApprovedDebitsByType(
                cooperativeId, LedgerTransactionType.MEMBER_PAYOUT);

        return DashboardSummaryResponse.builder()
                .totalMembers(totalMembers)
                .activeMembers(activeMembers)
                .regularContributionsTotal(regular)
                .specialContributionsTotal(special)
                .actualContributionsTotal(actual)
                .availableGroupFunds(available)
                .pendingSpecialApprovals(pending)
                .totalLoanPrincipal(totalLoanPrincipal)
                .outstandingLoanPrincipal(outstandingLoanPrincipal)
                .loanInterestEarned(loanInterestEarned)
                .overdueLoansCount(overdueLoansCount)
                .totalFines(totalFines)
                .unpaidFines(unpaidFines)
                .paidFines(paidFines)
                .membersWithFines(membersWithFines)
                .approvedFineIncome(approvedFineIncome)
                .pendingFinePayments(pendingFinePayments)
                .approvedFinePayments(approvedFinePayments)
                .rejectedFinePayments(rejectedFinePayments)
                .socialFundBalance(socialFundBalance)
                .socialContributionsTotal(socialContributionsTotal)
                .socialDisbursementsTotal(socialDisbursementsTotal)
                .pendingSocialApprovals(pendingSocialApprovals)
                .activeInvestmentsCount(activeInvestmentsCount)
                .investmentCapital(investmentCapital)
                .investmentProfits(investmentProfits)
                .otherIncomeTotal(otherIncomeTotal)
                .generalExpensesTotal(generalExpensesTotal)
                .interestExpensesTotal(interestExpensesTotal)
                .availableInterest(availableInterest)
                .pendingPayoutsCount(pendingPayoutsCount)
                .totalConfirmedPayouts(totalConfirmedPayouts)
                .currency(cooperative.getCurrency())
                .build();
    }

    @Transactional(readOnly = true)
    public List<MonthlyContributionChartPoint> monthlyContributionsChart(UUID cooperativeId, int year) {
        return contributionService.monthlyChart(cooperativeId, year);
    }

    /**
     * Loans disbursed by calendar month for a selectable year ({@code disbursementDate}).
     *
     * <p>Restricted to officers with loan-insight access (not MEMBER/SECRETARY).
     */
    @Transactional(readOnly = true)
    public List<LoansDisbursedByMonthPoint> loansDisbursedByMonthChart(UUID cooperativeId, int year) {
        requireCooperative(cooperativeId);
        authorizationService.requireMemberLoanInsightsAccess(cooperativeId);
        validateChartYear(year);

        LocalDate fromDate = LocalDate.of(year, 1, 1);
        LocalDate toDate = LocalDate.of(year, 12, 31);
        Map<Integer, LoansDisbursedByMonthPoint> byMonth = new HashMap<>();
        for (int m = 1; m <= 12; m++) {
            byMonth.put(
                    m,
                    LoansDisbursedByMonthPoint.builder()
                            .month(m)
                            .loanCount(0)
                            .principalAmount(MoneyUtils.scale(BigDecimal.ZERO))
                            .build());
        }
        for (Object[] row :
                loanRepository.sumDisbursedGroupedByMonth(cooperativeId, fromDate, toDate, DISBURSED_STATUSES)) {
            int month = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            BigDecimal principal = scaleOrZero((BigDecimal) row[2]);
            byMonth.put(
                    month,
                    LoansDisbursedByMonthPoint.builder()
                            .month(month)
                            .loanCount(count)
                            .principalAmount(principal)
                            .build());
        }
        List<LoansDisbursedByMonthPoint> points = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            points.add(byMonth.get(m));
        }
        return points;
    }

    /**
     * Investment capital deployed by calendar month using {@code activatedAt} in the cooperative
     * timezone. Uses original {@code amount}, not remaining capital.
     *
     * <p>Restricted to finance leadership (not LOAN_OFFICER/MEMBER/SECRETARY).
     */
    @Transactional(readOnly = true)
    public List<InvestmentsByMonthPoint> investmentsByMonthChart(UUID cooperativeId, int year) {
        requireCooperative(cooperativeId);
        authorizationService.requireMemberFinanceInsightsAccess(cooperativeId);
        validateChartYear(year);

        String timezone = resolveTimezone(cooperativeId);
        ZoneId zone = ZoneId.of(timezone);
        List<InvestmentsByMonthPoint> points = new ArrayList<>(12);
        for (int month = 1; month <= 12; month++) {
            YearMonth ym = YearMonth.of(year, month);
            Instant fromInclusive = ym.atDay(1).atStartOfDay(zone).toInstant();
            Instant toExclusive = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
            List<Object[]> rows = investmentRepository.sumAmountAndCountActivatedBetween(
                    cooperativeId, fromInclusive, toExclusive, DEPLOYED_INVESTMENT_STATUSES);
            BigDecimal capital = MoneyUtils.scale(BigDecimal.ZERO);
            long count = 0L;
            if (rows != null && !rows.isEmpty() && rows.get(0) != null) {
                Object[] row = rows.get(0);
                capital = scaleOrZero(row[0] instanceof BigDecimal bd ? bd : null);
                count = row[1] == null ? 0L : ((Number) row[1]).longValue();
            }
            points.add(InvestmentsByMonthPoint.builder()
                    .month(month)
                    .capitalDeployed(capital)
                    .investmentCount(count)
                    .build());
        }
        return points;
    }

    /**
     * Advanced ranked insights: frequent borrowers (YTD) and largest active investments.
     *
     * <p>Role-split like C2: loan officers get frequent borrowers only; finance leadership also get
     * largest active investments.
     */
    @Transactional(readOnly = true)
    public DashboardAdvancedInsightsResponse advancedInsights(UUID cooperativeId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        authorizationService.requireMemberInsightsAccess(cooperativeId);

        UserPrincipal principal = authorizationService.currentPrincipal();
        boolean finance = CooperativeOfficerRoles.canViewMemberFinanceInsights(principal);
        boolean loans = CooperativeOfficerRoles.canViewMemberLoanInsights(principal);

        String timezone = resolveTimezone(cooperativeId);
        ZoneId zone = ZoneId.of(timezone);
        LocalDate today = LocalDate.now(zone);
        int year = today.getYear();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        var page = PageRequest.of(0, ADVANCED_INSIGHTS_LIMIT);

        List<DashboardAdvancedInsightsResponse.FrequentBorrowerRow> frequentBorrowers = List.of();
        List<DashboardAdvancedInsightsResponse.LargestActiveInvestmentRow> largestActiveInvestments =
                List.of();
        DashboardAdvancedInsightsResponse.RepaymentReliabilitySection repaymentReliability = null;

        if (loans) {
            List<Object[]> borrowerRows = loanRepository.countDisbursedGroupedByMemberOrdered(
                    cooperativeId, yearStart, today, DISBURSED_STATUSES, page);
            Set<UUID> nameIds = new HashSet<>();
            for (Object[] row : borrowerRows) {
                nameIds.add((UUID) row[0]);
            }

            List<InstallmentFact> reliabilityFacts = mapReliabilityFacts(
                    loanInstallmentRepository.findRepaymentReliabilityFacts(cooperativeId));
            List<RepaymentReliabilityAggregator.MemberTotals> reliabilityTotals =
                    RepaymentReliabilityAggregator.aggregateRanked(
                            reliabilityFacts,
                            today,
                            RepaymentReliabilityAggregator.DEFAULT_MINIMUM_SAMPLE,
                            ADVANCED_INSIGHTS_LIMIT);
            for (RepaymentReliabilityAggregator.MemberTotals totals : reliabilityTotals) {
                nameIds.add(totals.memberId());
            }
            Map<UUID, String> names = resolveDisplayNames(nameIds);

            frequentBorrowers = new ArrayList<>();
            int rank = 1;
            for (Object[] row : borrowerRows) {
                UUID memberId = (UUID) row[0];
                frequentBorrowers.add(DashboardAdvancedInsightsResponse.FrequentBorrowerRow.builder()
                        .memberId(memberId)
                        .displayName(names.getOrDefault(memberId, memberId.toString()))
                        .numberOfLoansDisbursed(((Number) row[1]).longValue())
                        .totalPrincipalBorrowed(scaleOrZero((BigDecimal) row[2]))
                        .rank(rank++)
                        .build());
            }

            long dataQualityExcludedTotal = 0L;
            for (InstallmentFact fact : reliabilityFacts) {
                if (RepaymentReliabilityClassifier.isDataQualityExcluded(fact, today)) {
                    dataQualityExcludedTotal++;
                }
            }
            List<DashboardAdvancedInsightsResponse.RepaymentReliabilityRow> reliabilityRows =
                    new ArrayList<>();
            rank = 1;
            for (RepaymentReliabilityAggregator.MemberTotals totals : reliabilityTotals) {
                reliabilityRows.add(DashboardAdvancedInsightsResponse.RepaymentReliabilityRow.builder()
                        .memberId(totals.memberId())
                        .displayName(names.getOrDefault(totals.memberId(), totals.memberId().toString()))
                        .installmentsDue(totals.installmentsDue())
                        .installmentsPaidOnTime(totals.installmentsPaidOnTime())
                        .installmentsPaidLate(totals.installmentsPaidLate())
                        .installmentsUnpaidPastDue(totals.installmentsUnpaidPastDue())
                        .dataQualityExcluded(totals.dataQualityExcluded())
                        .onTimeRate(totals.onTimeRate())
                        .rank(rank++)
                        .build());
            }
            repaymentReliability = DashboardAdvancedInsightsResponse.RepaymentReliabilitySection.builder()
                    .period(RepaymentReliabilityAggregator.PERIOD_LIFETIME)
                    .minimumSample(RepaymentReliabilityAggregator.DEFAULT_MINIMUM_SAMPLE)
                    .dataQualityExcludedTotal(dataQualityExcludedTotal)
                    .members(reliabilityRows)
                    .build();
        }

        if (finance) {
            List<Object[]> investmentRows = investmentRepository.findLargestByRemainingCapital(
                    cooperativeId, ACTIVE_INVESTMENT_STATUSES, page);
            largestActiveInvestments = new ArrayList<>();
            int rank = 1;
            for (Object[] row : investmentRows) {
                largestActiveInvestments.add(
                        DashboardAdvancedInsightsResponse.LargestActiveInvestmentRow.builder()
                                .investmentId((UUID) row[0])
                                .name((String) row[1])
                                .originalCapital(scaleOrZero((BigDecimal) row[2]))
                                .remainingCapital(scaleOrZero((BigDecimal) row[3]))
                                .profitReturned(scaleOrZero((BigDecimal) row[4]))
                                .status((InvestmentStatus) row[5])
                                .rank(rank++)
                                .build());
            }
        }

        return DashboardAdvancedInsightsResponse.builder()
                .period(DashboardAdvancedInsightsResponse.Period.builder()
                        .year(year)
                        .asOf(today)
                        .build())
                .currency(cooperative.getCurrency())
                .timezone(timezone)
                .largestActiveInvestments(largestActiveInvestments)
                .frequentBorrowers(frequentBorrowers)
                .repaymentReliability(repaymentReliability)
                .build();
    }

    private static List<InstallmentFact> mapReliabilityFacts(List<Object[]> rows) {
        List<InstallmentFact> facts = new ArrayList<>();
        if (rows == null) {
            return facts;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 11) {
                continue;
            }
            facts.add(new InstallmentFact(
                    (UUID) row[0],
                    (UUID) row[1],
                    (LocalDate) row[2],
                    (LoanInstallmentStatus) row[3],
                    (BigDecimal) row[4],
                    (BigDecimal) row[5],
                    (BigDecimal) row[6],
                    (BigDecimal) row[7],
                    (BigDecimal) row[8],
                    (BigDecimal) row[9],
                    (LocalDate) row[10]));
        }
        return facts;
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private static void validateChartYear(int year) {
        if (year < 2000 || year > 2100) {
            throw new ValidationException("year must be between 2000 and 2100");
        }
    }

    /**
     * Period analytics for the current vs previous calendar month in the cooperative timezone.
     *
     * <p>Contributions use contribution-period year/month ({@code sumPaidForPeriod}) — same basis as
     * the monthly contributions chart. Loans use {@code disbursementDate}; repayments use total
     * repayment cash ({@code amountTotal}); fines issued use {@code issuedDate}; fines collected use
     * APPROVED fine payments by {@code paymentDate}.
     */
    @Transactional(readOnly = true)
    public DashboardInsightsResponse insights(UUID cooperativeId) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        authorizationService.requireMembership(cooperativeId);

        String timezone = resolveTimezone(cooperativeId);
        ZoneId zone = ZoneId.of(timezone);
        YearMonth currentMonth = YearMonth.from(LocalDate.now(zone));
        YearMonth previousMonth = currentMonth.minusMonths(1);
        LocalDate currentStart = currentMonth.atDay(1);
        LocalDate currentEnd = currentMonth.atEndOfMonth();
        LocalDate previousStart = previousMonth.atDay(1);
        LocalDate previousEnd = previousMonth.atEndOfMonth();

        BigDecimal contributionsCurrent = scaleOrZero(
                contributionRepository.sumPaidForPeriod(cooperativeId, currentMonth.getYear(), currentMonth.getMonthValue()));
        BigDecimal contributionsPrevious = scaleOrZero(
                contributionRepository.sumPaidForPeriod(
                        cooperativeId, previousMonth.getYear(), previousMonth.getMonthValue()));
        var contributionsMom = MonthOverMonthCalculator.of(contributionsCurrent, contributionsPrevious);

        long loansIssuedCountCurrent = loanRepository.countDisbursedInDateRange(
                cooperativeId, currentStart, currentEnd, DISBURSED_STATUSES);
        BigDecimal loansIssuedAmountCurrent = scaleOrZero(loanRepository.sumDisbursedPrincipalInDateRange(
                cooperativeId, currentStart, currentEnd, DISBURSED_STATUSES));
        long loansIssuedCountPrevious = loanRepository.countDisbursedInDateRange(
                cooperativeId, previousStart, previousEnd, DISBURSED_STATUSES);
        BigDecimal loansIssuedAmountPrevious = scaleOrZero(loanRepository.sumDisbursedPrincipalInDateRange(
                cooperativeId, previousStart, previousEnd, DISBURSED_STATUSES));
        var loansIssuedMom = MonthOverMonthCalculator.of(loansIssuedAmountCurrent, loansIssuedAmountPrevious);

        BigDecimal repaidCurrent =
                scaleOrZero(loanRepaymentRepository.sumAmountTotalInDateRange(cooperativeId, currentStart, currentEnd));
        BigDecimal repaidPrevious = scaleOrZero(
                loanRepaymentRepository.sumAmountTotalInDateRange(cooperativeId, previousStart, previousEnd));
        var repaidMom = MonthOverMonthCalculator.of(repaidCurrent, repaidPrevious);

        BigDecimal outstandingPrincipal = loanService.sumOutstandingPrincipalActiveOverdue(cooperativeId);

        long finesIssuedCount =
                fineRepository.countIssuedInDateRange(cooperativeId, currentStart, currentEnd);
        BigDecimal finesIssuedAmount =
                scaleOrZero(fineRepository.sumIssuedAmountInDateRange(cooperativeId, currentStart, currentEnd));
        BigDecimal finesCollectedCurrent = scaleOrZero(
                finePaymentRepository.sumApprovedAmountInDateRange(cooperativeId, currentStart, currentEnd));
        BigDecimal finesCollectedPrevious = scaleOrZero(
                finePaymentRepository.sumApprovedAmountInDateRange(cooperativeId, previousStart, previousEnd));
        var finesCollectedMom = MonthOverMonthCalculator.of(finesCollectedCurrent, finesCollectedPrevious);

        return DashboardInsightsResponse.builder()
                .period(DashboardInsightsResponse.Period.builder()
                        .year(currentMonth.getYear())
                        .month(currentMonth.getMonthValue())
                        .label(currentMonth.toString())
                        .previousYear(previousMonth.getYear())
                        .previousMonth(previousMonth.getMonthValue())
                        .build())
                .contributions(DashboardInsightsResponse.ContributionsInsights.builder()
                        .currentMonth(contributionsMom.current())
                        .previousMonth(contributionsMom.previous())
                        .changePercent(contributionsMom.changePercent())
                        .changeState(contributionsMom.changeState())
                        .build())
                .loans(DashboardInsightsResponse.LoansInsights.builder()
                        .issuedCountCurrentMonth(loansIssuedCountCurrent)
                        .issuedAmountCurrentMonth(loansIssuedMom.current())
                        .issuedCountPreviousMonth(loansIssuedCountPrevious)
                        .issuedAmountPreviousMonth(loansIssuedMom.previous())
                        .issuedAmountChangePercent(loansIssuedMom.changePercent())
                        .issuedAmountChangeState(loansIssuedMom.changeState())
                        .repaidCurrentMonth(repaidMom.current())
                        .repaidPreviousMonth(repaidMom.previous())
                        .repaidChangePercent(repaidMom.changePercent())
                        .repaidChangeState(repaidMom.changeState())
                        .outstandingPrincipal(outstandingPrincipal)
                        .build())
                .fines(DashboardInsightsResponse.FinesInsights.builder()
                        .issuedCountCurrentMonth(finesIssuedCount)
                        .issuedAmountCurrentMonth(finesIssuedAmount)
                        .collectedCurrentMonth(finesCollectedMom.current())
                        .collectedPreviousMonth(finesCollectedMom.previous())
                        .collectedChangePercent(finesCollectedMom.changePercent())
                        .collectedChangeState(finesCollectedMom.changeState())
                        .build())
                .currency(cooperative.getCurrency())
                .timezone(timezone)
                .build();
    }

    private static final int MEMBER_INSIGHTS_LIMIT = 5;

    /**
     * Officer-only member-identifying insights (YTD contributors, fine follow-up, overdue loans).
     *
     * <p>Finance leadership (president/VP/accountant/super-admin) see all lists. Loan officers see
     * overdue loans only. Members and secretaries are denied.
     *
     * <p>Read-only: does not call {@code refreshOverdueStatuses} or otherwise mutate operational
     * entities. Overdue loans are derived with {@link
     * rw.terimbere.csams.modules.loan.support.LoanOverdueRules}.
     */
    @Transactional(readOnly = true)
    public DashboardMemberInsightsResponse memberInsights(UUID cooperativeId) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        authorizationService.requireMemberInsightsAccess(cooperativeId);

        UserPrincipal principal = authorizationService.currentPrincipal();
        boolean finance = CooperativeOfficerRoles.canViewMemberFinanceInsights(principal);
        boolean loans = CooperativeOfficerRoles.canViewMemberLoanInsights(principal);

        String timezone = resolveTimezone(cooperativeId);
        ZoneId zone = ZoneId.of(timezone);
        LocalDate today = LocalDate.now(zone);
        LocalDate periodStart = LocalDate.of(today.getYear(), 1, 1);
        LocalDate periodEnd = today;
        var page = PageRequest.of(0, MEMBER_INSIGHTS_LIMIT);

        List<DashboardMemberInsightsResponse.TopContributorRow> topContributors = List.of();
        List<DashboardMemberInsightsResponse.FineFollowUpRow> fineFollowUp = List.of();
        List<DashboardMemberInsightsResponse.OverdueLoanRow> overdueLoans = List.of();
        Set<UUID> nameIds = new HashSet<>();

        List<Object[]> contributorRows = List.of();
        List<Object[]> fineRows = List.of();
        List<Object[]> overdueRows = List.of();

        if (finance) {
            contributorRows = contributionRepository.sumPaidGroupedByMemberInDateRangeOrdered(
                    cooperativeId, periodStart, periodEnd, page);
            fineRows = fineRepository.sumFineFollowUpGroupedByMemberOrdered(
                    cooperativeId, periodStart, periodEnd, page);
            for (Object[] row : contributorRows) {
                nameIds.add((UUID) row[0]);
            }
            for (Object[] row : fineRows) {
                nameIds.add((UUID) row[0]);
            }
        }
        if (loans) {
            // Read-only: derive overdue from dates/status without mutating Loan.status.
            overdueRows = loanRepository.sumOverdueGroupedByMemberOrdered(cooperativeId, today, page);
            for (Object[] row : overdueRows) {
                nameIds.add((UUID) row[0]);
            }
        }

        Map<UUID, String> names = resolveDisplayNames(nameIds);

        if (finance) {
            topContributors = new ArrayList<>();
            int rank = 1;
            for (Object[] row : contributorRows) {
                UUID memberId = (UUID) row[0];
                topContributors.add(DashboardMemberInsightsResponse.TopContributorRow.builder()
                        .memberId(memberId)
                        .displayName(names.getOrDefault(memberId, memberId.toString()))
                        .amount(scaleOrZero((BigDecimal) row[1]))
                        .rank(rank++)
                        .build());
            }

            fineFollowUp = new ArrayList<>();
            rank = 1;
            for (Object[] row : fineRows) {
                UUID memberId = (UUID) row[0];
                fineFollowUp.add(DashboardMemberInsightsResponse.FineFollowUpRow.builder()
                        .memberId(memberId)
                        .displayName(names.getOrDefault(memberId, memberId.toString()))
                        .issuedAmount(scaleOrZero((BigDecimal) row[1]))
                        .paidAmount(scaleOrZero((BigDecimal) row[2]))
                        .outstandingAmount(scaleOrZero((BigDecimal) row[3]))
                        .fineCount(((Number) row[4]).longValue())
                        .rank(rank++)
                        .build());
            }
        }

        if (loans) {
            overdueLoans = new ArrayList<>();
            int rank = 1;
            for (Object[] row : overdueRows) {
                UUID memberId = (UUID) row[0];
                overdueLoans.add(DashboardMemberInsightsResponse.OverdueLoanRow.builder()
                        .memberId(memberId)
                        .displayName(names.getOrDefault(memberId, memberId.toString()))
                        .overdueLoanCount(((Number) row[1]).longValue())
                        .outstandingPrincipal(scaleOrZero((BigDecimal) row[2]))
                        .oldestDueDate((LocalDate) row[3])
                        .rank(rank++)
                        .build());
            }
        }

        return DashboardMemberInsightsResponse.builder()
                .period(DashboardMemberInsightsResponse.Period.builder()
                        .start(periodStart)
                        .end(periodEnd)
                        .build())
                .currency(cooperative.getCurrency())
                .timezone(timezone)
                .topContributors(topContributors)
                .fineFollowUp(fineFollowUp)
                .overdueLoans(overdueLoans)
                .build();
    }

    private Map<UUID, String> resolveDisplayNames(Set<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        for (User user : userRepository.findAllById(userIds)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    private String resolveTimezone(UUID cooperativeId) {
        return cooperativeSettingsRepository
                .findByCooperativeId(cooperativeId)
                .map(CooperativeSettings::getTimezone)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_TIMEZONE);
    }

    private static BigDecimal scaleOrZero(BigDecimal value) {
        return MoneyUtils.scale(value == null ? BigDecimal.ZERO : value);
    }

    @Transactional(readOnly = true)
    public PlatformOverviewResponse platformOverview() {
        authorizationService.requireSuperAdmin();
        return PlatformOverviewResponse.builder()
                .totalCooperatives(cooperativeRepository.countByDeletedFalse())
                .activeCooperatives(cooperativeRepository.countByDeletedFalseAndStatus(CooperativeStatus.ACTIVE))
                .inactiveCooperatives(cooperativeRepository.countByDeletedFalseAndStatus(CooperativeStatus.INACTIVE))
                .suspendedCooperatives(cooperativeRepository.countByDeletedFalseAndStatus(CooperativeStatus.SUSPENDED))
                .archivedCooperatives(cooperativeRepository.countByDeletedFalseAndStatus(CooperativeStatus.ARCHIVED))
                .totalMembers(membershipRepository.count())
                .activeMembers(membershipRepository.countByMembershipStatus("ACTIVE"))
                .totalUsers(userRepository.countByDeletedFalse())
                .pendingContributionReviews(contributionRepository.countByReviewStatus(ContributionReviewStatus.PENDING))
                .pendingSpecialContributions(
                        specialContributionRepository.countByStatus(SpecialContributionStatus.PENDING))
                .pendingLoans(
                        loanRepository.countByStatus(LoanStatus.PENDING)
                                + loanRepository.countByStatus(LoanStatus.AWAITING_SECOND_APPROVAL))
                .overdueLoans(loanRepository.countByStatus(LoanStatus.OVERDUE))
                .pendingFinePayments(finePaymentRepository.countByStatus(FinePaymentStatus.PENDING))
                .pendingSocialContributions(
                        socialContributionRepository.countByStatus(SocialContributionStatus.PENDING))
                .pendingPayouts(
                        payoutRunRepository.countByStatus(PayoutRunStatus.DRAFT)
                                + payoutRunRepository.countByStatus(PayoutRunStatus.PREVIEWED))
                .build();
    }
}
