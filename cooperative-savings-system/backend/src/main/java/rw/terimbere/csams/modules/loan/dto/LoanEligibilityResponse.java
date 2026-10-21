package rw.terimbere.csams.modules.loan.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanEligibilityResponse {

    private UUID memberUserId;
    private boolean eligible;
    private String reason;
    private BigDecimal existingLoanAmount;
    private BigDecimal amountAlreadyRepaid;
    private BigDecimal outstandingBalance;
    private BigDecimal requestedAmount;
    private Integer shareCount;
    private Long totalShares;
    private BigDecimal sharePercent;
    private BigDecimal maxLoanByShares;
    private BigDecimal maxEligibleAmount;
}
