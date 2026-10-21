package rw.terimbere.csams.modules.loan.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApproveRequest {

    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal approvedAmount;

    @Min(1)
    @Max(600)
    private Integer termMonths;

    /**
     * Ignored. Maturity is set at disbursement from the repayment schedule
     * (or {@code disbursementDate + termMonths} for legacy / reducing loans).
     * Retained so older API clients can still send the field.
     */
    private LocalDate dueDate;
}
