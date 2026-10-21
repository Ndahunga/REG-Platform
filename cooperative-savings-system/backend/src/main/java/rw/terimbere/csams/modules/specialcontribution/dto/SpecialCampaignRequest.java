package rw.terimbere.csams.modules.specialcontribution.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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
public class SpecialCampaignRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 512)
    private String purpose;

    @Size(max = 2000)
    private String description;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal suggestedAmount;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal targetAmount;

    private LocalDate startDate;

    private LocalDate endDate;

    @AssertTrue(message = "endDate must be on or after startDate")
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) {
            return true;
        }
        return !endDate.isBefore(startDate);
    }
}
