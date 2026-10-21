package rw.terimbere.csams.modules.socialfund.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialFundSettingsResponse {

    private UUID id;
    private UUID cooperativeId;
    private BigDecimal suggestedContributionAmount;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
