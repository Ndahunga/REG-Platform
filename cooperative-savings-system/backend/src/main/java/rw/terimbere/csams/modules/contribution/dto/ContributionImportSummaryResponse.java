package rw.terimbere.csams.modules.contribution.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.contribution.entity.ContributionImportStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContributionImportSummaryResponse {

    private UUID id;
    private UUID cooperativeId;
    private int year;
    private int month;
    private String originalFilename;
    private ContributionImportStatus status;
    private int totalRows;
    private int validRows;
    private int invalidRows;
    private UUID uploadedBy;
    private UUID confirmedBy;
    private Instant confirmedAt;
    private String errorSummary;
    private Instant createdAt;
    private Instant updatedAt;
}
