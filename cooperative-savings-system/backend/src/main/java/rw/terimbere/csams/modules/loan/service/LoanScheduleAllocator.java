package rw.terimbere.csams.modules.loan.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentComponent;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Allocates a payment FIFO across persisted installments using the Ikimina
 * component order (typically PENALTY → INTEREST → PRINCIPAL).
 */
public final class LoanScheduleAllocator {

    private LoanScheduleAllocator() {}

    public record AllocationLine(
            java.util.UUID installmentId, int installmentNumber, LoanRepaymentComponent component, BigDecimal amount) {}

    public record PaymentSplit(
            BigDecimal principalPortion,
            BigDecimal interestPortion,
            BigDecimal penaltyPortion,
            List<AllocationLine> lines) {}

    public static PaymentSplit allocatePayment(
            List<LoanInstallment> installments,
            BigDecimal amount,
            List<LoanRepaymentComponent> order,
            LocalDate asOfDate) {
        BigDecimal remaining = MoneyUtils.scale(amount == null ? BigDecimal.ZERO : amount);
        BigDecimal totalPrincipal = zero();
        BigDecimal totalInterest = zero();
        BigDecimal totalPenalty = zero();
        List<AllocationLine> lines = new ArrayList<>();
        List<LoanRepaymentComponent> resolved = order == null || order.isEmpty()
                ? LoanAllocationOrder.parse(LoanAllocationOrder.DEFAULT)
                : order;

        if (installments != null) {
            for (LoanInstallment installment : installments) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    installment.refreshStatus(asOfDate);
                    continue;
                }
                for (LoanRepaymentComponent component : resolved) {
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                    BigDecimal available = remainingOf(installment, component);
                    if (MoneyUtils.isZero(available)) {
                        continue;
                    }
                    BigDecimal taken = remaining.min(available);
                    apply(installment, component, taken);
                    remaining = MoneyUtils.scale(remaining.subtract(taken));
                    lines.add(new AllocationLine(
                            installment.getId(), installment.getInstallmentNumber(), component, taken));
                    if (component == LoanRepaymentComponent.PRINCIPAL) {
                        totalPrincipal = MoneyUtils.add(totalPrincipal, taken);
                    } else if (component == LoanRepaymentComponent.INTEREST) {
                        totalInterest = MoneyUtils.add(totalInterest, taken);
                    } else {
                        totalPenalty = MoneyUtils.add(totalPenalty, taken);
                    }
                }
                installment.refreshStatus(asOfDate);
            }
        }
        return new PaymentSplit(
                MoneyUtils.scaleForStorage(totalPrincipal),
                MoneyUtils.scaleForStorage(totalInterest),
                MoneyUtils.scaleForStorage(totalPenalty),
                List.copyOf(lines));
    }

    private static BigDecimal remainingOf(LoanInstallment installment, LoanRepaymentComponent component) {
        return switch (component) {
            case PRINCIPAL -> installment.remainingPrincipal();
            case INTEREST -> installment.remainingInterest();
            case PENALTY -> installment.remainingPenalty();
        };
    }

    private static void apply(LoanInstallment installment, LoanRepaymentComponent component, BigDecimal taken) {
        if (component == LoanRepaymentComponent.PRINCIPAL) {
            installment.setPrincipalPaid(MoneyUtils.add(nvl(installment.getPrincipalPaid()), taken));
        } else if (component == LoanRepaymentComponent.INTEREST) {
            installment.setInterestPaid(MoneyUtils.add(nvl(installment.getInterestPaid()), taken));
        } else {
            installment.setPenaltyPaid(MoneyUtils.add(nvl(installment.getPenaltyPaid()), taken));
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? zero() : MoneyUtils.scale(value);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE);
    }
}
