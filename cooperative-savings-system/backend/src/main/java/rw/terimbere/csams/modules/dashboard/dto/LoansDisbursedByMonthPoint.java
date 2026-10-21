package rw.terimbere.csams.modules.dashboard.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoansDisbursedByMonthPoint {

    private int month;
    private long loanCount;
    private BigDecimal principalAmount;
}
