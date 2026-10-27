package rw.terimbere.csams.modules.historicalimport.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalImportPreviewResponse {

    /** Format of an import created by the opening-balance migration. */
    public static final String FORMAT_OPENING_BALANCE = "OPENING_BALANCE";
    /** Format of a historical import created from the retired 16-sheet workbook (read-only history). */
    public static final String FORMAT_LEGACY = "LEGACY";

    private UUID importId;
    private HistoricalImportStatus status;
    private String format;

    /** OPENING_BALANCE (members and share capital) or OPENING_LOANS (the separate loan workbook). */
    private String kind;
    private String originalFilename;
    private String fileHash;
    private LocalDate cutoverDate;
    private int totalRows;
    private int validRows;
    private int invalidRows;
    private boolean confirmAllowed;
    private boolean reportReady;
    /** Opening-balance imports: members / share capital / loans figures. Null for legacy imports. */
    private HistoricalMigrationSummary migration;
    private List<HistoricalImportSheetSummary> sheets;
    private List<HistoricalImportError> errors;
    /** Legacy imports only. */
    private List<HistoricalYearSummary> yearSummaries;
    /** Legacy imports only. */
    private HistoricalReconciliationSummary reconciliation;
    private String errorSummary;
    /** Set when this upload corrects an earlier validated one, which it replaces when validated. */
    private UUID revisionOfImportId;
    private String revisionOfFilename;
    private java.time.Instant revisionOfConfirmedAt;
    /** Loans workbook revision: loans identical in both versions, kept as they are (with anything recorded on them). */
    private Integer unchangedLoans;
    /** Loans of the earlier version that this one removes or changes. */
    private Integer replacedLoans;
}
