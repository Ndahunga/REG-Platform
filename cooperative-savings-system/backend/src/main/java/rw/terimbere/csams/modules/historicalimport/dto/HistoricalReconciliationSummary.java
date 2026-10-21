package rw.terimbere.csams.modules.historicalimport.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalReconciliationSummary {

    private BigDecimal currentAvailableFund;
    private BigDecimal projectedCredits;
    private BigDecimal projectedDebits;
    private BigDecimal projectedOutstandingLoanPrincipal;
    private BigDecimal projectedAvailableFund;
    private BigDecimal projectedSocialContributions;
    private BigDecimal projectedSocialDisbursements;
    private BigDecimal projectedSocialBalance;
    private BigDecimal projectedPayouts;
    private boolean blocked;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Builder.Default
    private List<String> errors = new ArrayList<>();

    @Builder.Default
    private List<HistoricalYearSummary> yearSummaries = new ArrayList<>();
}
