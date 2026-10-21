package rw.terimbere.csams.modules.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.service.LoanInterestCalculator;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.modules.loan.service.LoanSettingsService;

/**
 * REDUCING interest is blocked for new configuration until a client-approved formula exists.
 * Historical REDUCING loans remain readable via LoanInterestCalculator without formula change.
 */
class ReducingInterestGuardTest {

    @Test
    void flatInterestComputesPrincipalTimesRate() {
        BigDecimal interest = LoanInterestCalculator.computeInterest(
                new BigDecimal("100000.0000"), new BigDecimal("10"), InterestType.FLAT);
        assertEquals(0, new BigDecimal("10000.0000").compareTo(interest));
    }

    @Test
    void legacyReducingPathStillComputesWithoutChangingFormula() {
        // Documented approximation — must not silently change for historical loans.
        BigDecimal interest = LoanInterestCalculator.computeInterest(
                new BigDecimal("100000.0000"), new BigDecimal("10"), InterestType.REDUCING);
        assertEquals(0, new BigDecimal("10000.0000").compareTo(interest));
    }

    @Test
    void settingsServiceRejectsReducing() {
        ValidationException ex = assertThrows(
                ValidationException.class, () -> LoanSettingsService.rejectReducingInterest(InterestType.REDUCING));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().toLowerCase().contains("reducing"));
    }
}
