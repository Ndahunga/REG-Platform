package rw.terimbere.csams.modules.loan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanRepaymentResponse {

    private UUID id;
    private UUID loanId;
    private UUID cooperativeId;
    private UUID memberUserId;
    private LocalDate paymentDate;
    private BigDecimal amountTotal;
    private BigDecimal principalPortion;
    private BigDecimal interestPortion;
    private BigDecimal penaltyPortion;
    private String paymentReference;
    private String notes;
    private UUID recordedBy;
    private Instant createdAt;
    /** True when a payment reversal gave this repayment back to the loan. */
    private boolean reversed;
}
