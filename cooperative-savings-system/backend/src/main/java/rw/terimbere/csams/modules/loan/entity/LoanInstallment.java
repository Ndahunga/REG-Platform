package rw.terimbere.csams.modules.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "loan_installments")
public class LoanInstallment extends BaseEntity {

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "installment_number", nullable = false)
    private int installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "opening_principal_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal openingPrincipalBalance;

    @Column(name = "principal_due", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalDue;

    @Column(name = "interest_due", nullable = false, precision = 19, scale = 4)
    private BigDecimal interestDue;

    @Builder.Default
    @Column(name = "penalty_due", nullable = false, precision = 19, scale = 4)
    private BigDecimal penaltyDue = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "principal_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalPaid = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "interest_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal interestPaid = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "penalty_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal penaltyPaid = BigDecimal.ZERO;

    @Column(name = "scheduled_installment_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal scheduledInstallmentAmount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LoanInstallmentStatus status = LoanInstallmentStatus.PENDING;

    public BigDecimal remainingPrincipal() {
        return remaining(principalDue, principalPaid);
    }

    public BigDecimal remainingInterest() {
        return remaining(interestDue, interestPaid);
    }

    public BigDecimal remainingPenalty() {
        return remaining(penaltyDue, penaltyPaid);
    }

    public BigDecimal totalDue() {
        return MoneyUtils.add(MoneyUtils.add(nvl(principalDue), nvl(interestDue)), nvl(penaltyDue));
    }

    public BigDecimal totalPaid() {
        return MoneyUtils.add(MoneyUtils.add(nvl(principalPaid), nvl(interestPaid)), nvl(penaltyPaid));
    }

    public BigDecimal remainingAmount() {
        return MoneyUtils.subtract(totalDue(), totalPaid());
    }

    public void refreshStatus(LocalDate asOf) {
        BigDecimal remaining = remainingAmount();
        if (MoneyUtils.isZero(remaining) || remaining.compareTo(BigDecimal.ZERO) < 0) {
            status = LoanInstallmentStatus.PAID;
            return;
        }
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        if (dueDate != null && dueDate.isBefore(today)) {
            status = LoanInstallmentStatus.OVERDUE;
        } else if (dueDate != null && dueDate.isEqual(today)) {
            status = LoanInstallmentStatus.DUE;
        } else if (totalPaid().compareTo(BigDecimal.ZERO) > 0) {
            status = LoanInstallmentStatus.PARTIALLY_PAID;
        } else {
            status = LoanInstallmentStatus.PENDING;
        }
    }

    private static BigDecimal remaining(BigDecimal due, BigDecimal paid) {
        BigDecimal value = MoneyUtils.subtract(nvl(due), nvl(paid));
        return value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE) : value;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE) : MoneyUtils.scale(value);
    }
}
