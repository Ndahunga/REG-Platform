package rw.terimbere.csams.modules.historicalimport.transactions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.historicalimport.service.WorkbookCellReader;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * The historical transaction workbook: exactly two worksheets, fixed names and fixed headers.
 *
 * <ol>
 *   <li><b>Members and Configuration</b>: the roster of members the history refers to ({@code MEMBER} rows) and the
 *       settings the history is reconciled against ({@code SETTING} rows: opening balance, declared closing balance, end
 *       date, currency, operating model).
 *   <li><b>Historical Transactions</b>: one dated row per event, with an explicit {@code Row Type}.
 * </ol>
 *
 * The template carries no sample rows, so nothing in it can be imported by accident. Column notes are attached to the
 * header cells.
 */
public final class HistoricalTxWorkbook {

    public static final String MEMBERS_SHEET = "Members and Configuration";
    public static final String TRANSACTIONS_SHEET = "Historical Transactions";
    public static final int MAX_ROWS = 5000;

    public static final List<String> MEMBER_HEADERS =
            List.of("Row Type", "Username", "Full Name", "Share Count", "Setting", "Value");
    public static final List<String> TX_HEADERS = List.of(
            "Row Type",
            "Date",
            "Member Username",
            "Amount",
            "Original Reference",
            "Period Year",
            "Period Month",
            "Loan Reference",
            "Operating Model At The Time",
            "Description");

    private static final Map<String, String> MEMBER_NOTES = Map.of(
            "Row Type", "MEMBER or SETTING. MEMBER rows list members the history refers to; SETTING rows configure the reconciliation.",
            "Username", "MEMBER rows: the username of an existing member of this Saving Scheme. Members are never created by this import.",
            "Share Count", "MEMBER rows, optional. Only compared with the current share count; it never changes it.",
            "Setting", "SETTING rows: OPENING_BALANCE, DECLARED_CLOSING_BALANCE (both required), HISTORY_END_DATE, CURRENCY, OPERATING_MODEL.",
            "Value", "SETTING rows: the amount, date (yyyy-mm-dd), currency code or model.");
    private static final Map<String, String> TX_NOTES = Map.ofEntries(
            Map.entry("Row Type", "CONTRIBUTION, FINE_PAYMENT, OTHER_INCOME or GENERAL_EXPENSE. Anything else is rejected: loans and member payouts cannot be imported as history (use the opening-balance loan migration for loans)."),
            Map.entry("Date", "The ORIGINAL date of the event (yyyy-mm-dd). Must be after 2000, not in the future, and before the first live record of the scheme."),
            Map.entry("Member Username", "Required except for OTHER_INCOME and GENERAL_EXPENSE. An existing member of this Saving Scheme."),
            Map.entry("Amount", "Positive, at most four decimals."),
            Map.entry("Original Reference", "The receipt, page or transaction reference in the original books. Required except for CONTRIBUTION."),
            Map.entry("Period Year", "CONTRIBUTION only: the contribution period."),
            Map.entry("Period Month", "CONTRIBUTION only: 1 to 12."),
            Map.entry("Loan Reference", "Not used: loan rows are not importable. Leave blank."),
            Map.entry("Operating Model At The Time", "CONTRIBUTION only, optional: ACCUMULATING, ROTATING or HYBRID. Leave blank when unknown: the amount is then reported as unresolved and is never payout-eligible."),
            Map.entry("Description", "Optional free text (up to 500 characters)."));

    /** A raw row: the cell texts by header. */
    public record RawRow(String sheet, int rowNumber, Map<String, String> cells) {
        public String get(String header) {
            String value = cells.get(header);
            return value == null ? "" : value.trim();
        }
    }

    /** The parsed workbook: raw rows per sheet. Structural problems are thrown, never reported per row. */
    public record Parsed(List<RawRow> members, List<RawRow> transactions) {}

    private HistoricalTxWorkbook() {}

    // ------------------------------------------------------------------ template

