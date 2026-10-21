package rw.terimbere.csams.modules.fine.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FineSettingsUpdateRequest {

    private Boolean autoFinesEnabled;

    @NotNull
    private FineCalculationMode fineMode;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal baseFineAmount;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal dailyIncrement;

    @NotNull
    @Min(0)
    @Max(365)
    private Integer graceDays;

    private String currency;
}
