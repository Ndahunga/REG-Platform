package rw.terimbere.csams.modules.contribution.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.audit.dto.ApprovalEventResponse;
import rw.terimbere.csams.modules.contribution.entity.ContributionReviewStatus;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContributionResponse {

    private UUID id;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberName;
    private int year;
    private int month;
    private Integer shareCount;
    private BigDecimal expectedAmount;
    private BigDecimal paidAmount;
    private BigDecimal outstandingAmount;
    private BigDecimal remainingAmount;
    private LocalDate paymentDate;
    private ContributionStatus status;
    private String paymentReference;
    private String notes;
    private UUID recordedBy;
    private BigDecimal submittedAmount;
    private String evidenceFileKey;
    private UUID submittedBy;
    private String submittedByName;
    private Instant submittedAt;
    private UUID reviewedBy;
    private String reviewedByName;
    private Instant reviewedAt;
    private ContributionReviewStatus reviewStatus;
    private String rejectionReason;
    private List<ApprovalEventResponse> approvalHistory;
    private boolean persisted;
    /** Obligation view. Expectations only: none of these amounts is collected or received money. */
    private LocalDate dueDate;
    private boolean overdue;
    private BigDecimal overdueAmount;
    private boolean obligationSnapshotted;
    private SchemeOperatingModel operatingModel;
    private Integer rotatingBps;
    private Integer retainedBps;
    private BigDecimal expectedRotatingAmount;
    private BigDecimal expectedRetainedAmount;
    private UUID rotationPeriodId;
    private Instant createdAt;
    private Instant updatedAt;
}
