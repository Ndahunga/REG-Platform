package rw.terimbere.csams.shared.utilities;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Monetary arithmetic helpers. Always uses {@link BigDecimal}; never double/float.
 */
public final class MoneyUtils {

    public static final int MONEY_SCALE = 2;
    public static final int STORAGE_SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final MathContext MATH_CONTEXT = new MathContext(19, ROUNDING);

    private MoneyUtils() {
    }

    public static BigDecimal scale(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return amount.setScale(MONEY_SCALE, ROUNDING);
    }

    public static BigDecimal scaleForStorage(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return amount.setScale(STORAGE_SCALE, ROUNDING);
    }

    public static BigDecimal add(BigDecimal left, BigDecimal right) {
        return scale(require(left).add(require(right)));
    }

    public static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return scale(require(left).subtract(require(right)));
    }

    public static BigDecimal multiply(BigDecimal left, BigDecimal right) {
        return scale(require(left).multiply(require(right), MATH_CONTEXT));
    }

    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        if (require(divisor).compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Cannot divide by zero");
        }
        return scale(require(dividend).divide(divisor, MATH_CONTEXT));
    }

    /**
     * Calculates {@code amount * (percent / 100)} using BigDecimal only.
     */
    public static BigDecimal percentage(BigDecimal amount, BigDecimal percent) {
        BigDecimal hundred = new BigDecimal("100");
        BigDecimal ratio = require(percent).divide(hundred, MATH_CONTEXT);
        return scale(require(amount).multiply(ratio, MATH_CONTEXT));
    }

    public static void assertNonNegative(BigDecimal amount) {
        if (require(amount).compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be non-negative");
        }
    }

    public static void assertPositive(BigDecimal amount) {
        if (require(amount).compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public static boolean isZero(BigDecimal amount) {
        return require(amount).compareTo(BigDecimal.ZERO) == 0;
    }

    private static BigDecimal require(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount must not be null");
    }
}
