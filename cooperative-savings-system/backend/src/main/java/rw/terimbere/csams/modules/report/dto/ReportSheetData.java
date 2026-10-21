package rw.terimbere.csams.modules.report.dto;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ReportSheetData {
    String sheetName;
    List<String> headers;
    List<List<Object>> rows;
    /** Optional totals row values aligned to headers (null cells skip). */
    List<Object> totalsRow;
}
