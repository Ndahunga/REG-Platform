package rw.terimbere.csams.modules.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardAdvancedInsightsResponse {

    private Period period;
    private String currency;
    private String timezone;

    @Builder.Default
    private List<LargestActiveInvestmentRow> largestActiveInvestments = new ArrayList<>();

    @Builder.Default
    private List<FrequentBorrowerRow> frequentBorrowers = new ArrayList<>();

    private RepaymentReliabilitySection repaymentReliability;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Period {
        private int year;
        private LocalDate asOf;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepaymentReliabilitySection {
        /** Always {@code LIFETIME} for F2 MVP. */
        private String period;
        private int minimumSample;
        private long dataQualityExcludedTotal;

        @Builder.Default
        private List<RepaymentReliabilityRow> members = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepaymentReliabilityRow {
        private UUID memberId;
        private String displayName;
        private long installmentsDue;
        private long installmentsPaidOnTime;
        private long installmentsPaidLate;
        private long installmentsUnpaidPastDue;
        private long dataQualityExcluded;
        private BigDecimal onTimeRate;
        private int rank;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LargestActiveInvestmentRow {
        private UUID investmentId;
        private String name;
        private BigDecimal originalCapital;
        private BigDecimal remainingCapital;
        private BigDecimal profitReturned;
        private InvestmentStatus status;
        private int rank;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrequentBorrowerRow {
        private UUID memberId;
        private String displayName;
        private long numberOfLoansDisbursed;
        private BigDecimal totalPrincipalBorrowed;
        private int rank;
    }
}
