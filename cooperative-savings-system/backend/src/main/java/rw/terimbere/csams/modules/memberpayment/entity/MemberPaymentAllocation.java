package rw.terimbere.csams.modules.memberpayment.entity;

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
import rw.terimbere.csams.modules.membercredit.CreditPurpose;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * One target of a {@link MemberPayment}. The scheme and member are copied from the payment and tied to it by a
 * composite foreign key, so an allocation can never silently belong to someone else's records. Only the
 * loan-repayment breakdown may change after creation, and only while the payment is still being reviewed.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "member_payment_allocations")
public class MemberPaymentAllocation extends BaseEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private UUID memberUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_type", nullable = false, updatable = false, length = 32)
    private PaymentAllocationType allocationType;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "period_year", updatable = false)
    private Integer periodYear;

    @Column(name = "period_month", updatable = false)
    private Integer periodMonth;

    @Column(name = "loan_id", updatable = false)
    private UUID loanId;

    /** MEMBER_CREDIT allocations only: why the excess is held (a LOAN_PREPAYMENT also carries its loan). */
    @Enumerated(EnumType.STRING)
    @Column(name = "credit_purpose", updatable = false, length = 24)
    private CreditPurpose creditPurpose;

    @Column(name = "principal_amount", precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", precision = 19, scale = 4)
    private BigDecimal interestAmount;

    @Column(name = "penalty_amount", precision = 19, scale = 4)
    private BigDecimal penaltyAmount;

    public boolean hasBreakdown() {
        return principalAmount != null && interestAmount != null && penaltyAmount != null;
    }
}
