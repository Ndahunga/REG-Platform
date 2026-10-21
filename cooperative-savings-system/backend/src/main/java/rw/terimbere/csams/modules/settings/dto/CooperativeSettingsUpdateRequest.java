package rw.terimbere.csams.modules.settings.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CooperativeSettingsUpdateRequest {

    @NotBlank
    @Size(max = 64)
    private String timezone;

    @NotBlank
    @Size(max = 16)
    private String locale;

    private Boolean notifyContributions;
    private Boolean notifyLoans;
    private Boolean notifyFines;
    private Boolean notifyPayouts;

    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 15, fraction = 4)
    private BigDecimal baseSharePrice;

    /** When true, persist NULL. Distinct from omitting baseSharePrice, which leaves the stored value unchanged. */
    private Boolean clearBaseSharePrice;
}
