package rw.terimbere.csams.modules.historicalimport.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Safe cell reading for import workbooks. Formula cells use cached results only —
 * no formula evaluator is created.
 */
public final class WorkbookCellReader {

    private static final DateTimeFormatter[] DATE_FORMATS = new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy")
    };

    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public String stringValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return cachedFormulaString(cell);
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatCellValue(cell).trim();
    }

    public String dateString(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
            if (cell.getCellType() == CellType.FORMULA
                    && cell.getCachedFormulaResultType() == CellType.NUMERIC
                    && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return stringValue(cell);
    }

    public static LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    public static BigDecimal parseAmount(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return MoneyUtils.scaleForStorage(new BigDecimal(raw.trim().replace(",", "")));
    }

    private String cachedFormulaString(Cell cell) {
        try {
            CellType cached = cell.getCachedFormulaResultType();
            if (cached == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                return formatter.formatCellValue(cell).trim();
            }
            if (cached == CellType.STRING) {
                return cell.getStringCellValue() == null ? "" : cell.getStringCellValue().trim();
            }
            if (cached == CellType.BOOLEAN) {
                return Boolean.toString(cell.getBooleanCellValue());
            }
        } catch (Exception ignored) {
            // ignore unreadable cached formula
        }
        return "";
    }
}
