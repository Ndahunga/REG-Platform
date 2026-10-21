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
public class InvestmentsByMonthPoint {

    private int month;
    private BigDecimal capitalDeployed;
    private long investmentCount;
}
