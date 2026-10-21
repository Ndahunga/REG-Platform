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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMemberInsightsResponse {

    private Period period;
    private String currency;
    private String timezone;

    @Builder.Default
    private List<TopContributorRow> topContributors = new ArrayList<>();

    @Builder.Default
    private List<FineFollowUpRow> fineFollowUp = new ArrayList<>();

    @Builder.Default
    private List<OverdueLoanRow> overdueLoans = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Period {
        private LocalDate start;
        private LocalDate end;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopContributorRow {
        private UUID memberId;
        private String displayName;
        private BigDecimal amount;
        private int rank;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FineFollowUpRow {
        private UUID memberId;
        private String displayName;
        private BigDecimal issuedAmount;
        private BigDecimal paidAmount;
        private BigDecimal outstandingAmount;
        private long fineCount;
        private int rank;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverdueLoanRow {
        private UUID memberId;
        private String displayName;
        private long overdueLoanCount;
        private BigDecimal outstandingPrincipal;
        private LocalDate oldestDueDate;
        private int rank;
    }
}
