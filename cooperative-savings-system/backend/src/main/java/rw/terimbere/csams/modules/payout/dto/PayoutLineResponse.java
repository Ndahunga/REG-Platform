package rw.terimbere.csams.modules.payout.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.payout.entity.PayoutLineStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutLineResponse {

    private UUID id;
    private UUID payoutRunId;
    private UUID cooperativeId;
    private UUID memberUserId;
    private BigDecimal eligibleContributionAmount;
    private BigDecimal percentage;
    private BigDecimal payoutAmount;
    private PayoutLineStatus status;
    private Instant createdAt;
}
