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

/** What a reversal took back from one posted contribution allocation. Insert-only; UNIQUE (posting_id). */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Immutable
@Entity
@Table(name = "member_payment_posting_reversals")
public class MemberPaymentPostingReversal {

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

    @Column(name = "contribution_id", nullable = false, updatable = false)
    private UUID contributionId;

    @Column(name = "period_year", nullable = false, updatable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false, updatable = false)
    private int periodMonth;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "paid_before", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal paidBefore;

    @Column(name = "paid_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal paidAfter;

    @Column(name = "rotating_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal rotatingAmount;

    @Column(name = "retained_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal retainedAmount;

    @Column(name = "rotation_period_id", updatable = false)
    private UUID rotationPeriodId;

    @Column(name = "original_recipient_user_id", updatable = false)
    private UUID originalRecipientUserId;

    @Column(name = "ledger_entry_id", updatable = false)
    private UUID ledgerEntryId;

    @Column(name = "reversed_by", nullable = false, updatable = false)
    private UUID reversedBy;

    @Column(name = "reversed_at", nullable = false, updatable = false)
    private Instant reversedAt;
}
