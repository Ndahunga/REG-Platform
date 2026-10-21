package rw.terimbere.csams.modules.historicalimport.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet.Fields;

/**
 * Builds the header-only migration workbooks (the two-sheet opening balances and the one-sheet loans), bold frozen header,
 * column widths and column formats (dates, numbers, text). It contains no sample rows, so nothing in the
 * template can be imported by accident.
 */
final class MigrationTemplateWriter {

    private static final Set<String> DATE_COLUMNS = Set.of(Fields.ISSUED_DATE, Fields.LAST_PAYMENT_DATE);
    private static final Set<String> MONEY_COLUMNS = Set.of(
            Fields.TOTAL_CONTRIBUTIONS,
            Fields.SHARE_CAPITAL,
            Fields.LOAN_AMOUNT,
            Fields.AMOUNT_PAID,
            Fields.OUTSTANDING_PRINCIPAL);
    private static final Set<String> INTEGER_COLUMNS = Set.of(Fields.SHARE_COUNT, Fields.TERM);
    private static final Set<String> RATE_COLUMNS = Set.of(Fields.INTEREST_RATE);
    /** Text so Excel keeps leading zeros (phone, national id). */
    private static final Set<String> TEXT_COLUMNS =
            Set.of(Fields.USERNAME, Fields.EMAIL, Fields.PHONE, Fields.NATIONAL_ID, Fields.FULL_NAME, Fields.REPAYMENT_TYPE);

    private MigrationTemplateWriter() {}

    /** The two-sheet opening-balance template: members and share capital. */
    static byte[] write() {
        return write(List.of(
                HistoricalMigrationSheet.MEMBER_REGISTRATION, HistoricalMigrationSheet.SHARE_CAPITAL_REGISTRATION));
    }

    /** The separate one-sheet template for loans still outstanding at the cutover. */
    static byte[] writeLoans() {
        return write(List.of(HistoricalMigrationSheet.LOAN_REGISTRATION));
    }

    private static byte[] write(List<HistoricalMigrationSheet> sheets) {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            DataFormat format = workbook.createDataFormat();
            CellStyle date = style(workbook, format.getFormat("yyyy-mm-dd"));
            CellStyle money = style(workbook, format.getFormat("#,##0.00"));
            CellStyle integer = style(workbook, format.getFormat("0"));
            CellStyle rate = style(workbook, format.getFormat("0.00##"));
            CellStyle text = style(workbook, format.getFormat("@"));

            for (HistoricalMigrationSheet type : sheets) {
                Sheet sheet = workbook.createSheet(type.getSheetName());
                List<String> headers = type.getHeaders();
                Row headerRow = sheet.createRow(0);
                for (int c = 0; c < headers.size(); c++) {
                    String name = headers.get(c);
                    Cell cell = headerRow.createCell(c);
                    cell.setCellValue(name);
                    cell.setCellStyle(header);
                    sheet.setColumnWidth(c, Math.min(60, Math.max(16, name.length() + 4)) * 256);
                    CellStyle column = DATE_COLUMNS.contains(name)
                            ? date
                            : MONEY_COLUMNS.contains(name)
                                    ? money
                                    : INTEGER_COLUMNS.contains(name)
                                            ? integer
                                            : RATE_COLUMNS.contains(name)
                                                    ? rate
                                                    : TEXT_COLUMNS.contains(name) ? text : null;
                    if (column != null) {
                        sheet.setDefaultColumnStyle(c, column);
                    }
                }
                sheet.createFreezePane(0, 1);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build migration template", ex);
        }
    }

    private static CellStyle style(Workbook workbook, short dataFormat) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(dataFormat);
        return style;
    }
}
