package rw.terimbere.csams.modules.loanrepayment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable repayment record. Prefer insert-only; no business updates.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "loan_repayments")
@EntityListeners(AuditingEntityListener.class)
public class LoanRepayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private UUID memberUserId;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Column(name = "amount_total", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amountTotal;

    @Column(name = "principal_portion", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal principalPortion;

    @Column(name = "interest_portion", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal interestPortion;

    @Builder.Default
    @Column(name = "penalty_portion", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal penaltyPortion = BigDecimal.ZERO;

    @Column(name = "payment_reference", length = 128, updatable = false)
    private String paymentReference;

    @Column(name = "notes", length = 2000, updatable = false)
    private String notes;

    @Column(name = "recorded_by", updatable = false)
    private UUID recordedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Set once, when a payment reversal gave this repayment back to the loan. The repayment itself is never edited or
     * deleted; a reversed repayment no longer counts in any total or report.
     */
    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    /** Set on a corrected repayment: the repayment it replaces (which is reversed). */
    @Column(name = "revision_of_id", updatable = false)
    private UUID revisionOfId;

    /** Why the earlier repayment was revised. */
    @Column(name = "revision_reason", length = 500, updatable = false)
    private String revisionReason;

    public boolean isReversed() {
        return reversedAt != null;
    }

    @Version
    @Column(nullable = false)
    private Long version;
}
