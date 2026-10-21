package rw.terimbere.csams.modules.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.loan.entity.LoanShareTier;
import rw.terimbere.csams.modules.loan.service.LoanShareLimitCalculator;

class LoanShareLimitCalculatorTest {

    @Test
    void sharePercentIsMemberSharesOverCooperativeTotal() {
        assertEquals(0, new BigDecimal("4.0000").compareTo(LoanShareLimitCalculator.sharePercent(4, 100)));
        assertEquals(0, new BigDecimal("2.0000").compareTo(LoanShareLimitCalculator.sharePercent(2, 100)));
    }

    @Test
    void matchingTierUsesHighestPercentTheMemberMeets() {
        List<LoanShareTier> tiers = List.of(
                LoanShareTier.builder()
                        .minSharePercent(new BigDecimal("4.0000"))
                        .maxLoanAmount(new BigDecimal("20000.0000"))
                        .build(),
                LoanShareTier.builder()
                        .minSharePercent(new BigDecimal("2.0000"))
                        .maxLoanAmount(new BigDecimal("3000.0000"))
                        .build());

        assertEquals(
                0,
                new BigDecimal("20000.0000")
                        .compareTo(LoanShareLimitCalculator.matchingMaxLoan(new BigDecimal("4.0000"), tiers).orElseThrow()));
        assertEquals(
                0,
                new BigDecimal("3000.0000")
                        .compareTo(LoanShareLimitCalculator.matchingMaxLoan(new BigDecimal("2.0000"), tiers).orElseThrow()));
        assertEquals(
                0,
                new BigDecimal("3000.0000")
                        .compareTo(LoanShareLimitCalculator.matchingMaxLoan(new BigDecimal("3.9000"), tiers).orElseThrow()));
        assertTrue(LoanShareLimitCalculator.matchingMaxLoan(new BigDecimal("1.9000"), tiers).isEmpty());
    }
}
