package rw.terimbere.csams.modules.dashboard.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.dashboard.support.MonthOverMonthCalculator.ChangeState;

class MonthOverMonthCalculatorTest {

    @Test
    void positiveChange() {
        var result = MonthOverMonthCalculator.of(new BigDecimal("1200"), new BigDecimal("1000"));
        assertThat(result.changePercent()).isEqualByComparingTo("20.0");
        assertThat(result.changeState()).isEqualTo(ChangeState.UP);
    }

    @Test
    void negativeChange() {
        var result = MonthOverMonthCalculator.of(new BigDecimal("800"), new BigDecimal("1000"));
        assertThat(result.changePercent()).isEqualByComparingTo("-20.0");
        assertThat(result.changeState()).isEqualTo(ChangeState.DOWN);
    }

    @Test
    void flatWhenEqual() {
        var result = MonthOverMonthCalculator.of(new BigDecimal("500"), new BigDecimal("500"));
        assertThat(result.changePercent()).isEqualByComparingTo("0.0");
        assertThat(result.changeState()).isEqualTo(ChangeState.FLAT);
    }

    @Test
    void previousZeroCurrentPositive_isNoBaseline() {
        var result = MonthOverMonthCalculator.of(new BigDecimal("100"), BigDecimal.ZERO);
        assertThat(result.changePercent()).isNull();
        assertThat(result.changeState()).isEqualTo(ChangeState.NO_BASELINE);
        assertThat(result.current()).isEqualByComparingTo("100.00");
    }

    @Test
    void bothZero_isFlat() {
        var result = MonthOverMonthCalculator.of(BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(result.changePercent()).isEqualByComparingTo("0.0");
        assertThat(result.changeState()).isEqualTo(ChangeState.FLAT);
    }

    @Test
    void nullInputsTreatedAsZero() {
        var result = MonthOverMonthCalculator.of(null, null);
        assertThat(result.current()).isEqualByComparingTo("0.00");
        assertThat(result.previous()).isEqualByComparingTo("0.00");
        assertThat(result.changeState()).isEqualTo(ChangeState.FLAT);
    }
}
