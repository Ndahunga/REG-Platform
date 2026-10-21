package rw.terimbere.csams.modules.dashboard.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Shared month-over-month arithmetic for dashboard insights.
 *
 * <p>When previous = 0 and current &gt; 0, {@code changePercent} is null and
 * {@code changeState} is {@link ChangeState#NO_BASELINE} (never NaN/Infinity).
 */
public final class MonthOverMonthCalculator {

    public enum ChangeState {
        UP,
        DOWN,
        FLAT,
        NO_BASELINE
    }

    public record MonthOverMonth(
            BigDecimal current, BigDecimal previous, BigDecimal changePercent, ChangeState changeState) {}

    private MonthOverMonthCalculator() {}

    public static MonthOverMonth of(BigDecimal currentRaw, BigDecimal previousRaw) {
        BigDecimal current = MoneyUtils.scale(currentRaw == null ? BigDecimal.ZERO : currentRaw);
        BigDecimal previous = MoneyUtils.scale(previousRaw == null ? BigDecimal.ZERO : previousRaw);

        if (previous.compareTo(BigDecimal.ZERO) == 0) {
            if (current.compareTo(BigDecimal.ZERO) == 0) {
                return new MonthOverMonth(current, previous, BigDecimal.ZERO.setScale(1), ChangeState.FLAT);
            }
            return new MonthOverMonth(current, previous, null, ChangeState.NO_BASELINE);
        }

        BigDecimal changePercent = current
                .subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, RoundingMode.HALF_UP);

        ChangeState state;
        int cmp = changePercent.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            state = ChangeState.UP;
        } else if (cmp < 0) {
            state = ChangeState.DOWN;
        } else {
            state = ChangeState.FLAT;
        }
        return new MonthOverMonth(current, previous, changePercent, state);
    }
}
