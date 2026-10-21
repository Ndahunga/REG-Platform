package rw.terimbere.csams.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentComponent;
import rw.terimbere.csams.modules.loan.service.LoanAllocationOrder;
import rw.terimbere.csams.modules.loan.service.LoanScheduleAllocator;

class LoanScheduleAllocatorTest {

    @Test
    void penaltyInterestPrincipalAllocatesFullPayment() {
        List<LoanInstallment> installments = List.of(installment(5000, 15000, 80000));

        LoanScheduleAllocator.PaymentSplit split = LoanScheduleAllocator.allocatePayment(
                installments, money("100000"), LoanAllocationOrder.parse("PENALTY,INTEREST,PRINCIPAL"), today());

        assertThat(split.penaltyPortion()).isEqualByComparingTo("5000.00");
        assertThat(split.interestPortion()).isEqualByComparingTo("15000.00");
        assertThat(split.principalPortion()).isEqualByComparingTo("80000.00");
        assertThat(installments.get(0).getStatus()).isEqualTo(LoanInstallmentStatus.PAID);
    }

    @Test
    void partialPaymentStopsAfterConfiguredOrder() {
        List<LoanInstallment> installments = List.of(installment(5000, 15000, 80000));

        LoanScheduleAllocator.PaymentSplit split = LoanScheduleAllocator.allocatePayment(
                installments, money("12000"), LoanAllocationOrder.parse("PENALTY,INTEREST,PRINCIPAL"), today());

        assertThat(split.penaltyPortion()).isEqualByComparingTo("5000.00");
        assertThat(split.interestPortion()).isEqualByComparingTo("7000.00");
        assertThat(split.principalPortion()).isEqualByComparingTo("0.00");
        assertThat(installments.get(0).remainingInterest()).isEqualByComparingTo("8000.00");
        assertThat(installments.get(0).remainingPrincipal()).isEqualByComparingTo("80000.00");
        assertThat(installments.get(0).getStatus()).isEqualTo(LoanInstallmentStatus.PARTIALLY_PAID);
    }

    @Test
    void allocationOrderIsConfigurable() {
        List<LoanInstallment> installments = List.of(installment(5000, 15000, 80000));

        LoanScheduleAllocator.PaymentSplit split = LoanScheduleAllocator.allocatePayment(
                installments, money("12000"), LoanAllocationOrder.parse("PRINCIPAL,INTEREST,PENALTY"), today());

        assertThat(split.principalPortion()).isEqualByComparingTo("12000.00");
        assertThat(split.interestPortion()).isEqualByComparingTo("0.00");
        assertThat(split.penaltyPortion()).isEqualByComparingTo("0.00");
    }

    @Test
    void remainderFlowsFifoToNextInstallment() {
        List<LoanInstallment> installments = new ArrayList<>();
        installments.add(installment(1, 0, 6000, 60000));
        installments.add(installment(2, 0, 6000, 60000));

        LoanScheduleAllocator.PaymentSplit split = LoanScheduleAllocator.allocatePayment(
                installments, money("80000"), LoanAllocationOrder.parse("PENALTY,INTEREST,PRINCIPAL"), today());

        assertThat(split.interestPortion()).isEqualByComparingTo("12000.00");
        assertThat(split.principalPortion()).isEqualByComparingTo("68000.00");
        assertThat(installments.get(0).getStatus()).isEqualTo(LoanInstallmentStatus.PAID);
        assertThat(installments.get(1).getPrincipalPaid()).isEqualByComparingTo("8000.00");
        assertThat(installments.get(1).getStatus()).isEqualTo(LoanInstallmentStatus.PARTIALLY_PAID);
    }

    private static LoanInstallment installment(int penalty, int interest, int principal) {
        return installment(1, penalty, interest, principal);
    }

    private static LoanInstallment installment(int number, int penalty, int interest, int principal) {
        LoanInstallment row = LoanInstallment.builder()
                .loanId(UUID.randomUUID())
                .cooperativeId(UUID.randomUUID())
                .installmentNumber(number)
                .dueDate(LocalDate.of(2026, 3, 31))
                .openingPrincipalBalance(money(String.valueOf(principal)))
                .principalDue(money(String.valueOf(principal)))
                .interestDue(money(String.valueOf(interest)))
                .penaltyDue(money(String.valueOf(penalty)))
                .principalPaid(BigDecimal.ZERO)
                .interestPaid(BigDecimal.ZERO)
                .penaltyPaid(BigDecimal.ZERO)
                .scheduledInstallmentAmount(money(String.valueOf(principal + interest)))
                .status(LoanInstallmentStatus.PENDING)
                .build();
        row.setId(UUID.randomUUID());
        return row;
    }

    private static LocalDate today() {
        return LocalDate.of(2026, 3, 1);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
