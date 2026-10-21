package rw.terimbere.csams.modules.report.export;

import java.util.List;
import rw.terimbere.csams.modules.report.dto.ReportHeaderMeta;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;

/**
 * Pluggable report binary exporter. PDF is the default implementation
 * ({@link PdfReportExporter}). Excel remains available for contribution import templates.
 */
public interface ReportExporter {

    byte[] export(ReportHeaderMeta header, List<ReportSheetData> sheets);

    String contentType();

    String fileExtension();
}
