package rw.terimbere.csams.modules.loan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.loan.entity.LoanGuarantorStatus;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanGuarantorResponse {

    private UUID id;
    private UUID cooperativeId;
    private UUID loanId;
    private UUID borrowerUserId;
    private String borrowerName;
    private BigDecimal loanAmount;
    private LoanStatus loanStatus;
    private UUID guarantorUserId;
    private String guarantorName;
    private BigDecimal guaranteedAmount;
    private LoanGuarantorStatus status;
    private UUID requestedBy;
    private Instant requestedAt;
    private Instant respondedAt;
    private String responseComment;
}
