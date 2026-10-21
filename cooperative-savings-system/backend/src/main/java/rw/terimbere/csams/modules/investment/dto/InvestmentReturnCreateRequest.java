package rw.terimbere.csams.modules.investment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
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
public class InvestmentReturnCreateRequest {

    @NotNull
    @PastOrPresent(message = "Return date cannot be in the future")
    private LocalDate returnDate;

    @Builder.Default
    @DecimalMin(value = "0.00", inclusive = true)
    private BigDecimal capitalPortion = BigDecimal.ZERO;

    @Builder.Default
    @DecimalMin(value = "0.00", inclusive = true)
    private BigDecimal profitPortion = BigDecimal.ZERO;

    @Size(max = 2000)
    private String notes;

    @Size(max = 128)
    private String reference;
}
