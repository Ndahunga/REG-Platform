package rw.terimbere.csams.modules.loan.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanShareTierResponse {

    private UUID id;
    private BigDecimal minSharePercent;
    private BigDecimal maxLoanAmount;
}
