package rw.terimbere.csams.modules.memberpayment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * What a payment reversal gave back to one loan: the principal, interest and penalty restored, and the loan's position
 * before and after. Insert-only; UNIQUE (posting_id) and UNIQUE (loan_repayment_id) make a second reversal impossible.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Immutable
@Entity
@Table(name = "member_payment_loan_reversals")
public class MemberPaymentLoanReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reversal_id", nullable = false, updatable = false)
    private UUID reversalId;

    @Column(name = "posting_id", nullable = false, updatable = false)
    private UUID postingId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private UUID memberUserId;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "loan_repayment_id", nullable = false, updatable = false)
    private UUID loanRepaymentId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "principal_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal interestAmount;

    @Column(name = "penalty_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal penaltyAmount;

    @Column(name = "outstanding_principal_before", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal outstandingPrincipalBefore;

    @Column(name = "outstanding_principal_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal outstandingPrincipalAfter;

    @Column(name = "outstanding_interest_before", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal outstandingInterestBefore;

    @Column(name = "outstanding_interest_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal outstandingInterestAfter;

    @Column(name = "outstanding_penalty_before", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal outstandingPenaltyBefore;

    @Column(name = "outstanding_penalty_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal outstandingPenaltyAfter;

    @Column(name = "loan_status_before", nullable = false, updatable = false, length = 32)
    private String loanStatusBefore;

    @Column(name = "loan_status_after", nullable = false, updatable = false, length = 32)
    private String loanStatusAfter;

    @Column(name = "principal_ledger_entry_id", updatable = false)
    private UUID principalLedgerEntryId;

    @Column(name = "interest_ledger_entry_id", updatable = false)
    private UUID interestLedgerEntryId;

    @Column(name = "reversed_by", nullable = false, updatable = false)
    private UUID reversedBy;

    @Column(name = "reversed_at", nullable = false, updatable = false)
    private Instant reversedAt;
}
