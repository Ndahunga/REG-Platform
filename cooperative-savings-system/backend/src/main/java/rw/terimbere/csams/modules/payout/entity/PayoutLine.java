package rw.terimbere.csams.modules.payout.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Frozen payout share snapshot for a member within a run. Confirmed amounts are immutable.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payout_lines")
@EntityListeners(AuditingEntityListener.class)
public class PayoutLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payout_run_id", nullable = false, updatable = false)
    private UUID payoutRunId;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private UUID memberUserId;

    @Column(name = "eligible_contribution_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal eligibleContributionAmount;

    @Column(name = "percentage", nullable = false, precision = 19, scale = 8, updatable = false)
    private BigDecimal percentage;

    /** Frozen at preview; never recalculated after confirm. */
    @Column(name = "payout_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal payoutAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PayoutLineStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
