package rw.terimbere.csams.modules.fine.dto;

import jakarta.validation.constraints.DecimalMin;
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
public class FineUpdateRequest {

    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    @Size(max = 2000)
    private String reason;

    @Size(max = 2000)
    private String notes;

    private LocalDate dueDate;
}
