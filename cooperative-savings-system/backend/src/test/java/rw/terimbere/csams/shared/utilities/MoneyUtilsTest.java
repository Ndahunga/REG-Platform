package rw.terimbere.csams.shared.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyUtilsTest {

    @Test
    void scaleRoundsHalfUpToTwoDecimals() {
        assertEquals(new BigDecimal("10.13"), MoneyUtils.scale(new BigDecimal("10.125")));
        assertEquals(new BigDecimal("10.12"), MoneyUtils.scale(new BigDecimal("10.124")));
    }

    @Test
    void addAndSubtractUseScaledBigDecimal() {
        BigDecimal sum = MoneyUtils.add(new BigDecimal("10.105"), new BigDecimal("0.005"));
        assertEquals(new BigDecimal("10.11"), sum);

        BigDecimal difference = MoneyUtils.subtract(new BigDecimal("10.10"), new BigDecimal("0.05"));
        assertEquals(new BigDecimal("10.05"), difference);
    }

    @Test
    void multiplyAndPercentageNeverUseDouble() {
        BigDecimal product = MoneyUtils.multiply(new BigDecimal("12.50"), new BigDecimal("3"));
        assertEquals(new BigDecimal("37.50"), product);

        BigDecimal percent = MoneyUtils.percentage(new BigDecimal("200.00"), new BigDecimal("12.5"));
        assertEquals(new BigDecimal("25.00"), percent);
    }

    @Test
    void divideScalesHalfUpAndRejectsZero() {
        assertEquals(new BigDecimal("50.00"), MoneyUtils.divide(new BigDecimal("100.00"), new BigDecimal("2")));
        assertThrows(IllegalArgumentException.class, () -> MoneyUtils.divide(BigDecimal.ONE, BigDecimal.ZERO));
    }

    @Test
    void assertNonNegativeAndPositive() {
        MoneyUtils.assertNonNegative(BigDecimal.ZERO);
        MoneyUtils.assertPositive(new BigDecimal("0.01"));

        assertThrows(IllegalArgumentException.class, () -> MoneyUtils.assertNonNegative(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> MoneyUtils.assertPositive(BigDecimal.ZERO));
        assertTrue(MoneyUtils.isZero(new BigDecimal("0.00")));
    }
}
