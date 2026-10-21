package rw.terimbere.csams.modules.payout.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
public class PayoutPreviewRequest {

    /** Preferred period bounds (inclusive), applied to payment_date / contribution_date. */
    private LocalDate periodFrom;

    private LocalDate periodTo;

    /** Year-only range alternative when periodFrom/periodTo are absent. */
    @Min(2000)
    @Max(2100)
    private Integer fromYear;

    @Min(2000)
    @Max(2100)
    private Integer toYear;

    /** Year+month range alternative when periodFrom/periodTo are absent. */
    @Min(1)
    @Max(12)
    private Integer fromMonth;

    @Min(1)
    @Max(12)
    private Integer toMonth;

    private Boolean includeRegular;

    private Boolean includeSpecial;

    /** Optional explicit pool; must be &lt;= available fund. Defaults to full available fund. */
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal payoutPoolAmount;

    @Size(max = 255)
    private String name;

    @Size(max = 2000)
    private String notes;

    @AssertTrue(message = "periodTo must be on or after periodFrom")
    public boolean isPeriodRangeValid() {
        if (periodFrom == null || periodTo == null) {
            return true;
        }
        return !periodTo.isBefore(periodFrom);
    }
}
