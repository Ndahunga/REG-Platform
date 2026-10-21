package rw.terimbere.csams.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.modules.loan.service.LoanRepaymentDates;

class LoanRepaymentDatesTest {

    @Test
    void sameDayUsesDisbursementDayOneMonthLater() {
        List<LocalDate> dates = LoanRepaymentDates.generate(
                LoanRepaymentDateModel.SAME_DAY_OF_MONTH, LocalDate.of(2026, 1, 15), 3);

        assertThat(dates)
                .containsExactly(
                        LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 15));
    }

    @Test
    void sameDayPreservesCycleDayAcrossShortMonths() {
        List<LocalDate> dates = LoanRepaymentDates.generate(
                LoanRepaymentDateModel.SAME_DAY_OF_MONTH, LocalDate.of(2026, 1, 31), 5);

        assertThat(dates)
                .containsExactly(
                        LocalDate.of(2026, 2, 28),
                        LocalDate.of(2026, 3, 31),
                        LocalDate.of(2026, 4, 30),
                        LocalDate.of(2026, 5, 31),
                        LocalDate.of(2026, 6, 30));
    }

    @Test
    void monthEndUsesCalendarLastDaysIncludingLeapFebruary() {
        List<LocalDate> dates = LoanRepaymentDates.generate(
                LoanRepaymentDateModel.MONTH_END, LocalDate.of(2028, 1, 15), 4);

        assertThat(dates)
                .containsExactly(
                        LocalDate.of(2028, 1, 31),
                        LocalDate.of(2028, 2, 29),
                        LocalDate.of(2028, 3, 31),
                        LocalDate.of(2028, 4, 30));
    }

    @Test
    void monthEndUsesNonLeapFebruary() {
        List<LocalDate> dates = LoanRepaymentDates.generate(
                LoanRepaymentDateModel.MONTH_END, LocalDate.of(2026, 1, 15), 3);

        assertThat(dates)
                .containsExactly(
                        LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
    }
}
