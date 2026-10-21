package rw.terimbere.csams.modules.socialfund.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialFundSummaryResponse {

    private BigDecimal balance;
    private BigDecimal totalApprovedContributions;
    private BigDecimal totalApprovedDisbursements;
    private long pendingContributions;
    private long pendingDisbursements;
    private String currency;
}
