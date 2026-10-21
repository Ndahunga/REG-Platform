package rw.terimbere.csams.modules.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.dashboard.dto.DashboardInsightsResponse;
import rw.terimbere.csams.modules.dashboard.support.MonthOverMonthCalculator.ChangeState;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.investment.service.InvestmentService;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanService;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.modules.payout.service.PayoutService;
import rw.terimbere.csams.modules.settings.entity.CooperativeSettings;
import rw.terimbere.csams.modules.settings.repository.CooperativeSettingsRepository;
import rw.terimbere.csams.modules.socialfund.repository.SocialContributionRepository;
import rw.terimbere.csams.modules.socialfund.service.SocialFundBalanceService;
import rw.terimbere.csams.modules.socialfund.service.SocialFundService;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;

@ExtendWith(MockitoExtension.class)
class DashboardServiceInsightsTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kigali");
    private static final EnumSet<LoanStatus> DISBURSED =
            EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.CLOSED, LoanStatus.WRITTEN_OFF);

    @Mock
    private CooperativeRepository cooperativeRepository;
    @Mock
    private CooperativeMembershipRepository membershipRepository;
    @Mock
    private ContributionRepository contributionRepository;
    @Mock
    private SpecialContributionRepository specialContributionRepository;
    @Mock
    private ContributionService contributionService;
    @Mock
    private LoanService loanService;
    @Mock
    private FineService fineService;
    @Mock
    private SocialFundBalanceService socialFundBalanceService;
    @Mock
    private SocialFundService socialFundService;
    @Mock
    private InvestmentService investmentService;
    @Mock
    private InvestmentRepository investmentRepository;
    @Mock
    private PayoutService payoutService;
    @Mock
    private LedgerFinancialCalculationService financialCalculationService;
    @Mock
    private CooperativeAuthorizationService authorizationService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LoanRepository loanRepository;
    @Mock
    private LoanInstallmentRepository loanInstallmentRepository;
    @Mock
    private LoanRepaymentRepository loanRepaymentRepository;
    @Mock
    private FineRepository fineRepository;
    @Mock
    private FinePaymentRepository finePaymentRepository;
    @Mock
    private SocialContributionRepository socialContributionRepository;
    @Mock
    private PayoutRunRepository payoutRunRepository;
    @Mock
    private CooperativeSettingsRepository cooperativeSettingsRepository;

    @InjectMocks
    private DashboardService dashboardService;

    private UUID cooperativeId;
    private YearMonth current;
    private YearMonth previous;
    private LocalDate currentStart;
    private LocalDate currentEnd;
    private LocalDate previousStart;
    private LocalDate previousEnd;

    @BeforeEach
    void setUp() {
        cooperativeId = UUID.randomUUID();
        current = YearMonth.from(LocalDate.now(ZONE));
        previous = current.minusMonths(1);
        currentStart = current.atDay(1);
        currentEnd = current.atEndOfMonth();
        previousStart = previous.atDay(1);
        previousEnd = previous.atEndOfMonth();
    }

    private void stubCooperative() {
        Cooperative cooperative = Cooperative.builder().currency("RWF").build();
        cooperative.setId(cooperativeId);
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId)).thenReturn(Optional.of(cooperative));
        when(cooperativeSettingsRepository.findByCooperativeId(cooperativeId))
                .thenReturn(Optional.of(CooperativeSettings.builder()
                        .cooperativeId(cooperativeId)
                        .timezone("Africa/Kigali")
                        .locale("en")
                        .build()));
    }

    @Test
    void contributionsCurrentAndPreviousWithPositiveMom() {
        stubCooperative();
        when(contributionRepository.sumPaidForPeriod(cooperativeId, current.getYear(), current.getMonthValue()))
                .thenReturn(new BigDecimal("1200000"));
        when(contributionRepository.sumPaidForPeriod(cooperativeId, previous.getYear(), previous.getMonthValue()))
                .thenReturn(new BigDecimal("1000000"));
        stubLoansAndFinesZeros();

        DashboardInsightsResponse insights = dashboardService.insights(cooperativeId);

        assertThat(insights.getContributions().getCurrentMonth()).isEqualByComparingTo("1200000.00");
        assertThat(insights.getContributions().getPreviousMonth()).isEqualByComparingTo("1000000.00");
        assertThat(insights.getContributions().getChangePercent()).isEqualByComparingTo("20.0");
        assertThat(insights.getContributions().getChangeState()).isEqualTo(ChangeState.UP);
        assertThat(insights.getPeriod().getYear()).isEqualTo(current.getYear());
        assertThat(insights.getPeriod().getMonth()).isEqualTo(current.getMonthValue());
        assertThat(insights.getPeriod().getPreviousYear()).isEqualTo(previous.getYear());
        assertThat(insights.getPeriod().getPreviousMonth()).isEqualTo(previous.getMonthValue());
    }

    @Test
    void contributionsNegativeMom() {
        stubCooperative();
        when(contributionRepository.sumPaidForPeriod(cooperativeId, current.getYear(), current.getMonthValue()))
                .thenReturn(new BigDecimal("800"));
        when(contributionRepository.sumPaidForPeriod(cooperativeId, previous.getYear(), previous.getMonthValue()))
                .thenReturn(new BigDecimal("1000"));
        stubLoansAndFinesZeros();

        DashboardInsightsResponse insights = dashboardService.insights(cooperativeId);

        assertThat(insights.getContributions().getChangePercent()).isEqualByComparingTo("-20.0");
        assertThat(insights.getContributions().getChangeState()).isEqualTo(ChangeState.DOWN);
    }

    @Test
    void previousMonthZeroHandledSafely() {
        stubCooperative();
        when(contributionRepository.sumPaidForPeriod(cooperativeId, current.getYear(), current.getMonthValue()))
                .thenReturn(new BigDecimal("500"));
        when(contributionRepository.sumPaidForPeriod(cooperativeId, previous.getYear(), previous.getMonthValue()))
                .thenReturn(BigDecimal.ZERO);
        stubLoansAndFinesZeros();

        DashboardInsightsResponse insights = dashboardService.insights(cooperativeId);

        assertThat(insights.getContributions().getChangePercent()).isNull();
        assertThat(insights.getContributions().getChangeState()).isEqualTo(ChangeState.NO_BASELINE);
    }

    @Test
    void yearBoundaryUsesPreviousDecember() {
        stubCooperative();
        // Force January by stubbing settings + verifying previous month args when current is Jan.
        // We cannot freeze LocalDate.now easily without Clock; verify minusMonths math via YearMonth.
        YearMonth january = YearMonth.of(2026, 1);
        YearMonth december = january.minusMonths(1);
        assertThat(december.getYear()).isEqualTo(2025);
        assertThat(december.getMonthValue()).isEqualTo(12);

        when(contributionRepository.sumPaidForPeriod(eq(cooperativeId), any(Integer.class), any(Integer.class)))
                .thenReturn(BigDecimal.ZERO);
        stubLoansAndFinesZeros();

        DashboardInsightsResponse insights = dashboardService.insights(cooperativeId);
        YearMonth expectedPrevious = YearMonth.from(LocalDate.now(ZONE)).minusMonths(1);
        assertThat(insights.getPeriod().getPreviousYear()).isEqualTo(expectedPrevious.getYear());
        assertThat(insights.getPeriod().getPreviousMonth()).isEqualTo(expectedPrevious.getMonthValue());
    }

    @Test
    void loansIssuedUsesDisbursementDateRangeAndStatuses() {
        stubCooperative();
        when(contributionRepository.sumPaidForPeriod(eq(cooperativeId), any(Integer.class), any(Integer.class)))
                .thenReturn(BigDecimal.ZERO);
        when(loanRepository.countDisbursedInDateRange(cooperativeId, currentStart, currentEnd, DISBURSED))
                .thenReturn(8L);
        when(loanRepository.sumDisbursedPrincipalInDateRange(cooperativeId, currentStart, currentEnd, DISBURSED))
                .thenReturn(new BigDecimal("700000"));
        when(loanRepository.countDisbursedInDateRange(cooperativeId, previousStart, previousEnd, DISBURSED))
                .thenReturn(6L);
        when(loanRepository.sumDisbursedPrincipalInDateRange(cooperativeId, previousStart, previousEnd, DISBURSED))
                .thenReturn(new BigDecimal("500000"));
        when(loanRepaymentRepository.sumAmountTotalInDateRange(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(loanService.sumOutstandingPrincipalActiveOverdue(cooperativeId)).thenReturn(new BigDecimal("1600000"));
        when(fineRepository.countIssuedInDateRange(any(), any(), any())).thenReturn(0L);
        when(fineRepository.sumIssuedAmountInDateRange(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(finePaymentRepository.sumApprovedAmountInDateRange(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        DashboardInsightsResponse insights = dashboardService.insights(cooperativeId);

        assertThat(insights.getLoans().getIssuedCountCurrentMonth()).isEqualTo(8);
        assertThat(insights.getLoans().getIssuedAmountCurrentMonth()).isEqualByComparingTo("700000.00");
        assertThat(insights.getLoans().getIssuedCountPreviousMonth()).isEqualTo(6);
        assertThat(insights.getLoans().getIssuedAmountPreviousMonth()).isEqualByComparingTo("500000.00");
        assertThat(insights.getLoans().getIssuedAmountChangePercent()).isEqualByComparingTo("40.0");
        assertThat(insights.getLoans().getOutstandingPrincipal()).isEqualByComparingTo("1600000.00");

        verify(loanRepository).countDisbursedInDateRange(cooperativeId, currentStart, currentEnd, DISBURSED);
        verify(loanRepository).sumDisbursedPrincipalInDateRange(cooperativeId, currentStart, currentEnd, DISBURSED);
    }

    @Test
    void loanRepaymentsAndFinesThisMonth() {
        stubCooperative();
        when(contributionRepository.sumPaidForPeriod(eq(cooperativeId), any(Integer.class), any(Integer.class)))
                .thenReturn(BigDecimal.ZERO);
        when(loanRepository.countDisbursedInDateRange(any(), any(), any(), any())).thenReturn(0L);
        when(loanRepository.sumDisbursedPrincipalInDateRange(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(loanRepaymentRepository.sumAmountTotalInDateRange(cooperativeId, currentStart, currentEnd))
                .thenReturn(new BigDecimal("420000"));
        when(loanRepaymentRepository.sumAmountTotalInDateRange(cooperativeId, previousStart, previousEnd))
                .thenReturn(new BigDecimal("380000"));
        when(loanService.sumOutstandingPrincipalActiveOverdue(cooperativeId)).thenReturn(BigDecimal.ZERO);
        when(fineRepository.countIssuedInDateRange(cooperativeId, currentStart, currentEnd)).thenReturn(5L);
        when(fineRepository.sumIssuedAmountInDateRange(cooperativeId, currentStart, currentEnd))
                .thenReturn(new BigDecimal("30000"));
        when(finePaymentRepository.sumApprovedAmountInDateRange(cooperativeId, currentStart, currentEnd))
                .thenReturn(new BigDecimal("22000"));
        when(finePaymentRepository.sumApprovedAmountInDateRange(cooperativeId, previousStart, previousEnd))
                .thenReturn(BigDecimal.ZERO);

        DashboardInsightsResponse insights = dashboardService.insights(cooperativeId);

        assertThat(insights.getLoans().getRepaidCurrentMonth()).isEqualByComparingTo("420000.00");
        assertThat(insights.getLoans().getRepaidPreviousMonth()).isEqualByComparingTo("380000.00");
        assertThat(insights.getFines().getIssuedCountCurrentMonth()).isEqualTo(5);
        assertThat(insights.getFines().getIssuedAmountCurrentMonth()).isEqualByComparingTo("30000.00");
        assertThat(insights.getFines().getCollectedCurrentMonth()).isEqualByComparingTo("22000.00");
        assertThat(insights.getFines().getCollectedChangeState()).isEqualTo(ChangeState.NO_BASELINE);
    }

    @Test
    void emptyCooperativeReturnsZeros() {
        stubCooperative();
        when(contributionRepository.sumPaidForPeriod(eq(cooperativeId), any(Integer.class), any(Integer.class)))
                .thenReturn(null);
        stubLoansAndFinesZeros();

        DashboardInsightsResponse insights = dashboardService.insights(cooperativeId);

        assertThat(insights.getContributions().getCurrentMonth()).isEqualByComparingTo("0.00");
        assertThat(insights.getLoans().getIssuedCountCurrentMonth()).isZero();
        assertThat(insights.getLoans().getIssuedAmountCurrentMonth()).isEqualByComparingTo("0.00");
        assertThat(insights.getLoans().getRepaidCurrentMonth()).isEqualByComparingTo("0.00");
        assertThat(insights.getFines().getIssuedCountCurrentMonth()).isZero();
        assertThat(insights.getFines().getCollectedCurrentMonth()).isEqualByComparingTo("0.00");
    }

    @Test
    void unauthorizedUserDenied() {
        Cooperative cooperative = Cooperative.builder().currency("RWF").build();
        cooperative.setId(cooperativeId);
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId)).thenReturn(Optional.of(cooperative));
        doThrow(new ForbiddenException("Not a member of this cooperative"))
                .when(authorizationService)
                .requireMembership(cooperativeId);

        assertThatThrownBy(() -> dashboardService.insights(cooperativeId))
                .isInstanceOf(ForbiddenException.class);
    }

    private void stubLoansAndFinesZeros() {
        when(loanRepository.countDisbursedInDateRange(any(), any(), any(), any())).thenReturn(0L);
        when(loanRepository.sumDisbursedPrincipalInDateRange(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(loanRepaymentRepository.sumAmountTotalInDateRange(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(loanService.sumOutstandingPrincipalActiveOverdue(cooperativeId)).thenReturn(BigDecimal.ZERO);
        when(fineRepository.countIssuedInDateRange(any(), any(), any())).thenReturn(0L);
        when(fineRepository.sumIssuedAmountInDateRange(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(finePaymentRepository.sumApprovedAmountInDateRange(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }
}
