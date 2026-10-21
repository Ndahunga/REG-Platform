package rw.terimbere.csams.modules.loanrepayment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentComponent;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "loan_repayment_allocations")
public class LoanRepaymentAllocation extends BaseEntity {

    @Column(name = "repayment_id", nullable = false)
    private UUID repaymentId;

    @Column(name = "installment_id", nullable = false)
    private UUID installmentId;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "component", nullable = false, length = 32)
    private LoanRepaymentComponent component;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
}
