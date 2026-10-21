package rw.terimbere.csams.modules.report.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.report.dto.ReportHeaderMeta;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;

class PdfReportWriterTest {

    @Test
    void writesPdfDocument() {
        byte[] pdf = PdfReportWriter.write(
                ReportHeaderMeta.builder()
                        .cooperativeName("Demo Coop")
                        .reportTitle("Contributions")
                        .selectedPeriod("2026-01-01 to 2026-08-20")
                        .generatedAt(Instant.parse("2026-08-20T10:00:00Z"))
                        .generatedBy("admin")
                        .currency("RWF")
                        .build(),
                List.of(ReportSheetData.builder()
                        .sheetName("Contributions")
                        .headers(List.of("Member", "Amount"))
                        .rows(List.of(List.of("Jane", new BigDecimal("100.0000"))))
                        .totalsRow(List.of("TOTAL", new BigDecimal("100.0000")))
                        .build()));

        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void writesPdfWithKinyarwandaAndSmartPunctuation() {
        byte[] pdf = PdfReportWriter.write(
                ReportHeaderMeta.builder()
                        .cooperativeName("Ikibina cy’Iterambere")
                        .reportTitle("Contributions")
                        .selectedPeriod("2026-01-01 to 2026-08-22")
                        .generatedAt(Instant.parse("2026-08-22T08:00:00Z"))
                        .generatedBy("luanda vicky")
                        .currency("RWF")
                        .build(),
                List.of(ReportSheetData.builder()
                        .sheetName("Contributions")
                        .headers(List.of("Member", "Amount"))
                        .rows(List.of(List.of("Mugwaneza Jean-Baptiste", new BigDecimal("20000.0000"))))
                        .totalsRow(List.of("TOTAL", new BigDecimal("20000.0000")))
                        .build()));

        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void brandsPdfWithLogoCandaraAndSystemNavy() throws IOException {
        assertThat(PdfReportStyle.hasLogo()).isTrue();
        assertThat(PdfReportStyle.hasCandara()).isTrue();
        assertThat(PdfReportStyle.BRAND_BLUE.getRGB()).isEqualTo(new java.awt.Color(0x1B, 0x4D, 0x8C).getRGB());

        byte[] pdf = PdfReportWriter.write(
                ReportHeaderMeta.builder()
                        .cooperativeName("Demo Coop")
                        .reportTitle("Contributions")
                        .selectedPeriod("2026-01-01 to 2026-08-20")
                        .generatedAt(Instant.parse("2026-08-20T10:00:00Z"))
                        .generatedBy("admin")
                        .currency("RWF")
                        .build(),
                List.of(ReportSheetData.builder()
                        .sheetName("Contributions")
                        .headers(List.of("Member", "Amount"))
                        .rows(List.of(List.of("Jane", new BigDecimal("100.0000"))))
                        .build()));

        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("Candara");

        String text = extractPdfText(pdf).toUpperCase(Locale.ROOT);
        assertThat(text).contains("SAVING SCHEME");
        assertThat(text).doesNotContain("COOPERATIVE");
    }

    private static String extractPdfText(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                String pageText = extractor.getTextFromPage(page);
                if (pageText != null) {
                    text.append(pageText).append('\n');
                }
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void logoPreservesAspectRatioAndDoesNotUpscale() {
        com.lowagie.text.Image logo = PdfReportStyle.logo();
        assertThat(logo).isNotNull();
        assertThat(logo.getScaledWidth()).isLessThanOrEqualTo(168f);
        assertThat(logo.getScaledHeight()).isLessThanOrEqualTo(46f);
        assertThat(logo.getScaledWidth()).isLessThanOrEqualTo(logo.getPlainWidth() + 0.01f);
        assertThat(logo.getScaledHeight()).isLessThanOrEqualTo(logo.getPlainHeight() + 0.01f);
        assertThat(logo.getScaledWidth() / logo.getScaledHeight())
                .isCloseTo(logo.getPlainWidth() / logo.getPlainHeight(), org.assertj.core.data.Offset.offset(0.01f));
    }
}
