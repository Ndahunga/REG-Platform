package rw.terimbere.csams.modules.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.settings.entity.CooperativeSettings;
import rw.terimbere.csams.modules.settings.repository.CooperativeSettingsRepository;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.repository.ShareValuationSnapshotRepository;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;

@ExtendWith(MockitoExtension.class)
class ShareValuationServiceTest {

    @Mock
    private CooperativeRepository cooperativeRepository;

    @Mock
    private CooperativeMembershipRepository membershipRepository;

    @Mock
    private CooperativeSettingsRepository settingsRepository;

    @Mock
    private LedgerFinancialCalculationService ledgerFinancialCalculationService;

    @Mock
    private LoanRepository loanRepository;

    @Mock
    private FineRepository fineRepository;

    @Mock
    private ShareValuationSnapshotRepository snapshotRepository;

    @Mock
    private rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService rotationEntitlementService;

    @InjectMocks
    private ShareValuationService service;

    private UUID cooperativeId;

    @BeforeEach
    void setUp() {
        cooperativeId = UUID.randomUUID();
        Cooperative cooperative = Cooperative.builder().currency("RWF").build();
        cooperative.setId(cooperativeId);
        when(cooperativeRepository.findByIdAndDeletedFalse(cooperativeId)).thenReturn(Optional.of(cooperative));
        // an accumulating scheme owes recipients nothing, so the valuation formula is exactly the original one
        org.mockito.Mockito.lenient()
                .when(rotationEntitlementService.totalOutstandingEntitlement(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.math.BigDecimal.ZERO);
        org.mockito.Mockito.lenient()
                .when(rotationEntitlementService.unresolvedHistory(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService.UnresolvedHistory.none());
        org.mockito.Mockito.lenient()
                .when(rotationEntitlementService.totalUnreconciledRotating(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.math.BigDecimal.ZERO);
    }

    @Test
    void totalIkiminaValueAndCurrentShareValueFollowNetAssetFormula() {
        stubComponents("1000.00", "400.00", "50.00", "30.00", "200.00");
        when(membershipRepository.sumShareCountByCooperativeIdAndActiveStatus(cooperativeId)).thenReturn(10);
        when(settingsRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.empty());

        ShareValuationResponse valuation = service.calculate(cooperativeId);

        assertThat(valuation.getAvailableFunds()).isEqualByComparingTo("1000.00");
        assertThat(valuation.getOutstandingLoans()).isEqualByComparingTo("400.00");
        assertThat(valuation.getUnpaidInterest()).isEqualByComparingTo("50.00");
        assertThat(valuation.getUnpaidPenalties()).isEqualByComparingTo("30.00");
        assertThat(valuation.getOtherAssets()).isEqualByComparingTo("200.00");
        assertThat(valuation.getLiabilities()).isEqualByComparingTo("0.00");
        assertThat(valuation.getTotalIkiminaValue()).isEqualByComparingTo("1680.00");
        assertThat(valuation.getTotalExistingShares()).isEqualTo(10);
        assertThat(valuation.getCurrentShareValue()).isEqualByComparingTo("168.00");
        assertThat(valuation.isUsedBaseSharePrice()).isFalse();
        assertThat(valuation.isCanPurchase()).isTrue();
        assertThat(valuation.getCalculatedAt()).isNotNull();
    }

    @Test
    void zeroExistingSharesUsesConfiguredBasePrice() {
        stubComponents("0.00", "0.00", "0.00", "0.00", "0.00");
        when(membershipRepository.sumShareCountByCooperativeIdAndActiveStatus(cooperativeId)).thenReturn(0);
        when(settingsRepository.findByCooperativeId(cooperativeId))
                .thenReturn(Optional.of(CooperativeSettings.builder()
                        .cooperativeId(cooperativeId)
                        .baseSharePrice(new BigDecimal("100000.0000"))
                        .build()));

        ShareValuationResponse valuation = service.calculate(cooperativeId);

        assertThat(valuation.getTotalExistingShares()).isEqualTo(0);
        assertThat(valuation.getCurrentShareValue()).isEqualByComparingTo("100000.00");
        assertThat(valuation.isUsedBaseSharePrice()).isTrue();
        assertThat(valuation.isCanPurchase()).isTrue();
    }

    @Test
    void zeroExistingSharesWithoutBasePriceBlocksPurchase() {
        stubComponents("0.00", "0.00", "0.00", "0.00", "0.00");
        when(membershipRepository.sumShareCountByCooperativeIdAndActiveStatus(cooperativeId)).thenReturn(0);
        when(settingsRepository.findByCooperativeId(cooperativeId)).thenReturn(Optional.empty());

        ShareValuationResponse valuation = service.calculate(cooperativeId);

        assertThat(valuation.getCurrentShareValue()).isEqualByComparingTo("0.00");
        assertThat(valuation.isCanPurchase()).isFalse();
        assertThat(valuation.getPurchaseBlockedReason()).contains("base share price");
    }

    @Test
    void existingSharesIgnoreBasePriceAndUseNetAssetValue() {
        stubComponents("0.00", "0.00", "0.00", "0.00", "0.00");
        when(membershipRepository.sumShareCountByCooperativeIdAndActiveStatus(cooperativeId)).thenReturn(1);
        when(settingsRepository.findByCooperativeId(cooperativeId))
                .thenReturn(Optional.of(CooperativeSettings.builder()
                        .cooperativeId(cooperativeId)
                        .baseSharePrice(new BigDecimal("100000.0000"))
                        .build()));

        ShareValuationResponse valuation = service.calculate(cooperativeId);

        assertThat(valuation.getTotalExistingShares()).isEqualTo(1);
        assertThat(valuation.getTotalIkiminaValue()).isEqualByComparingTo("0.00");
        assertThat(valuation.getCurrentShareValue()).isEqualByComparingTo("0.00");
        assertThat(valuation.isUsedBaseSharePrice()).isFalse();
        assertThat(valuation.isCanPurchase()).isFalse();
    }

    private void stubComponents(
            String funds, String loans, String interest, String penalties, String assets) {
        when(ledgerFinancialCalculationService.calculateAvailableGroupFund(cooperativeId))
                .thenReturn(new BigDecimal(funds));
        when(loanRepository.sumOutstandingPrincipalByStatuses(
                        cooperativeId, EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE)))
                .thenReturn(new BigDecimal(loans));
        when(loanRepository.sumOutstandingInterestByStatuses(
                        cooperativeId, EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE)))
                .thenReturn(new BigDecimal(interest));
        when(fineRepository.sumOutstandingForValuationExcludingWrittenOffLoans(
                        cooperativeId, EnumSet.of(FineStatus.UNPAID, FineStatus.PARTIALLY_PAID)))
                .thenReturn(new BigDecimal(penalties));
        when(ledgerFinancialCalculationService.sumActiveInvestmentCapital(cooperativeId))
                .thenReturn(new BigDecimal(assets));
    }
}
