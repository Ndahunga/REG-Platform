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
public class ContributionPeriodSummaryResponse {

    private int year;
    private Integer month;
    private BigDecimal expectedTotal;
    private BigDecimal paidTotal;
    private BigDecimal outstandingTotal;
    private long memberCount;
    private long paidCount;
    private long pendingCount;
    private String currency;
}
