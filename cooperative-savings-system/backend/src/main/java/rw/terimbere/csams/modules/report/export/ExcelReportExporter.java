package rw.terimbere.csams.modules.report.export;

import java.util.List;
import rw.terimbere.csams.modules.report.dto.ReportHeaderMeta;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;

/**
 * Excel workbook helper kept for contribution import templates. Report downloads use
 * {@link PdfReportExporter}.
 */
public class ExcelReportExporter implements ReportExporter {

    public static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override
    public byte[] export(ReportHeaderMeta header, List<ReportSheetData> sheets) {
        return ExcelReportWriter.write(header, sheets);
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public String fileExtension() {
        return "xlsx";
    }
}
