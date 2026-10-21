package rw.terimbere.csams.modules.contribution.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyContributionChartPoint {

    private int month;
    private BigDecimal totalPaid;
}
