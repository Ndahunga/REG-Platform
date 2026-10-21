package rw.terimbere.csams.modules.contribution.obligation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ContributionSplitByUnitsTest {

    private static final BigDecimal UNIT = new BigDecimal("70000");
    private static final BigDecimal ROT = new BigDecimal("50000");

    @Test
    void fullPaymentRotatesExactlyTheStatedAmount() {
        var s = ContributionAllocationCalculator.splitByUnits(new BigDecimal("70000"), ROT, UNIT);
        assertThat(s.rotating()).isEqualByComparingTo("50000");
        assertThat(s.retained()).isEqualByComparingTo("20000");
    }

    @Test
    void twoSharesRotateTwiceTheAmount() {
        var s = ContributionAllocationCalculator.splitByUnits(new BigDecimal("140000.0000"), ROT, UNIT);
        assertThat(s.rotating()).isEqualByComparingTo("100000");
        assertThat(s.retained()).isEqualByComparingTo("40000");
    }

    @Test
    void partialPaymentsAddUpToTheFullSplit() {
        var a = ContributionAllocationCalculator.deltaByUnits(BigDecimal.ZERO, new BigDecimal("30000"), ROT, UNIT);
        var b = ContributionAllocationCalculator.deltaByUnits(new BigDecimal("30000"), new BigDecimal("40000"), ROT, UNIT);
        assertThat(a.rotating().add(b.rotating())).isEqualByComparingTo("50000");
        assertThat(a.retained().add(b.retained())).isEqualByComparingTo("20000");
    }

    @Test
    void basisPointsWouldHaveOverCreditedTheRotation() {
        var bps = ContributionAllocationCalculator.split(new BigDecimal("70000"), 7143);
        assertThat(bps.rotating()).isEqualByComparingTo("50001");
    }
}
