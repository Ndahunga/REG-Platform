package rw.terimbere.csams.modules.historicalimport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalImportSheetSummary {

    private String sheet;
    private int totalRows;
    private int validRows;
    private int invalidRows;
}
