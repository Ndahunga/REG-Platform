package rw.terimbere.csams.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleResponse;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.modules.loan.service.LoanInterestCalculator;
import rw.terimbere.csams.modules.loan.service.LoanScheduleCalculator;
import rw.terimbere.csams.shared.exceptions.ValidationException;

class LoanScheduleCalculatorTest {

    @Test
    void sameDayUsesFullMonthlyInterestAndOpeningBalances() {
        LoanScheduleResponse schedule = LoanScheduleCalculator.calculate(
                money("300000"),
                money("2"),
                5,
                LoanRepaymentDateModel.SAME_DAY_OF_MONTH,
                LocalDate.of(2026, 1, 15));

        assertThat(schedule.isProrataEnabled()).isFalse();
        assertThat(schedule.getFirstPeriodInterest()).isEqualByComparingTo("6000.00");
        assertThat(schedule.getTotalInterest()).isEqualByComparingTo("30000.00");
        assertThat(schedule.getEqualInstallmentAmount()).isEqualByComparingTo("66000.00");
        assertThat(schedule.getInstallments().get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(schedule.getInstallments().get(0).getOpeningPrincipalBalance()).isEqualByComparingTo("300000.00");
        assertThat(schedule.getInstallments().get(1).getOpeningPrincipalBalance()).isEqualByComparingTo("240000.00");
        assertThat(sumPrincipal(schedule)).isEqualByComparingTo("300000.00");
        assertThat(sumInterest(schedule)).isEqualByComparingTo("30000.00");
    }

    @Test
    void monthEndProratesWithActualCalendarDays() {
        LoanScheduleResponse schedule = LoanScheduleCalculator.calculate(
                money("300000"),
                money("2"),
                5,
                LoanRepaymentDateModel.MONTH_END,
                LocalDate.of(2026, 1, 15));

        assertThat(schedule.isProrataEnabled()).isTrue();
        assertThat(schedule.getFirstPeriodDays()).isEqualTo(16);
        assertThat(schedule.getDaysInFirstMonth()).isEqualTo(31);
        assertThat(schedule.getFirstPeriodInterest()).isEqualByComparingTo("3096.77");
        assertThat(schedule.getRegularMonthlyInterest()).isEqualByComparingTo("6000.00");
        assertThat(schedule.getInstallments().get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(schedule.getInstallments().get(1).getDueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(sumPrincipal(schedule)).isEqualByComparingTo("300000.00");
        assertThat(sumInterest(schedule)).isEqualByComparingTo(schedule.getTotalInterest());
        assertThat(sumPayments(schedule)).isEqualByComparingTo(schedule.getTotalRepayment());
    }

    @Test
    void interestIsNeverDecliningBalance() {
        LoanScheduleResponse schedule = LoanScheduleCalculator.calculate(
                money("300000"),
                money("2"),
                5,
                LoanRepaymentDateModel.SAME_DAY_OF_MONTH,
                LocalDate.of(2026, 3, 1));

        BigDecimal monthly = LoanInterestCalculator.computeInterest(money("300000"), money("2"), InterestType.FLAT);
        BigDecimal remainingBefore = money("300000");
        for (LoanInstallmentResponse installment : schedule.getInstallments()) {
            assertThat(installment.getInterestComponent()).isEqualByComparingTo(monthly);
            BigDecimal declining = remainingBefore.multiply(new BigDecimal("0.02"));
            if (remainingBefore.compareTo(money("300000")) < 0) {
                assertThat(installment.getInterestComponent()).isNotEqualByComparingTo(declining);
            }
            remainingBefore = remainingBefore.subtract(installment.getPrincipalComponent());
        }
    }

    @Test
    void zeroInstallmentsAreRejected() {
        assertThatThrownBy(() -> LoanScheduleCalculator.calculate(
                        money("300000"),
                        money("2"),
                        0,
                        LoanRepaymentDateModel.SAME_DAY_OF_MONTH,
                        LocalDate.of(2026, 1, 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("installments");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal sumPrincipal(LoanScheduleResponse schedule) {
        return schedule.getInstallments().stream()
                .map(LoanInstallmentResponse::getPrincipalComponent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumInterest(LoanScheduleResponse schedule) {
        return schedule.getInstallments().stream()
                .map(LoanInstallmentResponse::getInterestComponent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumPayments(LoanScheduleResponse schedule) {
        return schedule.getInstallments().stream()
                .map(LoanInstallmentResponse::getPaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
