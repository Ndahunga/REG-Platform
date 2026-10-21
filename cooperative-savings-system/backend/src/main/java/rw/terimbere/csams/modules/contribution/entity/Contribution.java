package rw.terimbere.csams.modules.contribution.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "contributions")
public class Contribution extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false)
    private UUID memberUserId;

    @Column(name = "\"year\"", nullable = false)
    private int year;

    @Column(name = "\"month\"", nullable = false)
    private int month;

    @Column(name = "share_count")
    private Integer shareCount;

    @Column(name = "expected_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedAmount;

    @Builder.Default
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingAmount;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ContributionStatus status;

    @Column(name = "payment_reference", length = 128)
    private String paymentReference;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "submitted_amount", precision = 19, scale = 4)
    private BigDecimal submittedAmount;

    @Column(name = "evidence_file_key", length = 512)
    private String evidenceFileKey;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 32)
    private ContributionReviewStatus reviewStatus;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @Builder.Default
    @Column(name = "ledger_revision", nullable = false)
    private int ledgerRevision = 0;

    // ---- Obligation snapshot (Task 3B-2). All NULL for rows that predate it; written once when the row is created
    // for the current or a future month and never rewritten, hence updatable = false.

    @Column(name = "obligation_snapshot_at", updatable = false)
    private Instant obligationSnapshotAt;

    @Column(name = "unit_amount_snapshot", precision = 19, scale = 4, updatable = false)
    private BigDecimal unitAmountSnapshot;

    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    @Column(name = "operating_model_snapshot", length = 16, updatable = false)
    private String operatingModelSnapshot;

    @Column(name = "rotation_configuration_id", updatable = false)
    private UUID rotationConfigurationId;

    @Column(name = "rotation_period_id", updatable = false)
    private UUID rotationPeriodId;

    @Column(name = "rotating_bps_snapshot", updatable = false)
    private Integer rotatingBpsSnapshot;

    @Column(name = "retained_bps_snapshot", updatable = false)
    private Integer retainedBpsSnapshot;
}
