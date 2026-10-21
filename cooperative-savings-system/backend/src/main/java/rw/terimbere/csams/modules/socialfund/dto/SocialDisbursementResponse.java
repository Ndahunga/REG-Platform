package rw.terimbere.csams.modules.socialfund.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursementStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialDisbursementResponse {

    private UUID id;
    private UUID cooperativeId;
    private UUID beneficiaryMemberUserId;
    private BigDecimal amount;
    private LocalDate disbursementDate;
    private String reason;
    private String notes;
    private String evidenceFileKey;
    private SocialDisbursementStatus status;
    private UUID requestedBy;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String reviewNotes;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
}
