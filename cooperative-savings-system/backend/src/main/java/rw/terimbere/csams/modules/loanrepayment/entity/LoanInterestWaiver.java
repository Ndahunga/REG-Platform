package rw.terimbere.csams.modules.loanrepayment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/** Interest of unused months waived when a loan was repaid early; for a scheduled loan, the instalment it came from. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "loan_interest_waivers")
public class LoanInterestWaiver extends BaseEntity {

    @Column(name = "repayment_id", nullable = false)
    private UUID repaymentId;

    @Column(name = "installment_id")
    private UUID installmentId;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
}
