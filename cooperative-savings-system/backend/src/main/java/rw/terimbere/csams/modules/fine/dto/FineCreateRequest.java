package rw.terimbere.csams.modules.fine.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FineCreateRequest {

    @NotNull
    private UUID memberUserId;

    private FineCalculationMode calculationMode;

    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal baseAmount;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal dailyIncrement;

    @Min(0)
    private Integer overdueDays;

    @Size(max = 2000)
    private String reason;

    @Size(max = 2000)
    private String notes;

    @PastOrPresent(message = "Issued date cannot be in the future")
    private LocalDate issuedDate;

    private LocalDate dueDate;

    @AssertTrue(message = "dueDate must be on or after issuedDate")
    public boolean isDueDateValid() {
        if (dueDate == null || issuedDate == null) {
            return true;
        }
        return !dueDate.isBefore(issuedDate);
    }
}
