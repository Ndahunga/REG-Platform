package rw.terimbere.csams.modules.report.export;

import java.util.List;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.report.dto.ReportHeaderMeta;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;

@Component
public class PdfReportExporter implements ReportExporter {

    public static final String CONTENT_TYPE = "application/pdf";

    @Override
    public byte[] export(ReportHeaderMeta header, List<ReportSheetData> sheets) {
        return PdfReportWriter.write(header, sheets);
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public String fileExtension() {
        return "pdf";
    }
}
