package rw.terimbere.csams.modules.payout.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutStatementResponse {

    private UUID runId;
    private String cooperativeName;
    private String name;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private Instant generatedAt;
    private PayoutRunStatus status;
    private String currency;
    private BigDecimal availableFundSnapshot;
    private BigDecimal payoutPoolAmount;
    private BigDecimal totalEligibleContributions;
    private BigDecimal totalPayoutAmount;
    private List<PayoutLineResponse> lines;
}
