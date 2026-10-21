package rw.terimbere.csams.modules.specialcontribution.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecialContributionResponse {

    private UUID id;
    private UUID campaignId;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberName;
    private BigDecimal amount;
    private LocalDate contributionDate;
    private String paymentReference;
    private String notes;
    private SpecialContributionStatus status;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String reviewNotes;
    private UUID recordedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
