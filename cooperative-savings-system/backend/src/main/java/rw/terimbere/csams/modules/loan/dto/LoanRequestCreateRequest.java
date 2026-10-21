package rw.terimbere.csams.modules.loan.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.loan.entity.LoanGuaranteeMode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanRequestCreateRequest {

    /** Required for admin-issued loans; ignored for member self-requests (forced to current user). */
    private UUID memberUserId;

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    @Min(1)
    @Max(600)
    private Integer termMonths;

    /** When true, first-period interest uses a 30-day financial-month fraction. */
    private Boolean prorataEnabled;

    @Min(0)
    @Max(30)
    private Integer firstPeriodDays;

    private String purpose;

    /** SELF = loan on your own; GUARANTOR = loan that needs umwishingizi. */
    private LoanGuaranteeMode guaranteeMode;

    private UUID guarantorUserId;

    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal guaranteedAmount;
}
