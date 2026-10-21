package rw.terimbere.csams.modules.memberpayment.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Principal / interest / penalty split of a loan-repayment allocation. Recording it does not repay the loan: it is
 * information a reviewer must supply before the payment can be verified. The three parts must add up to the
 * allocation amount (checked by the service and by the database).
 */
@Data
@NoArgsConstructor
public class LoanBreakdownRequest {

    @NotNull
    @DecimalMin("0")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal principalAmount;

    @NotNull
    @DecimalMin("0")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal interestAmount;

    @NotNull
    @DecimalMin("0")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal penaltyAmount;

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown field: " + name);
    }
}
