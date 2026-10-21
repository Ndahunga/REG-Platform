package rw.terimbere.csams.modules.report.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExportRequest {

    @NotNull
    private ReportType reportType;

    @PastOrPresent(message = "fromDate cannot be in the future")
    private LocalDate fromDate;

    @PastOrPresent(message = "toDate cannot be in the future")
    private LocalDate toDate;

    private UUID memberUserId;

    private String status;

    private LedgerTransactionType transactionType;

    @Min(2000)
    @Max(2100)
    private Integer year;

    @Min(1)
    @Max(12)
    private Integer month;

    @AssertTrue(message = "toDate must be on or after fromDate")
    public boolean isDateRangeValid() {
        if (fromDate == null || toDate == null) {
            return true;
        }
        return !toDate.isBefore(fromDate);
    }

    @AssertTrue(message = "year and month must both be provided or both omitted")
    public boolean isYearMonthPairValid() {
        return (year == null && month == null) || (year != null && month != null);
    }
}
