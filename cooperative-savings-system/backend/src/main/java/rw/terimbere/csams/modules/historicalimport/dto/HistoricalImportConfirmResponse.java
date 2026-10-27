package rw.terimbere.csams.modules.historicalimport.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;

/** Result of confirming an opening-balance migration. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalImportConfirmResponse {

    private UUID importId;
    private HistoricalImportStatus status;
    private LocalDate cutoverDate;
    private BigDecimal openingAvailableFunds;

    private int membersImported;
    private int memberOpeningBalancesImported;
    private int shareOpeningBalancesImported;
    private int loansImported;
    private int loanInstallmentsCreated;

    private BigDecimal totalHistoricalContributions;
    private long totalHistoricalShares;
    private BigDecimal totalHistoricalShareCapital;

    private BigDecimal totalOriginalLoanAmount;
    private BigDecimal totalOutstandingLoanPrincipal;
    private BigDecimal totalHistoricalLoanPayments;

    /** Correction of an earlier upload: members it left out that were kept because records exist for them. */
    private java.util.List<String> membersLeftOutButKept;
}
