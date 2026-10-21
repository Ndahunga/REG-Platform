package rw.terimbere.csams.modules.loan.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
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
public class LoanRepaymentCreateRequest {

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    @NotNull
    @PastOrPresent(message = "Payment date cannot be in the future")
    private LocalDate paymentDate;

    private String paymentReference;

    private String notes;

    /** When true (default), interest outstanding is paid before principal. */
    @Builder.Default
    private Boolean allocateInterestFirst = true;
}
