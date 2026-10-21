package rw.terimbere.csams.modules.historicalimport.dto;

import java.time.Instant;
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
public class HistoricalImportSummaryResponse {

    private UUID id;
    private UUID cooperativeId;
    private String originalFilename;
    private String fileHash;
    private HistoricalImportStatus status;
    private String format;

    /** OPENING_BALANCE (members and share capital) or OPENING_LOANS (the separate loan workbook). */
    private String kind;
    private java.time.LocalDate cutoverDate;
    private int totalRows;
    private int validRows;
    private int invalidRows;
    private UUID uploadedBy;
    private Instant createdAt;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private String errorSummary;
}
