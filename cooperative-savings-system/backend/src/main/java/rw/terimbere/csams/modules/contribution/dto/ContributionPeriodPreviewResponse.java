package rw.terimbere.csams.modules.contribution.dto;

import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.contribution.entity.ContributionReviewStatus;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContributionPeriodPreviewResponse {

    private UUID contributionId;
    private UUID cooperativeId;
    private UUID memberUserId;
    private int year;
    private int month;
    private int shareCount;
    private BigDecimal requiredAmount;
    private BigDecimal paidAmount;
    private BigDecimal pendingSubmittedAmount;
    private BigDecimal remainingAmount;
    private LocalDate paymentDate;
    private LocalDate dueDate;
    private ContributionStatus status;
    private ContributionReviewStatus reviewStatus;
    private boolean awaitingReview;
    private boolean canSubmit;
    /** Obligation view. Expectations only: not received money. dueDate is null when the scheme has no configured due day. */
    private boolean overdue;
    private BigDecimal overdueAmount;
    private SchemeOperatingModel operatingModel;
    private Integer rotatingBps;
    private Integer retainedBps;
    private BigDecimal expectedRotatingAmount;
    private BigDecimal expectedRetainedAmount;
}
