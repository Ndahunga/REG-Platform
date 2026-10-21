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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
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
import rw.terimbere.csams.modules.dashboard.dto.DashboardMemberInsightsResponse;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.investment.service.InvestmentService;
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
class DashboardServiceMemberInsightsTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kigali");

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
    private LocalDate periodStart;
    private LocalDate periodEnd;

    @BeforeEach
    void setUp() {
        cooperativeId = UUID.randomUUID();
        memberA = UUID.randomUUID();
        memberB = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZONE);
        periodStart = LocalDate.of(today.getYear(), 1, 1);
        periodEnd = today;
    }

    @Test
    void topContributorsOrderedAndLimitedWithNames() {
        stubCooperativeAndPresident();
        when(contributionRepository.sumPaidGroupedByMemberInDateRangeOrdered(
                        eq(cooperativeId), eq(periodStart), eq(periodEnd), any(Pageable.class)))
                .thenReturn(List.of(
                        new Object[] {memberA, new BigDecimal("450000")},
                        new Object[] {memberB, new BigDecimal("390000")}));
        when(fineRepository.sumFineFollowUpGroupedByMemberOrdered(
                        eq(cooperativeId), eq(periodStart), eq(periodEnd), any(Pageable.class)))
                .thenReturn(List.of());
        when(loanRepository.sumOverdueGroupedByMemberOrdered(
                        eq(cooperativeId), eq(periodEnd), any(Pageable.class)))
                .thenReturn(List.of());
        stubNames(memberA, "Jane Doe", memberB, "Eric N.");

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        DashboardMemberInsightsResponse response = dashboardService.memberInsights(cooperativeId);
        verify(contributionRepository)
                .sumPaidGroupedByMemberInDateRangeOrdered(
                        eq(cooperativeId), eq(periodStart), eq(periodEnd), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(5);
        verify(loanService, never()).refreshOverdueStatuses(any());

        assertThat(response.getTopContributors()).hasSize(2);
        assertThat(response.getTopContributors().get(0).getDisplayName()).isEqualTo("Jane Doe");
        assertThat(response.getTopContributors().get(0).getAmount()).isEqualByComparingTo("450000.00");
        assertThat(response.getTopContributors().get(0).getRank()).isEqualTo(1);
        assertThat(response.getTopContributors().get(1).getRank()).isEqualTo(2);
        assertThat(response.getPeriod().getStart()).isEqualTo(periodStart);
        assertThat(response.getPeriod().getEnd()).isEqualTo(periodEnd);
    }

    @Test
    void fineFollowUpUsesOutstandingAndCounts() {
        stubCooperativeAndPresident();
        when(contributionRepository.sumPaidGroupedByMemberInDateRangeOrdered(
                        eq(cooperativeId), eq(periodStart), eq(periodEnd), any(Pageable.class)))
                .thenReturn(List.of());
        when(fineRepository.sumFineFollowUpGroupedByMemberOrdered(
                        eq(cooperativeId), eq(periodStart), eq(periodEnd), any(Pageable.class)))
                .thenReturn(Collections.singletonList(new Object[] {
                    memberA,
                    new BigDecimal("50000"),
                    new BigDecimal("20000"),
                    new BigDecimal("30000"),
                    4L
                }));
        when(loanRepository.sumOverdueGroupedByMemberOrdered(
                        eq(cooperativeId), eq(periodEnd), any(Pageable.class)))
                .thenReturn(List.of());
        stubNames(memberA, "John Doe");

        DashboardMemberInsightsResponse response = dashboardService.memberInsights(cooperativeId);

        assertThat(response.getFineFollowUp()).hasSize(1);
        var row = response.getFineFollowUp().get(0);
        assertThat(row.getIssuedAmount()).isEqualByComparingTo("50000.00");
        assertThat(row.getPaidAmount()).isEqualByComparingTo("20000.00");
        assertThat(row.getOutstandingAmount()).isEqualByComparingTo("30000.00");
        assertThat(row.getFineCount()).isEqualTo(4);
        assertThat(row.getRank()).isEqualTo(1);
        verify(loanService, never()).refreshOverdueStatuses(any());
    }

    @Test
    void overdueLoansReturnedForLoanOfficerOnlyLoansSection() {
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
        when(loanRepository.sumOverdueGroupedByMemberOrdered(
                        eq(cooperativeId), eq(periodEnd), any(Pageable.class)))
                .thenReturn(Collections.singletonList(new Object[] {
                    memberA, 2L, new BigDecimal("120000"), LocalDate.of(2026, 1, 15)
                }));
        stubNames(memberA, "Alice K.");

        DashboardMemberInsightsResponse response = dashboardService.memberInsights(cooperativeId);

        verify(contributionRepository, never())
                .sumPaidGroupedByMemberInDateRangeOrdered(any(), any(), any(), any());
        verify(fineRepository, never()).sumFineFollowUpGroupedByMemberOrdered(any(), any(), any(), any());
        verify(loanService, never()).refreshOverdueStatuses(any());
        assertThat(response.getTopContributors()).isEmpty();
        assertThat(response.getFineFollowUp()).isEmpty();
        assertThat(response.getOverdueLoans()).hasSize(1);
        assertThat(response.getOverdueLoans().get(0).getOverdueLoanCount()).isEqualTo(2);
        assertThat(response.getOverdueLoans().get(0).getOutstandingPrincipal())
                .isEqualByComparingTo("120000.00");
        assertThat(response.getOverdueLoans().get(0).getOldestDueDate())
                .isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void emptyCooperativeReturnsEmptyLists() {
        stubCooperativeAndPresident();
        when(contributionRepository.sumPaidGroupedByMemberInDateRangeOrdered(
                        eq(cooperativeId), eq(periodStart), eq(periodEnd), any(Pageable.class)))
                .thenReturn(List.of());
        when(fineRepository.sumFineFollowUpGroupedByMemberOrdered(
                        eq(cooperativeId), eq(periodStart), eq(periodEnd), any(Pageable.class)))
                .thenReturn(List.of());
        when(loanRepository.sumOverdueGroupedByMemberOrdered(
                        eq(cooperativeId), eq(periodEnd), any(Pageable.class)))
                .thenReturn(List.of());

        DashboardMemberInsightsResponse response = dashboardService.memberInsights(cooperativeId);

        assertThat(response.getTopContributors()).isEmpty();
        assertThat(response.getFineFollowUp()).isEmpty();
        assertThat(response.getOverdueLoans()).isEmpty();
        verify(loanService, never()).refreshOverdueStatuses(any());
    }

    @Test
    void memberDenied() {
        Cooperative cooperative = Cooperative.builder().currency("RWF").build();
        cooperative.setId(cooperativeId);
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId)).thenReturn(Optional.of(cooperative));
        doThrow(new ForbiddenException("Member insights are restricted to Saving Scheme officers"))
                .when(authorizationService)
                .requireMemberInsightsAccess(cooperativeId);

        assertThatThrownBy(() -> dashboardService.memberInsights(cooperativeId))
                .isInstanceOf(ForbiddenException.class);
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

    private void stubNames(UUID id1, String name1) {
        User u1 = new User();
        u1.setId(id1);
        u1.setFirstName(name1.split(" ")[0]);
        u1.setLastName(name1.contains(" ") ? name1.substring(name1.indexOf(' ') + 1) : "");
        when(userRepository.findAllById(any())).thenReturn(List.of(u1));
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
}
