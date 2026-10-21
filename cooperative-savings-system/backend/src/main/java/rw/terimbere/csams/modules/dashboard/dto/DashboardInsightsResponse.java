package rw.terimbere.csams.modules.dashboard.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.dashboard.support.MonthOverMonthCalculator.ChangeState;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardInsightsResponse {

    private Period period;
    private ContributionsInsights contributions;
    private LoansInsights loans;
    private FinesInsights fines;
    private String currency;
    private String timezone;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Period {
        private int year;
        private int month;
        private String label;
        private int previousYear;
        private int previousMonth;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContributionsInsights {
        /** Paid regular contributions for the current contribution period (year/month). */
        private BigDecimal currentMonth;
        private BigDecimal previousMonth;
        /** Null when previous month is zero and current &gt; 0 (no baseline). */
        private BigDecimal changePercent;
        private ChangeState changeState;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoansInsights {
        private long issuedCountCurrentMonth;
        private BigDecimal issuedAmountCurrentMonth;
        private long issuedCountPreviousMonth;
        private BigDecimal issuedAmountPreviousMonth;
        private BigDecimal issuedAmountChangePercent;
        private ChangeState issuedAmountChangeState;
        /**
         * Total repayment cash recorded this month ({@code amountTotal} = principal + interest +
         * penalty). Answers “how much loan money came back this month?”
         */
        private BigDecimal repaidCurrentMonth;
        private BigDecimal repaidPreviousMonth;
        private BigDecimal repaidChangePercent;
        private ChangeState repaidChangeState;
        /** Reuses outstanding principal for ACTIVE/OVERDUE loans. */
        private BigDecimal outstandingPrincipal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinesInsights {
        private long issuedCountCurrentMonth;
        private BigDecimal issuedAmountCurrentMonth;
        private BigDecimal collectedCurrentMonth;
        private BigDecimal collectedPreviousMonth;
        private BigDecimal collectedChangePercent;
        private ChangeState collectedChangeState;
    }
}
