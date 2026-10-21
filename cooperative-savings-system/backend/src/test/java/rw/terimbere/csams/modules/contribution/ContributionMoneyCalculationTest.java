package rw.terimbere.csams.modules.contribution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

class ContributionMoneyCalculationTest {

    @Test
    void deriveStatus_andOutstandingUseBigDecimal() {
        BigDecimal expected = MoneyUtils.scaleForStorage(new BigDecimal("1000.0000"));
        BigDecimal paid = MoneyUtils.scaleForStorage(new BigDecimal("400.5000"));
        BigDecimal outstanding = MoneyUtils.scaleForStorage(expected.subtract(paid).max(BigDecimal.ZERO));

        assertEquals(ContributionStatus.PARTIALLY_PAID, ContributionService.deriveStatus(expected, paid));
        assertEquals(new BigDecimal("599.5000"), outstanding);
        assertEquals(ContributionStatus.PAID, ContributionService.deriveStatus(expected, expected));
        assertEquals(ContributionStatus.PENDING, ContributionService.deriveStatus(expected, BigDecimal.ZERO));
        assertEquals(new BigDecimal("10.13"), MoneyUtils.scale(new BigDecimal("10.125")));
        assertEquals(new BigDecimal("10.12"), MoneyUtils.scale(new BigDecimal("10.124")));
    }
}
