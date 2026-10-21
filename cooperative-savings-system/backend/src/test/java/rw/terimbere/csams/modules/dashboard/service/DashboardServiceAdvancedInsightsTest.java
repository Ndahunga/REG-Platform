package rw.terimbere.csams.modules.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.dashboard.dto.DashboardAdvancedInsightsResponse;
import rw.terimbere.csams.modules.dashboard.dto.InvestmentsByMonthPoint;
import rw.terimbere.csams.modules.dashboard.dto.LoansDisbursedByMonthPoint;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
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
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;

@ExtendWith(MockitoExtension.class)
class DashboardServiceAdvancedInsightsTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kigali");
    private static final EnumSet<LoanStatus> DISBURSED = EnumSet.of(
            LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.CLOSED, LoanStatus.WRITTEN_OFF);
    private static final EnumSet<InvestmentStatus> DEPLOYED = EnumSet.of(
            InvestmentStatus.ACTIVE,
            InvestmentStatus.PARTIALLY_RETURNED,
            InvestmentStatus.COMPLETED,
            InvestmentStatus.LOSS_RECORDED);
    private static final EnumSet<InvestmentStatus> ACTIVE = EnumSet.of(
            InvestmentStatus.ACTIVE, InvestmentStatus.PARTIALLY_RETURNED);

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
    private UUID memberA;
    private UUID memberB;
    private LocalDate today;
    private LocalDate yearStart;

    @BeforeEach
    void setUp() {
        cooperativeId = UUID.randomUUID();
        memberA = UUID.randomUUID();
        memberB = UUID.randomUUID();
        today = LocalDate.now(ZONE);
        yearStart = LocalDate.of(today.getYear(), 1, 1);
    }

    @Test
    void loansDisbursedByMonth_fillsTwelveMonthsAndMapsAggregation() {
        stubCooperative();
        when(loanRepository.sumDisbursedGroupedByMonth(
                        eq(cooperativeId),
                        eq(LocalDate.of(2026, 1, 1)),
                        eq(LocalDate.of(2026, 12, 31)),
                        eq(DISBURSED)))
                .thenReturn(List.of(
                        new Object[] {3, 4L, new BigDecimal("500000")},
                        new Object[] {12, 1L, new BigDecimal("100000")}));

        List<LoansDisbursedByMonthPoint> points =
                dashboardService.loansDisbursedByMonthChart(cooperativeId, 2026);

        assertThat(points).hasSize(12);
        assertThat(points.get(0).getMonth()).isEqualTo(1);
        assertThat(points.get(0).getLoanCount()).isZero();
        assertThat(points.get(2).getLoanCount()).isEqualTo(4);
        assertThat(points.get(2).getPrincipalAmount()).isEqualByComparingTo("500000.00");
        assertThat(points.get(11).getLoanCount()).isEqualTo(1);
        verify(authorizationService).requireMemberLoanInsightsAccess(cooperativeId);
    }

    @Test
    void loansDisbursedByMonth_forbiddenWithoutLoanInsights() {
        stubCooperative();
        doThrow(new ForbiddenException("denied"))
                .when(authorizationService)
                .requireMemberLoanInsightsAccess(cooperativeId);

        assertThatThrownBy(() -> dashboardService.loansDisbursedByMonthChart(cooperativeId, 2026))
                .isInstanceOf(ForbiddenException.class);
        verify(loanRepository, never()).sumDisbursedGroupedByMonth(any(), any(), any(), any());
    }

    @Test
    void investmentsByMonth_usesActivatedWindowAndOriginalAmount() {
        stubCooperative();
        when(cooperativeSettingsRepository.findByCooperativeId(cooperativeId))
                .thenReturn(Optional.of(CooperativeSettings.builder()
                        .cooperativeId(cooperativeId)
                        .timezone("Africa/Kigali")
                        .build()));
        when(investmentRepository.sumAmountAndCountActivatedBetween(any(), any(), any(), eq(DEPLOYED)))
                .thenReturn(List.<Object[]>of(new Object[] {BigDecimal.ZERO, 0L}));
        Instant juneStart = LocalDate.of(2026, 6, 1).atStartOfDay(ZONE).toInstant();
        Instant julyStart = LocalDate.of(2026, 7, 1).atStartOfDay(ZONE).toInstant();
        when(investmentRepository.sumAmountAndCountActivatedBetween(
                        eq(cooperativeId), eq(juneStart), eq(julyStart), eq(DEPLOYED)))
                .thenReturn(List.<Object[]>of(new Object[] {new BigDecimal("2500000"), 2L}));

        List<InvestmentsByMonthPoint> points =
                dashboardService.investmentsByMonthChart(cooperativeId, 2026);

        assertThat(points).hasSize(12);
        assertThat(points.get(5).getCapitalDeployed()).isEqualByComparingTo("2500000.00");
        assertThat(points.get(5).getInvestmentCount()).isEqualTo(2);
        assertThat(points.get(0).getInvestmentCount()).isZero();
        verify(authorizationService).requireMemberFinanceInsightsAccess(cooperativeId);
    }

    @Test
    void investmentsByMonth_loanOfficerForbidden() {
        stubCooperative();
        doThrow(new ForbiddenException("denied"))
                .when(authorizationService)
                .requireMemberFinanceInsightsAccess(cooperativeId);

        assertThatThrownBy(() -> dashboardService.investmentsByMonthChart(cooperativeId, 2026))
                .isInstanceOf(ForbiddenException.class);
        verify(investmentRepository, never()).sumAmountAndCountActivatedBetween(any(), any(), any(), any());
    }

    @Test
    void advancedInsights_frequentBorrowersOrderedWithNames() {
        stubCooperativeAndPresident();
        when(loanRepository.countDisbursedGroupedByMemberOrdered(
                        eq(cooperativeId), eq(yearStart), eq(today), eq(DISBURSED), any(Pageable.class)))
                .thenReturn(List.of(
                        new Object[] {memberA, 5L, new BigDecimal("900000")},
                        new Object[] {memberB, 4L, new BigDecimal("700000")}));
        when(loanInstallmentRepository.findRepaymentReliabilityFacts(cooperativeId)).thenReturn(List.of());
        when(investmentRepository.findLargestByRemainingCapital(
                        eq(cooperativeId), eq(ACTIVE), any(Pageable.class)))
                .thenReturn(List.of());
        stubNames(memberA, "Jane Doe", memberB, "Eric N.");

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        DashboardAdvancedInsightsResponse response = dashboardService.advancedInsights(cooperativeId);
        verify(loanRepository)
                .countDisbursedGroupedByMemberOrdered(
                        eq(cooperativeId), eq(yearStart), eq(today), eq(DISBURSED), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(5);

        assertThat(response.getFrequentBorrowers()).hasSize(2);
        assertThat(response.getFrequentBorrowers().get(0).getDisplayName()).isEqualTo("Jane Doe");
        assertThat(response.getFrequentBorrowers().get(0).getNumberOfLoansDisbursed()).isEqualTo(5);
        assertThat(response.getFrequentBorrowers().get(0).getTotalPrincipalBorrowed())
                .isEqualByComparingTo("900000.00");
        assertThat(response.getFrequentBorrowers().get(0).getRank()).isEqualTo(1);
        assertThat(response.getPeriod().getYear()).isEqualTo(today.getYear());
        assertThat(response.getPeriod().getAsOf()).isEqualTo(today);
    }

    @Test
    void advancedInsights_largestInvestmentsOrderedByRemaining() {
        stubCooperativeAndPresident();
        UUID inv1 = UUID.randomUUID();
        UUID inv2 = UUID.randomUUID();
        when(loanRepository.countDisbursedGroupedByMemberOrdered(
                        eq(cooperativeId), eq(yearStart), eq(today), eq(DISBURSED), any(Pageable.class)))
                .thenReturn(List.of());
        when(loanInstallmentRepository.findRepaymentReliabilityFacts(cooperativeId)).thenReturn(List.of());
        when(investmentRepository.findLargestByRemainingCapital(
                        eq(cooperativeId), eq(ACTIVE), any(Pageable.class)))
                .thenReturn(List.of(
                        new Object[] {
                            inv1,
                            "Tea plantation",
                            new BigDecimal("5000000"),
                            new BigDecimal("4000000"),
                            new BigDecimal("200000"),
                            InvestmentStatus.ACTIVE
                        },
                        new Object[] {
                            inv2,
                            "Grain store",
                            new BigDecimal("3000000"),
                            new BigDecimal("1500000"),
                            new BigDecimal("100000"),
                            InvestmentStatus.PARTIALLY_RETURNED
                        }));

        DashboardAdvancedInsightsResponse response = dashboardService.advancedInsights(cooperativeId);

        assertThat(response.getLargestActiveInvestments()).hasSize(2);
        assertThat(response.getLargestActiveInvestments().get(0).getName()).isEqualTo("Tea plantation");
        assertThat(response.getLargestActiveInvestments().get(0).getRemainingCapital())
                .isEqualByComparingTo("4000000.00");
        assertThat(response.getLargestActiveInvestments().get(0).getOriginalCapital())
                .isEqualByComparingTo("5000000.00");
        assertThat(response.getLargestActiveInvestments().get(0).getProfitReturned())
                .isEqualByComparingTo("200000.00");
        assertThat(response.getLargestActiveInvestments().get(0).getStatus()).isEqualTo(InvestmentStatus.ACTIVE);
        assertThat(response.getLargestActiveInvestments().get(0).getRank()).isEqualTo(1);
        assertThat(response.getLargestActiveInvestments().get(1).getStatus())
                .isEqualTo(InvestmentStatus.PARTIALLY_RETURNED);
    }

    @Test
    void advancedInsights_loanOfficerSeesBorrowersNotInvestments() {
        stubCooperative();
        UserPrincipal loanOfficer = UserPrincipal.builder()
                .id(UUID.randomUUID())
                .username("loan")
                .roles(Set.of("LOAN_OFFICER"))
                .cooperativeIds(Set.of(cooperativeId))
                .accountNonLocked(true)
                .enabled(true)
                .build();
        when(authorizationService.currentPrincipal()).thenReturn(loanOfficer);
        when(cooperativeSettingsRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.empty());
        when(loanRepository.countDisbursedGroupedByMemberOrdered(
                        eq(cooperativeId), eq(yearStart), eq(today), eq(DISBURSED), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(new Object[] {memberA, 3L, new BigDecimal("300000")}));
        when(loanInstallmentRepository.findRepaymentReliabilityFacts(cooperativeId)).thenReturn(List.of());
        stubNames(memberA, "Sam Loan");

        DashboardAdvancedInsightsResponse response = dashboardService.advancedInsights(cooperativeId);

        assertThat(response.getFrequentBorrowers()).hasSize(1);
        assertThat(response.getLargestActiveInvestments()).isEmpty();
        assertThat(response.getRepaymentReliability()).isNotNull();
        assertThat(response.getRepaymentReliability().getMembers()).isEmpty();
        assertThat(response.getRepaymentReliability().getPeriod()).isEqualTo("LIFETIME");
        assertThat(response.getRepaymentReliability().getMinimumSample()).isEqualTo(3);
        verify(investmentRepository, never()).findLargestByRemainingCapital(any(), any(), any());
    }

    @Test
    void advancedInsights_memberForbidden() {
        stubCooperative();
        doThrow(new ForbiddenException("denied"))
                .when(authorizationService)
                .requireMemberInsightsAccess(cooperativeId);

        assertThatThrownBy(() -> dashboardService.advancedInsights(cooperativeId))
                .isInstanceOf(ForbiddenException.class);
    }

    private void stubCooperative() {
        Cooperative cooperative = Cooperative.builder().currency("RWF").build();
        cooperative.setId(cooperativeId);
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId)).thenReturn(Optional.of(cooperative));
    }

    private void stubCooperativeAndPresident() {
        stubCooperative();
        UserPrincipal president = UserPrincipal.builder()
                .id(UUID.randomUUID())
                .username("pres")
                .roles(Set.of("PRESIDENT"))
                .cooperativeIds(Set.of(cooperativeId))
                .accountNonLocked(true)
                .enabled(true)
                .build();
        when(authorizationService.currentPrincipal()).thenReturn(president);
        when(cooperativeSettingsRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.empty());
    }

    private void stubNames(UUID id1, String name1, UUID id2, String name2) {
        User u1 = new User();
        u1.setId(id1);
        u1.setFirstName(name1.split(" ")[0]);
        u1.setLastName(name1.contains(" ") ? name1.substring(name1.indexOf(' ') + 1) : "");
        User u2 = new User();
        u2.setId(id2);
        u2.setFirstName(name2.split(" ")[0]);
        u2.setLastName(name2.contains(" ") ? name2.substring(name2.indexOf(' ') + 1) : "");
        when(userRepository.findAllById(any())).thenReturn(List.of(u1, u2));
    }

    private void stubNames(UUID id, String name) {
        User u = new User();
        u.setId(id);
        u.setFirstName(name.split(" ")[0]);
        u.setLastName(name.contains(" ") ? name.substring(name.indexOf(' ') + 1) : "");
        when(userRepository.findAllById(any())).thenReturn(List.of(u));
    }
}
