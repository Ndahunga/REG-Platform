package rw.terimbere.csams.modules.specialcontribution.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialCampaignStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecialCampaignResponse {

    private UUID id;
    private UUID cooperativeId;
    private String name;
    private String purpose;
    private String description;
    private BigDecimal suggestedAmount;
    private BigDecimal targetAmount;
    private LocalDate startDate;
    private LocalDate endDate;
    private SpecialCampaignStatus status;
    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
