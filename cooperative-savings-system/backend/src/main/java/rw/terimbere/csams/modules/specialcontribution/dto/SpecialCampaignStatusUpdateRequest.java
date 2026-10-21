package rw.terimbere.csams.modules.specialcontribution.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialCampaignStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecialCampaignStatusUpdateRequest {

    @NotNull
    private SpecialCampaignStatus status;
}
