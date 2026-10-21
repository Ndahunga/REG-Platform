package rw.terimbere.csams.modules.report.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.report.dto.ReportHeaderMeta;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;

class ExcelReportWriterTest {

    @Test
    void headerUsesSavingSchemeInsteadOfCooperative() throws Exception {
        byte[] xlsx = ExcelReportWriter.write(
                ReportHeaderMeta.builder()
                        .cooperativeName("Demo Scheme")
                        .reportTitle("Contributions")
                        .selectedPeriod("2026-01-01 to 2026-08-20")
                        .generatedAt(Instant.parse("2026-08-20T10:00:00Z"))
                        .generatedBy("admin")
                        .currency("RWF")
                        .build(),
                List.of(ReportSheetData.builder()
                        .sheetName("Contributions")
                        .headers(List.of("Member", "Amount"))
                        .rows(List.of(List.of("Jane", "100")))
                        .build()));

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet cover = workbook.getSheet("Report Header");
            Row first = cover.getRow(0);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("Saving Scheme");
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("Demo Scheme");
            assertThat(first.getCell(0).getStringCellValue()).doesNotContain("Cooperative");
        }
    }
}
