package rw.terimbere.csams.modules.historicalimport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalImportError {

    private String sheet;
    private Integer rowNumber;
    private String field;
    private String code;
    private String message;
}
