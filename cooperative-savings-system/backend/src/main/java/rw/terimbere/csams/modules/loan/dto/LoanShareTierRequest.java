package rw.terimbere.csams.modules.loan.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanShareTierRequest {

    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true)
    @DecimalMax(value = "100.0", inclusive = true)
    private BigDecimal minSharePercent;

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal maxLoanAmount;
}
