package rw.terimbere.csams.modules.historicalimport.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Preview / result figures of an opening-balance migration. Persisted as JSON in
 * {@code historical_imports.migration_summary}; absent for legacy imports.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalMigrationSummary {

    private LocalDate cutoverDate;
    /** Explicit cash available at the end of the cutover date (not derived from any sheet). */
    private BigDecimal openingAvailableFunds;
    private MemberSection members;
    private ShareSection shareCapital;
    private LoanSection loans;
    /** Set once the import is confirmed. */
    private HistoricalImportConfirmResponse result;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberSection {
        private int totalRows;
        private int validRows;
        private int invalidRows;
        private BigDecimal totalOpeningContributions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShareSection {
        private int totalRows;
        private int validRows;
        private int invalidRows;
        private long totalOpeningShares;
        private BigDecimal totalOpeningShareCapital;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoanSection {
        private int totalRows;
        private int validRows;
        private int invalidRows;
        private int endOfTermCount;
        private int monthlyCount;
        private BigDecimal totalOriginalPrincipal;
        private BigDecimal totalOutstandingPrincipal;
        private BigDecimal totalAmountPaid;
    }
}
