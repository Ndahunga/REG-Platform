package rw.terimbere.csams.modules.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanInstallmentResponse {

    private UUID id;
    private int installmentNumber;
    private LocalDate dueDate;
    private BigDecimal openingPrincipalBalance;
    private BigDecimal paymentAmount;
    private BigDecimal scheduledInstallmentAmount;
    private BigDecimal principalComponent;
    private BigDecimal interestComponent;
    private BigDecimal penaltyDue;
    private BigDecimal remainingPrincipal;
    private LoanInstallmentStatus status;
    private BigDecimal amountPaid;
    private BigDecimal balance;
    private BigDecimal principalPaid;
    private BigDecimal interestPaid;
    private BigDecimal penaltyPaid;
    private BigDecimal remainingAmount;
}
