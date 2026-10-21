package rw.terimbere.csams.modules.fine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.fine.service.FineCalculationService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

class FineCalculationServiceTest {

    private final FineCalculationService service = new FineCalculationService();

    @Test
    void progressiveFormula_exact() {
        BigDecimal total = service.calculateProgressive(
                new BigDecimal("1000.0000"), new BigDecimal("50.0000"), 7);
        assertThat(total).isEqualByComparingTo(MoneyUtils.scale(new BigDecimal("1350.00")));
    }

    @Test
    void progressiveFormula_zeroOverdueDays_equalsBase() {
        BigDecimal total = service.calculateProgressive(
                new BigDecimal("500.0000"), new BigDecimal("25.0000"), 0);
        assertThat(total).isEqualByComparingTo(MoneyUtils.scale(new BigDecimal("500.00")));
    }

    @Test
    void overdueDays_respectsGracePeriod() {
        LocalDate due = LocalDate.of(2026, 1, 5);
        assertThat(service.computeOverdueDays(due, 3, LocalDate.of(2026, 1, 8))).isZero();
        assertThat(service.computeOverdueDays(due, 3, LocalDate.of(2026, 1, 9))).isEqualTo(1);
        assertThat(service.computeOverdueDays(due, 3, LocalDate.of(2026, 1, 15))).isEqualTo(7);
    }

    @Test
    void contributionDueDate_clampsToMonthLength() {
        assertThat(service.contributionDueDate(2026, 2, 31)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(service.contributionDueDate(2026, 1, 15)).isEqualTo(LocalDate.of(2026, 1, 15));
    }
}
