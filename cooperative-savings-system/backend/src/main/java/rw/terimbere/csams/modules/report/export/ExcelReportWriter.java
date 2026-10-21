package rw.terimbere.csams.modules.report.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import rw.terimbere.csams.modules.report.dto.ReportHeaderMeta;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;

/**
 * Apache POI helper for contribution import templates (and optional Excel report fallback).
 */
public final class ExcelReportWriter {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private ExcelReportWriter() {}

    public static byte[] write(ReportHeaderMeta header, List<ReportSheetData> sheets) {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = boldStyle(workbook);
            CellStyle headerStyle = boldStyle(workbook);

            Sheet cover = workbook.createSheet("Report Header");
            writeHeaderBlock(cover, header, bold);

            if (sheets == null || sheets.isEmpty()) {
                Sheet empty = workbook.createSheet("Data");
                writeHeaderBlock(empty, header, bold);
                Row note = empty.createRow(8);
                note.createCell(0).setCellValue("No records for selected filters");
            } else {
                for (ReportSheetData sheetData : sheets) {
                    String name = sanitizeSheetName(sheetData.getSheetName());
                    Sheet sheet = workbook.createSheet(name);
                    int rowIdx = writeHeaderBlock(sheet, header, bold);
                    rowIdx++;
                    writeTable(sheet, sheetData, rowIdx, headerStyle);
                    autosize(sheet, Math.max(2, sheetData.getHeaders() == null ? 2 : sheetData.getHeaders().size()));
                }
            }

            autosize(cover, 2);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build Excel report", ex);
        }
    }

    public static byte[] writeTemplate(String sheetName, List<String> headers, List<List<Object>> sampleRows) {
        return writeMultiSheetTemplate(List.of(new TemplateSheet(sheetName, headers, sampleRows, null)));
    }

    /**
     * Multi-sheet workbook used by historical import templates. Instruction-only sheets
     * pass {@code instructions} and omit a header row.
     */
    public static byte[] writeMultiSheetTemplate(List<TemplateSheet> sheets) {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = boldStyle(workbook);
            CellStyle wrap = workbook.createCellStyle();
            wrap.setWrapText(true);
            for (TemplateSheet spec : sheets) {
                Sheet sheet = workbook.createSheet(sanitizeSheetName(spec.name()));
                if (spec.instructions() != null && !spec.instructions().isEmpty()) {
                    int r = 0;
                    for (String line : spec.instructions()) {
                        Row row = sheet.createRow(r++);
                        Cell cell = row.createCell(0);
                        cell.setCellValue(line == null ? "" : line);
                        cell.setCellStyle(wrap);
                    }
                    sheet.setColumnWidth(0, 80 * 256);
                    continue;
                }
                List<String> headers = spec.headers() == null ? List.of() : spec.headers();
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers.get(i));
                    cell.setCellStyle(headerStyle);
                }
                if (spec.sampleRows() != null) {
                    int r = 1;
                    for (List<Object> rowValues : spec.sampleRows()) {
                        writeValues(sheet.createRow(r++), rowValues);
                    }
                }
                autosize(sheet, Math.max(1, headers.size()));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build Excel template", ex);
        }
    }

    public record TemplateSheet(
            String name, List<String> headers, List<List<Object>> sampleRows, List<String> instructions) {}

    private static int writeHeaderBlock(Sheet sheet, ReportHeaderMeta header, CellStyle bold) {
        int row = 0;
        row = writeLabelValue(sheet, row, ReportLabels.SCHEME, header.getCooperativeName(), bold);
        row = writeLabelValue(sheet, row, "Report", header.getReportTitle(), bold);
        row = writeLabelValue(sheet, row, "Selected period", header.getSelectedPeriod(), bold);
        row = writeLabelValue(
                sheet,
                row,
                "Generated date",
                header.getGeneratedAt() == null ? "" : DATE_TIME.format(header.getGeneratedAt()),
                bold);
        row = writeLabelValue(sheet, row, "Generated by", header.getGeneratedBy(), bold);
        row = writeLabelValue(sheet, row, "Currency", header.getCurrency(), bold);
        return row;
    }

    private static int writeLabelValue(Sheet sheet, int rowIdx, String label, String value, CellStyle bold) {
        Row row = sheet.createRow(rowIdx);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(bold);
        row.createCell(1).setCellValue(value == null ? "" : value);
        return rowIdx + 1;
    }

    private static void writeTable(Sheet sheet, ReportSheetData data, int startRow, CellStyle headerStyle) {
        List<String> headers = data.getHeaders() == null ? List.of() : data.getHeaders();
        Row headerRow = sheet.createRow(startRow);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = startRow + 1;
        if (data.getRows() != null) {
            for (List<Object> values : data.getRows()) {
                writeValues(sheet.createRow(rowIdx++), values);
            }
        }

        if (data.getTotalsRow() != null && !data.getTotalsRow().isEmpty()) {
            Row totals = sheet.createRow(rowIdx);
            writeValues(totals, data.getTotalsRow());
            CellStyle bold = boldStyle(sheet.getWorkbook());
            for (Cell cell : totals) {
                cell.setCellStyle(bold);
            }
        }
    }

    private static void writeValues(Row row, List<Object> values) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            setCellValue(row.createCell(i), values.get(i));
        }
    }

    private static void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof BigDecimal bd) {
            cell.setCellValue(bd.doubleValue());
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            return;
        }
        if (value instanceof LocalDate date) {
            cell.setCellValue(DATE.format(date));
            return;
        }
        if (value instanceof Instant instant) {
            cell.setCellValue(DATE_TIME.format(instant));
            return;
        }
        if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private static CellStyle boldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private static String sanitizeSheetName(String name) {
        if (name == null || name.isBlank()) {
            return "Sheet1";
        }
        String cleaned = name.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (cleaned.length() > 31) {
            cleaned = cleaned.substring(0, 31);
        }
        return cleaned.isBlank() ? "Sheet1" : cleaned;
    }
}
