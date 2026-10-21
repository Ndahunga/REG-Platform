package rw.terimbere.csams.modules.memberpayment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * The immutable record of one allocation actually applied to the books. Posted history is never updated or deleted:
 * the entity is {@link Immutable} and UNIQUE (allocation_id) makes a second posting of the same allocation impossible.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Immutable
@Entity
@Table(name = "member_payment_postings")
public class MemberPaymentPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "allocation_id", nullable = false, updatable = false)
    private UUID allocationId;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private UUID memberUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_type", nullable = false, updatable = false, length = 32)
    private PaymentAllocationType allocationType;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Column(name = "posted_by", nullable = false, updatable = false)
    private UUID postedBy;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "contribution_id", updatable = false)
    private UUID contributionId;

    @Column(name = "period_year", updatable = false)
    private Integer periodYear;

    @Column(name = "period_month", updatable = false)
    private Integer periodMonth;

    @Column(name = "paid_before", updatable = false, precision = 19, scale = 4)
    private BigDecimal paidBefore;

    @Column(name = "paid_after", updatable = false, precision = 19, scale = 4)
    private BigDecimal paidAfter;

    @Column(name = "ledger_entry_id", updatable = false)
    private UUID ledgerEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "attribution", nullable = false, updatable = false, length = 16)
    private PostingAttribution attribution;

    @Column(name = "operating_model", updatable = false, length = 16)
    private String operatingModel;

    @Column(name = "rotating_bps", updatable = false)
    private Integer rotatingBps;

    @Column(name = "retained_bps", updatable = false)
    private Integer retainedBps;

    @Column(name = "rotating_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal rotatingAmount;

    @Column(name = "retained_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal retainedAmount;

    @Column(name = "rotation_period_id", updatable = false)
    private UUID rotationPeriodId;

    @Column(name = "original_recipient_user_id", updatable = false)
    private UUID originalRecipientUserId;

    @Column(name = "loan_id", updatable = false)
    private UUID loanId;

    @Column(name = "loan_repayment_id", updatable = false)
    private UUID loanRepaymentId;

    @Column(name = "principal_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal interestAmount;

    @Column(name = "penalty_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal penaltyAmount;
}