    public static byte[] template() {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            DataFormat format = workbook.createDataFormat();
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(format.getFormat("@"));
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(format.getFormat("yyyy-mm-dd"));
            CellStyle money = workbook.createCellStyle();
            money.setDataFormat(format.getFormat("#,##0.00##"));
            CellStyle integer = workbook.createCellStyle();
            integer.setDataFormat(format.getFormat("0"));
            writeSheet(workbook, MEMBERS_SHEET, MEMBER_HEADERS, MEMBER_NOTES, header, Map.of("Username", text, "Full Name", text, "Value", text, "Share Count", integer));
            writeSheet(workbook, TRANSACTIONS_SHEET, TX_HEADERS, TX_NOTES, header, Map.of(
                    "Date", date, "Amount", money, "Member Username", text, "Original Reference", text, "Loan Reference", text,
                    "Period Year", integer, "Period Month", integer));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build the historical transaction template", e);
        }
    }

    private static void writeSheet(
            Workbook workbook,
            String name,
            List<String> headers,
            Map<String, String> notes,
            CellStyle headerStyle,
            Map<String, CellStyle> columnStyles) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        CreationHelper helper = workbook.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        for (int c = 0; c < headers.size(); c++) {
            String title = headers.get(c);
            Cell cell = row.createCell(c);
            cell.setCellValue(title);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(c, Math.min(60, Math.max(18, title.length() + 6)) * 256);
            CellStyle column = columnStyles.get(title);
            if (column != null) {
                sheet.setDefaultColumnStyle(c, column);
            }
            String note = notes.get(title);
            if (note != null) {
                ClientAnchor anchor = helper.createClientAnchor();
                anchor.setCol1(c);
                anchor.setCol2(c + 3);
                anchor.setRow1(0);
                anchor.setRow2(6);
                Comment comment = drawing.createCellComment(anchor);
                comment.setString(helper.createRichTextString(note));
                cell.setCellComment(comment);
            }
        }
        sheet.createFreezePane(0, 1);
    }

    // ------------------------------------------------------------------ parsing

    public static Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw ValidationException.coded("FILE_REQUIRED", "An Excel file is required");
        }
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 2) {
                throw ValidationException.coded("WORKBOOK_SHEETS_INVALID", "The workbook must contain exactly two worksheets: \"" + MEMBERS_SHEET
                        + "\" and \"" + TRANSACTIONS_SHEET + "\". Download the template.");
            }
            Sheet members = workbook.getSheet(MEMBERS_SHEET);
            Sheet transactions = workbook.getSheet(TRANSACTIONS_SHEET);
            if (members == null || transactions == null) {
                throw ValidationException.coded("WORKBOOK_SHEET_NAMES_INVALID", "The worksheets must be named exactly \"" + MEMBERS_SHEET + "\" and \""
                        + TRANSACTIONS_SHEET + "\". Download the template.");
            }
            WorkbookCellReader reader = new WorkbookCellReader();
            return new Parsed(
                    read(members, MEMBERS_SHEET, MEMBER_HEADERS, reader),
                    read(transactions, TRANSACTIONS_SHEET, TX_HEADERS, reader));
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw ValidationException.coded("FILE_UNREADABLE", "The file is not a readable .xlsx workbook");
        }
    }

    private static List<RawRow> read(Sheet sheet, String name, List<String> headers, WorkbookCellReader reader) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            throw ValidationException.coded("WORKBOOK_HEADER_MISSING", "Sheet \"" + name + "\" has no header row. Download the template.");
        }
        for (int c = 0; c < headers.size(); c++) {
            String actual = reader.stringValue(headerRow.getCell(c));
            if (!normalize(headers.get(c)).equals(normalize(actual))) {
                throw ValidationException.coded("WORKBOOK_HEADERS_INVALID", "Sheet \"" + name + "\" headers must be exactly: " + String.join(", ", headers)
                        + ". Download the template.");
            }
        }
        List<RawRow> rows = new ArrayList<>();
        for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            Map<String, String> cells = new LinkedHashMap<>();
            boolean blank = true;
            for (int c = 0; c < headers.size(); c++) {
                String header = headers.get(c);
                String value = header.toLowerCase(Locale.ROOT).contains("date")
                        ? reader.dateString(row.getCell(c))
                        : reader.stringValue(row.getCell(c));
                if (StringUtils.hasText(value)) {
                    blank = false;
                }
                cells.put(header, value == null ? "" : value.trim());
            }
            if (blank) {
                continue;
            }
            if (rows.size() >= MAX_ROWS) {
                throw ValidationException.coded("WORKBOOK_TOO_MANY_ROWS", "Sheet \"" + name + "\" has more than " + MAX_ROWS
                        + " rows. Split the history into several files.");
            }
            rows.add(new RawRow(name.equals(MEMBERS_SHEET) ? "MEMBERS" : "TRANSACTIONS", i + 1, cells));
        }
        return rows;
    }

    private static String normalize(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
