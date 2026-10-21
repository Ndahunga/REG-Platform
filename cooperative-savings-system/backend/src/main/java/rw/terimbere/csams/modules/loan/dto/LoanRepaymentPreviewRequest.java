package rw.terimbere.csams.modules.loan.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
public class LoanRepaymentPreviewRequest {

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    @Min(1)
    @Max(600)
    private Integer termMonths;

    @Builder.Default
    private boolean prorataEnabled = false;

    @Min(0)
    @Max(30)
    private Integer firstPeriodDays;

    /** Optional date used to derive first-period days when prorata is on and days are omitted. */
    private LocalDate referenceDate;
}
