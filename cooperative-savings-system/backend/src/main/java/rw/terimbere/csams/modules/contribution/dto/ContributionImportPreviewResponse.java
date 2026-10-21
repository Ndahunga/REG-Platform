package rw.terimbere.csams.modules.contribution.dto;

import java.util.List;
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
public class ContributionImportPreviewResponse {

    private UUID importId;
    private int year;
    private int month;
    private ContributionImportStatus status;
    private int validCount;
    private int invalidCount;
    private int totalRows;
    private List<ContributionImportPreviewRowResponse> rows;
}
