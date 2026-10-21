package rw.terimbere.csams.modules.report.export;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import rw.terimbere.csams.modules.report.dto.ReportHeaderMeta;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;

/**
 * Landscape PDF with the OuWealth logo, navy accents, and Candara table type.
 */
public final class PdfReportWriter {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private PdfReportWriter() {}

    public static byte[] write(ReportHeaderMeta header, List<ReportSheetData> sheets) {
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 42, 42);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new FooterEvent(header));
            document.open();
            writeBanner(document, header);
            writeMeta(document, header);

            if (sheets == null || sheets.isEmpty()) {
                document.add(body("No records for the selected filters."));
            } else {
                for (ReportSheetData sheetData : sheets) {
                    document.add(sectionTitle(sheetData.getSheetName()));
                    document.add(buildTable(sheetData));
                }
            }

            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build PDF report", ex);
        } finally {
            if (document.isOpen()) {
                try {
                    document.close();
                } catch (Exception ignored) {
                    // Prefer the original exception from the try block.
                }
            }
        }
    }

    private static void writeBanner(Document document, ReportHeaderMeta header) throws DocumentException {
        Image logo = PdfReportStyle.logo();
        PdfPTable banner = new PdfPTable(logo == null ? 1 : 2);
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(0);
        if (logo != null) {
            banner.setWidths(new float[] {2.15f, 7.85f});
            PdfPCell logoCell = new PdfPCell(logo, false);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setBackgroundColor(null);
            logoCell.setPadding(0);
            logoCell.setPaddingRight(10);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            banner.addCell(logoCell);
        }

        PdfPTable titles = new PdfPTable(1);
        titles.setWidthPercentage(100);
        titles.addCell(textCell(
                pdfText(header.getReportTitle(), "Report").toUpperCase(Locale.ROOT),
                PdfReportStyle.heading(16, PdfReportStyle.BRAND_BLUE),
                Element.ALIGN_LEFT,
                4,
                4));

        PdfPCell titlesCell = new PdfPCell(titles);
        titlesCell.setBorder(Rectangle.NO_BORDER);
        titlesCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titlesCell.setPadding(2);
        banner.addCell(titlesCell);
        document.add(banner);

        PdfPTable accent = new PdfPTable(1);
        accent.setWidthPercentage(100);
        accent.setSpacingAfter(10);
        PdfPCell bar = new PdfPCell(new Phrase(" "));
        bar.setBackgroundColor(PdfReportStyle.BRAND_ORANGE);
        bar.setBorder(Rectangle.NO_BORDER);
        bar.setFixedHeight(4f);
        accent.addCell(bar);
        document.add(accent);
    }

    private static void writeMeta(Document document, ReportHeaderMeta header) throws DocumentException {
        Font labelFont = PdfReportStyle.tableBold(8, PdfReportStyle.MUTED);
        Font valueFont = PdfReportStyle.table(9, Color.BLACK);

        PdfPTable meta = new PdfPTable(4);
        meta.setWidthPercentage(100);
        meta.setSpacingAfter(14);
        meta.setWidths(new float[] {1.15f, 2.35f, 1.15f, 2.35f});

        addMeta(meta, ReportLabels.SCHEME, header.getCooperativeName(), labelFont, valueFont);
        addMeta(meta, "Selected period", header.getSelectedPeriod(), labelFont, valueFont);
        addMeta(
                meta,
                "Generated",
                header.getGeneratedAt() == null ? "" : DATE_TIME.format(header.getGeneratedAt()),
                labelFont,
                valueFont);
        addMeta(meta, "Generated by", header.getGeneratedBy(), labelFont, valueFont);
        addMeta(meta, "Currency", header.getCurrency(), labelFont, valueFont);
        document.add(meta);
    }

    private static void addMeta(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label.toUpperCase(Locale.ROOT), labelFont));
        labelCell.setBackgroundColor(PdfReportStyle.META_BG);
        labelCell.setBorderColor(PdfReportStyle.GRID);
        labelCell.setPadding(6);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(pdfText(value, ""), valueFont));
        valueCell.setBackgroundColor(PdfReportStyle.META_BG);
        valueCell.setBorderColor(PdfReportStyle.GRID);
        valueCell.setPadding(6);
        table.addCell(valueCell);
    }

    private static Paragraph sectionTitle(String name) {
        Paragraph paragraph = new Paragraph(pdfText(name, "Data"), PdfReportStyle.heading(11, PdfReportStyle.BRAND_BLUE));
        paragraph.setSpacingBefore(8);
        paragraph.setSpacingAfter(6);
        return paragraph;
    }

    private static Paragraph body(String text) {
        Paragraph paragraph = new Paragraph(text, PdfReportStyle.table(10, PdfReportStyle.MUTED));
        paragraph.setSpacingBefore(8);
        return paragraph;
    }

    private static PdfPTable buildTable(ReportSheetData data) {
        List<String> headers = data.getHeaders() == null ? List.of() : data.getHeaders();
        int columns = Math.max(1, headers.size());
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSpacingAfter(14);
        table.setWidths(columnWidths(columns));

        Font headerFont = PdfReportStyle.tableBold(8, Color.WHITE);
        Font cellFont = PdfReportStyle.table(8, Color.BLACK);
        Font totalFont = PdfReportStyle.tableBold(8, Color.BLACK);

        if (headers.isEmpty()) {
            table.addCell(headerCell("Value", headerFont));
        } else {
            for (String heading : headers) {
                table.addCell(headerCell(heading, headerFont));
            }
        }

        List<List<Object>> rows = data.getRows() == null ? List.of() : data.getRows();
        if (rows.isEmpty()) {
            PdfPCell empty = bodyCell("No records for the selected filters.", cellFont, false, false);
            empty.setColspan(columns);
            empty.setHorizontalAlignment(Element.ALIGN_CENTER);
            empty.setPadding(12);
            table.addCell(empty);
        } else {
            int rowIdx = 0;
            for (List<Object> values : rows) {
                boolean zebra = rowIdx++ % 2 == 1;
                for (int i = 0; i < columns; i++) {
                    Object value = values != null && i < values.size() ? values.get(i) : null;
                    table.addCell(bodyCell(formatValue(value), cellFont, zebra, isNumeric(value)));
                }
            }
        }

        if (data.getTotalsRow() != null && !data.getTotalsRow().isEmpty()) {
            for (int i = 0; i < columns; i++) {
                Object value = i < data.getTotalsRow().size() ? data.getTotalsRow().get(i) : null;
                PdfPCell cell = bodyCell(formatValue(value), totalFont, false, isNumeric(value));
                cell.setBackgroundColor(PdfReportStyle.TOTALS_BG);
                cell.setBorderColor(PdfReportStyle.BRAND_ORANGE);
                table.addCell(cell);
            }
        }

        return table;
    }

    private static float[] columnWidths(int columns) {
        float[] widths = new float[columns];
        for (int i = 0; i < columns; i++) {
            widths[i] = i == 0 ? 1.55f : 1f;
        }
        return widths;
    }

    private static PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(pdfText(text, ""), font));
        cell.setBackgroundColor(PdfReportStyle.BRAND_BLUE);
        cell.setBorderColor(PdfReportStyle.BRAND_BLUE_DARK);
        cell.setPadding(7);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPCell bodyCell(String text, Font font, boolean zebra, boolean numeric) {
        PdfPCell cell = new PdfPCell(new Phrase(pdfText(text, ""), font));
        cell.setPadding(5);
        cell.setPaddingLeft(6);
        cell.setPaddingRight(6);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(PdfReportStyle.GRID);
        cell.setHorizontalAlignment(numeric ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        if (zebra) {
            cell.setBackgroundColor(PdfReportStyle.ZEBRA);
        }
        return cell;
    }

    private static PdfPCell textCell(String text, Font font, int alignment, float padTop, float padBottom) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setPaddingTop(padTop);
        cell.setPaddingBottom(padBottom);
        cell.setPaddingLeft(0);
        cell.setPaddingRight(0);
        return cell;
    }

    private static boolean isNumeric(Object value) {
        return value instanceof Number;
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal bd) {
            return formatDecimal(bd);
        }
        if (value instanceof Number number) {
            if (number instanceof Double || number instanceof Float) {
                return formatDecimal(BigDecimal.valueOf(number.doubleValue()));
            }
            return String.valueOf(number);
        }
        if (value instanceof LocalDate date) {
            return DATE.format(date);
        }
        if (value instanceof Instant instant) {
            return DATE_TIME.format(instant);
        }
        if (value instanceof Boolean bool) {
            return bool ? "Yes" : "No";
        }
        return pdfText(String.valueOf(value), "");
    }

    private static String formatDecimal(BigDecimal value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat format = new DecimalFormat("#,##0.##", symbols);
        format.setGroupingUsed(true);
        format.setMaximumFractionDigits(2);
        BigDecimal stripped = value.stripTrailingZeros();
        format.setMinimumFractionDigits(stripped.scale() > 0 ? 2 : 0);
        return format.format(stripped);
    }

    /**
     * Built-in Helvetica is WinAnsi-only. Candara is embedded with Identity-H, so letters stay as-is.
     */
    private static String pdfText(String text, String blankFallback) {
        if (text == null || text.isBlank()) {
            return blankFallback == null ? "" : blankFallback;
        }
        String normalized = text
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u00A0', ' ');
        if (PdfReportStyle.hasCandara()) {
            return normalized.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        }
        StringBuilder safe = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                safe.append(' ');
            } else if (ch == ' ' || ch == '\'' || ch == '"' || (ch >= 0x20 && ch <= 0x7E) || (ch >= 0xA0 && ch <= 0xFF)) {
                safe.append(ch);
            } else {
                safe.append('?');
            }
        }
        return safe.toString();
    }

    private static final class FooterEvent extends PdfPageEventHelper {
        private final String line;

        private FooterEvent(ReportHeaderMeta header) {
            String coop = pdfText(header == null ? "" : header.getCooperativeName(), "");
            this.line = coop.isBlank() ? "Confidential" : coop + "  ·  Confidential";
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfPTable footer = new PdfPTable(2);
            footer.setTotalWidth(document.right() - document.left());
            footer.setLockedWidth(true);
            Font font = PdfReportStyle.table(7, PdfReportStyle.MUTED);

            PdfPCell left = new PdfPCell(new Phrase(line, font));
            left.setBorder(Rectangle.TOP);
            left.setBorderColor(PdfReportStyle.GRID);
            left.setPaddingTop(6);
            footer.addCell(left);

            PdfPCell right = new PdfPCell(new Phrase("Page " + writer.getPageNumber(), font));
            right.setBorder(Rectangle.TOP);
            right.setBorderColor(PdfReportStyle.GRID);
            right.setHorizontalAlignment(Element.ALIGN_RIGHT);
            right.setPaddingTop(6);
            footer.addCell(right);

            footer.writeSelectedRows(0, -1, document.left(), document.bottom() - 12, writer.getDirectContent());
        }
    }
}
