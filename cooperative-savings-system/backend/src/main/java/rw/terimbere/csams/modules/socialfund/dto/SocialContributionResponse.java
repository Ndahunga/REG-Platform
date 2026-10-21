package rw.terimbere.csams.modules.socialfund.dto;

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
import rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialContributionResponse {

    private UUID id;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberName;
    private BigDecimal amount;
    private LocalDate contributionDate;
    private String paymentReference;
    private String notes;
    private String evidenceFileKey;
    private SocialContributionStatus status;
    private UUID submittedBy;
    private String submittedByName;
    private UUID reviewedBy;
    private String reviewedByName;
    private Instant reviewedAt;
    private String reviewNotes;
    private List<ApprovalEventResponse> approvalHistory;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
}
