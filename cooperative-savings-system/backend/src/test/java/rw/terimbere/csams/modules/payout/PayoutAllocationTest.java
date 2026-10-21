package rw.terimbere.csams.modules.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.payout.repository.PayoutLineRepository;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.modules.cooperative.service.SchemeOperatingModelGuard;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.payout.service.PayoutService;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

class PayoutAllocationTest {

    private PayoutService payoutService;

    @BeforeEach
    void setUp() {
        payoutService = new PayoutService(
                mock(PayoutRunRepository.class),
                mock(PayoutLineRepository.class),
                mock(ContributionRepository.class),
                mock(SpecialContributionRepository.class),
                mock(CooperativeRepository.class),
                mock(LedgerService.class),
                mock(LedgerFinancialCalculationService.class),
                mock(CooperativeAuthorizationService.class),
                mock(AuditService.class),
                mock(NotificationFacade.class),
                mock(SchemeFundLockService.class),
                mock(SchemeOperatingModelGuard.class));
    }

    @Test
    void percentagesSumNear100AndPayoutsSumToPool() {
        UUID m1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID m2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID m3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        Map<UUID, BigDecimal> eligible = new LinkedHashMap<>();
        eligible.put(m1, MoneyUtils.scaleForStorage(new BigDecimal("100.0000")));
        eligible.put(m2, MoneyUtils.scaleForStorage(new BigDecimal("200.0000")));
        eligible.put(m3, MoneyUtils.scaleForStorage(new BigDecimal("300.0000")));

        BigDecimal totalEligible = MoneyUtils.scaleForStorage(new BigDecimal("600.0000"));
        BigDecimal pool = MoneyUtils.scaleForStorage(new BigDecimal("1000.0000"));

        List<PayoutService.AllocatedLine> lines = payoutService.allocate(eligible, totalEligible, pool);

        BigDecimal pctSum = lines.stream()
                .map(PayoutService.AllocatedLine::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(pctSum)
                .isCloseTo(
                        new BigDecimal("100"),
                        org.assertj.core.data.Offset.offset(new BigDecimal("0.00000001")));

        BigDecimal payoutSum = lines.stream()
                .map(PayoutService.AllocatedLine::payout)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(MoneyUtils.scaleForStorage(payoutSum)).isEqualByComparingTo(pool);

        assertThat(lines.get(0).payout()).isEqualByComparingTo("166.6667");
        assertThat(lines.get(1).payout()).isEqualByComparingTo("333.3333");
        // last member absorbs remainder: 1000 - 166.6667 - 333.3333 = 500.0000
        assertThat(lines.get(2).payout()).isEqualByComparingTo("500.0000");
    }

    @Test
    void unevenEligibleStillBalancesPoolExactly() {
        UUID m1 = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID m2 = UUID.fromString("00000000-0000-0000-0000-00000000000b");

        Map<UUID, BigDecimal> eligible = Map.of(
                m1, MoneyUtils.scaleForStorage(new BigDecimal("10.0000")),
                m2, MoneyUtils.scaleForStorage(new BigDecimal("10.0000")));
        BigDecimal total = MoneyUtils.scaleForStorage(new BigDecimal("20.0000"));
        BigDecimal pool = MoneyUtils.scaleForStorage(new BigDecimal("100.0000"));

        List<PayoutService.AllocatedLine> lines = payoutService.allocate(eligible, total, pool);
        BigDecimal payoutSum = lines.stream()
                .map(PayoutService.AllocatedLine::payout)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(MoneyUtils.scaleForStorage(payoutSum)).isEqualByComparingTo(pool);
    }
}
