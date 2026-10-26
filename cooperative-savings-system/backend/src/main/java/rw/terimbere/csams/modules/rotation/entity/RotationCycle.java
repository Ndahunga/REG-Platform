package rw.terimbere.csams.modules.rotation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/** One rotation cycle of a Saving Scheme: an ordered, monthly list of recipients (see {@link RotationPeriod}). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rotation_cycles")
public class RotationCycle extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "cycle_number", nullable = false, updatable = false)
    private int cycleNumber;

    @Column(name = "start_year", nullable = false, updatable = false)
    private int startYear;

    @Column(name = "start_month", nullable = false, updatable = false)
    private int startMonth;

    @Column(name = "period_count", nullable = false, updatable = false)
    private int periodCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RotationCycleStatus status;

    @Column(name = "idempotency_key", length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "activated_by")
    private UUID activatedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 1000)
    private String cancelReason;

    /** PERIOD_COLLECTION: each month's recipient gets that month's collection. FIXED_PER_SHARE: each share has a turn. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payout_mode", nullable = false, length = 24)
    private RotationPayoutMode payoutMode = RotationPayoutMode.PERIOD_COLLECTION;

    /** What one share's turn pays (e.g. 50,000 x 12 = 600,000); set only for FIXED_PER_SHARE. */
    @Column(name = "payout_per_share", precision = 19, scale = 4)
    private java.math.BigDecimal payoutPerShare;

    @Enumerated(EnumType.STRING)
    @Column(name = "turn_mode", length = 24)
    private RotationTurnMode turnMode;

    public boolean isShareBased() {
        return payoutMode == RotationPayoutMode.FIXED_PER_SHARE;
    }
}
