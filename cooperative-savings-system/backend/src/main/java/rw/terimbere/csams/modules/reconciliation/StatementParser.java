package rw.terimbere.csams.modules.reconciliation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads the lines of a bank or mobile-money statement: a PDF with a text layer (such as an Equity Bank statement), an
 * Excel export or a CSV file. Each line gives its date, narration, amount, direction (money in or out) and running
 * balance when the statement shows one.
 *
 * <p>The direction of a PDF line is taken from the running balance (the balance went up by the amount: money in), so a
 * column layout never has to be guessed. A "Grand Total" line, when present, is used to check the reading. Nothing is
 * invented: a line that cannot be read is reported, not guessed.
 */
public final class StatementParser {

    public record Line(int number, LocalDate date, LocalDate valueDate, String narration, String reference,
            boolean in, BigDecimal amount, BigDecimal balance) {}

    public record Parsed(List<Line> lines, BigDecimal openingBalance, BigDecimal closingBalance, String account,
            List<String> warnings) {}

    private static final String DATE = "(\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{2,4})";
    private static final String AMOUNT = "-?\\(?[\\d,]+\\.\\d{2}\\)?(?:\\s?(?:CR|DR|Cr|Dr))?";
    private static final Pattern ROW = Pattern.compile("^" + DATE + "(?:\\s+" + DATE + ")?\\s+(.*?)\\s+(" + AMOUNT + ")"
            + "(?:\\s+(" + AMOUNT + "))?(?:\\s+(" + AMOUNT + "))?$");
    private static final Pattern TOTAL = Pattern.compile("(?i)^(?:grand\\s+)?totals?\\b.*?(" + AMOUNT + ")\\s+(" + AMOUNT + ")(?:\\s+(" + AMOUNT + "))?$");
    private static final Pattern NOT_NARRATION = Pattern.compile("(?i)^\\d+\\s*/\\s*\\d+$|head office|tran date|value date|grand total"
            + "|account statement|statement period|^note:|^end$|^_+$|customer name|branch id|product name|currency\\s*:|^to,$|page \\d+");
    private static final Pattern ENDS_WITH_AMOUNT = Pattern.compile(".*" + AMOUNT + "$");
    private static final Pattern ACCOUNT = Pattern.compile("(?i)account (?:statement|number|no\\.?)\\s*:?\\s*(\\d{6,20})");
    private static final Pattern REFERENCE = Pattern.compile("\\b(?=[A-Z0-9]*\\d)[A-Z0-9]{8,}\\b");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d-M-uuuu"), DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("uuuu-M-d"), DateTimeFormatter.ofPattern("d-M-uu"), DateTimeFormatter.ofPattern("d/M/uu"),
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-uu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d MMM uu", Locale.ENGLISH));

    private StatementParser() {}

    public static Parsed parse(String filename, byte[] bytes) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf") || (bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F')) {
            return parseText(pdfText(bytes));
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return parseTable(excelRows(bytes));
        }
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return parseTable(csvRows(new String(bytes, StandardCharsets.UTF_8)));
        }
        throw new IllegalArgumentException("Upload the statement as a PDF, Excel (.xlsx) or CSV file");
    }

    // ------------------------------------------------------------------ PDF (text lines)

    static String pdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) {
                throw new IllegalArgumentException("The statement PDF is protected by a password; upload an unprotected copy");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (IOException e) {
            throw new IllegalArgumentException("The PDF could not be read");
        }
    }

    /** Lines of text (one statement line per text line, narration possibly continued on the next lines). */
    static Parsed parseText(String text) {
        List<String> warnings = new ArrayList<>();
        List<Row0> rows = new ArrayList<>();
        BigDecimal[] totals = null;
        String account = null;
        Row0 last = null;
        int continued = 0;
        for (String raw : text.split("\\R")) {
            String l = raw.replaceAll("\\s+", " ").trim();
            if (l.isEmpty()) {
                continue;
            }
            if (account == null) {
                Matcher a = ACCOUNT.matcher(l);
                if (a.find()) {
                    account = a.group(1);
                }
            }
            Matcher t = TOTAL.matcher(l);
            if (t.matches()) {
                totals = new BigDecimal[] {amount(t.group(1)), amount(t.group(2)), t.group(3) == null ? null : amount(t.group(3))};
                last = null;
                continue;
            }
            Matcher m = ROW.matcher(l);
            if (m.matches()) {
                LocalDate d = date(m.group(1));
                if (d != null) {
                    List<BigDecimal> amounts = new ArrayList<>();
                    for (int g = 4; g <= 6; g++) {
                        if (m.group(g) != null) {
                            amounts.add(amount(m.group(g)));
                        }
                    }
                    Row0 r = new Row0();
                    r.date = d;
                    r.valueDate = m.group(2) == null ? null : date(m.group(2));
                    r.narration = m.group(3);
                    if (amounts.size() >= 2) {
                        r.balance = amounts.get(amounts.size() - 1);
                        r.amount = amounts.get(amounts.size() - 2).abs();
                        if (amounts.size() == 3 && amounts.get(0).signum() != 0 && amounts.get(1).signum() != 0) {
                            warnings.add("Line dated " + d + " shows two amounts; check it");
                        }
                    } else {
                        r.amount = amounts.get(0).abs();
                        r.signed = amounts.get(0);
                    }
                    rows.add(r);
                    last = r;
                    continued = 0;
                    continue;
                }
            }
            if (NOT_NARRATION.matcher(l).find()) {
                last = null;
            } else if (last != null && continued < 2 && !ENDS_WITH_AMOUNT.matcher(l).matches()) {
                last.narration = last.narration + " " + l;
                continued++;
            }
        }
        // Direction from the running balance; the first line from the totals, or from what came after it.
        BigDecimal prev = null;
        for (Row0 r : rows) {
            if (prev != null && r.balance != null) {
                BigDecimal diff = r.balance.subtract(prev);
                if (diff.subtract(r.amount).abs().compareTo(new BigDecimal("0.01")) < 0) {
                    r.in = Boolean.TRUE;
                } else if (diff.add(r.amount).abs().compareTo(new BigDecimal("0.01")) < 0) {
                    r.in = Boolean.FALSE;
                }
            } else if (r.signed != null) {
                r.in = r.signed.signum() > 0;
            }
            prev = r.balance;
        }
        resolveUnknown(rows, totals, warnings);
        List<Line> lines = new ArrayList<>();
        int n = 0;
        BigDecimal opening = null;
        for (Row0 r : rows) {
            if (r.in == null) {
                warnings.add("Could not tell whether the line dated " + r.date + " (" + r.amount.toPlainString() + ") is money in or out; it was left out");
                continue;
            }
            if (opening == null && r.balance != null) {
                opening = r.in ? r.balance.subtract(r.amount) : r.balance.add(r.amount);
            }
            lines.add(new Line(++n, r.date, r.valueDate, cut(r.narration, 1000), reference(r.narration), r.in, r.amount, r.balance));
        }
        BigDecimal closing = rows.isEmpty() ? null : rows.get(rows.size() - 1).balance;
        if (totals != null) {
            BigDecimal out = lines.stream().filter(x -> !x.in()).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal in = lines.stream().filter(Line::in).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (out.compareTo(totals[0]) != 0 || in.compareTo(totals[1]) != 0) {
                warnings.add("The lines read add up to " + in.toPlainString() + " in and " + out.toPlainString()
                        + " out, but the statement's total says " + totals[1].toPlainString() + " in and " + totals[0].toPlainString() + " out");
            }
            if (totals[2] != null) {
                closing = totals[2];
            }
        }
        if (lines.isEmpty()) {
            warnings.add("No statement lines were found in this file");
        }
        return new Parsed(lines, opening, closing, account, warnings);
    }

    /** The first line (no balance before it) takes the direction that makes the statement's totals add up. */
    private static void resolveUnknown(List<Row0> rows, BigDecimal[] totals, List<String> warnings) {
        List<Row0> unknown = rows.stream().filter(r -> r.in == null).toList();
        if (unknown.isEmpty()) {
            return;
        }
        if (totals != null && unknown.size() == 1) {
            BigDecimal out = rows.stream().filter(r -> Boolean.FALSE.equals(r.in)).map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal in = rows.stream().filter(r -> Boolean.TRUE.equals(r.in)).map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            Row0 u = unknown.get(0);
            if (in.add(u.amount).compareTo(totals[1]) == 0) {
                u.in = Boolean.TRUE;
                return;
            }
            if (out.add(u.amount).compareTo(totals[0]) == 0) {
                u.in = Boolean.FALSE;
                return;
            }
        }
        for (Row0 u : unknown) {
            String n = u.narration.toUpperCase(Locale.ROOT);
            if (n.contains("CHARGE") || n.contains("FEE") || n.contains("TRF TO") || n.contains("WITHDRAW") || n.contains("COMMISSION")
                    || n.contains("LOAN TO") || n.contains("CHQ TO") || n.startsWith("TO ")) {
                u.in = Boolean.FALSE;
            } else if (n.contains("DEPOSIT") || n.contains("TPGYBILL") || n.startsWith("APP/") || n.startsWith("USSD/")) {
                u.in = Boolean.TRUE;
            }
        }
    }

    // ------------------------------------------------------------------ tables (Excel, CSV)

    static Parsed parseTable(List<List<String>> rows) {
        List<String> warnings = new ArrayList<>();
        int header = -1;
        int date = -1, valueDate = -1, narration = -1, debit = -1, credit = -1, amount = -1, balance = -1, ref = -1, type = -1;
        for (int i = 0; i < Math.min(rows.size(), 40) && header < 0; i++) {
            List<String> r = rows.get(i);
            int d = -1, vd = -1, na = -1, de = -1, cr = -1, am = -1, ba = -1, re = -1, ty = -1;
            for (int c = 0; c < r.size(); c++) {
                String h = r.get(c).toLowerCase(Locale.ROOT).trim();
                if (h.isEmpty()) {
                    continue;
                }
                if (h.contains("value date")) {
                    vd = c;
                } else if (d < 0 && (h.contains("date") || h.equals("when"))) {
                    d = c;
                } else if (na < 0 && (h.contains("narration") || h.contains("description") || h.contains("particulars")
                        || h.contains("details") || h.contains("remark") || h.contains("memo"))) {
                    na = c;
                } else if (de < 0 && (h.contains("debit") || h.contains("withdraw") || h.contains("money out") || h.equals("dr") || h.equals("out"))) {
                    de = c;
                } else if (cr < 0 && (h.contains("credit") || h.contains("deposit") || h.contains("money in") || h.equals("cr") || h.equals("in"))) {
                    cr = c;
                } else if (ba < 0 && h.contains("balance")) {
                    ba = c;
                } else if (re < 0 && (h.contains("reference") || h.equals("ref") || h.contains("transaction id") || h.contains("txn id") || h.equals("id"))) {
                    re = c;
                } else if (ty < 0 && (h.equals("type") || h.contains("dr/cr") || h.contains("direction"))) {
                    ty = c;
                } else if (am < 0 && h.contains("amount")) {
                    am = c;
                }
            }
            if (d >= 0 && (na >= 0 || re >= 0) && ((de >= 0 && cr >= 0) || am >= 0)) {
                header = i;
                date = d; valueDate = vd; narration = na; debit = de; credit = cr; amount = am; balance = ba; ref = re; type = ty;
            }
        }
        if (header < 0) {
            throw new IllegalArgumentException("The statement needs a header row with a date, a description and the amounts"
                    + " (debit and credit columns, or one amount column)");
        }
        List<Line> lines = new ArrayList<>();
        BigDecimal opening = null;
        BigDecimal closing = null;
        int n = 0;
        for (int i = header + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            LocalDate d = date(cell(r, date));
            if (d == null) {
                continue;
            }
            BigDecimal de = debit >= 0 ? amountOrNull(cell(r, debit)) : null;
            BigDecimal cr = credit >= 0 ? amountOrNull(cell(r, credit)) : null;
            BigDecimal am = amount >= 0 ? amountOrNull(cell(r, amount)) : null;
            boolean in;
            BigDecimal value;
            if (cr != null && cr.signum() != 0) {
                in = true;
                value = cr.abs();
            } else if (de != null && de.signum() != 0) {
                in = false;
                value = de.abs();
            } else if (am != null && am.signum() != 0) {
                String t = type >= 0 ? cell(r, type).toUpperCase(Locale.ROOT) : "";
                in = t.isEmpty() ? am.signum() > 0 : (t.startsWith("C") || t.contains("IN") || t.contains("DEPOSIT") || t.contains("RECEIVED"));
                value = am.abs();
            } else {
                continue;
            }
            BigDecimal bal = balance >= 0 ? amountOrNull(cell(r, balance)) : null;
            String narr = narration >= 0 ? cell(r, narration) : "";
            String reference = ref >= 0 && !cell(r, ref).isBlank() ? cell(r, ref).trim() : reference(narr);
            if (narr.isBlank()) {
                narr = reference == null ? "" : reference;
            }
            if (opening == null && bal != null) {
                opening = in ? bal.subtract(value) : bal.add(value);
            }
            if (bal != null) {
                closing = bal;
            }
            lines.add(new Line(++n, d, valueDate >= 0 ? date(cell(r, valueDate)) : null, cut(narr, 1000), cut(reference, 128), in,
                    value.setScale(4, RoundingMode.HALF_UP), bal));
        }
        if (lines.isEmpty()) {
            warnings.add("No statement lines were found in this file");
        }
        return new Parsed(lines, opening, closing, null, warnings);
    }

    private static List<List<String>> excelRows(byte[] bytes) {
        List<List<String>> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter(Locale.ROOT);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) {
                        cells.add("");
                    } else if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        cells.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
                    } else if (cell.getCellType() == CellType.NUMERIC
                            || (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC)) {
                        cells.add(BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString());
                    } else {
                        cells.add(fmt.formatCellValue(cell).trim());
                    }
                }
                out.add(cells);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("The Excel file could not be read");
        }
        return out;
    }

    static List<List<String>> csvRows(String text) {
        List<List<String>> out = new ArrayList<>();
        String sep = text.lines().limit(10).anyMatch(l -> l.chars().filter(ch -> ch == ';').count() > l.chars().filter(ch -> ch == ',').count())
                ? ";" : ",";
        for (String line : text.split("\\R")) {
            List<String> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (!quoted && String.valueOf(ch).equals(sep)) {
                    cells.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
            cells.add(cur.toString().trim());
            out.add(cells);
        }
        return out;
    }

    // ------------------------------------------------------------------ values

    static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > 10 && v.charAt(4) == '-' && v.charAt(10) == 'T') {
            v = v.substring(0, 10);
        } else if (v.length() > 10 && v.charAt(10) == ' ') {
            v = v.substring(0, 10);
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                LocalDate d = LocalDate.parse(v, f);
                return d.getYear() < 100 ? d.plusYears(2000) : d;
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return null;
    }

    static BigDecimal amount(String raw) {
        String s = raw.trim().toUpperCase(Locale.ROOT);
        boolean negative = s.startsWith("-") || s.startsWith("(") || s.endsWith("DR");
        s = s.replaceAll("[^0-9.]", "");
        BigDecimal v = new BigDecimal(s);
        return negative ? v.negate() : v;
    }

    private static BigDecimal amountOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replace(",", "").replace(" ", "").replace("RWF", "").replace("Frw", "");
        try {
            return amount(s.isEmpty() ? "0" : s.replace("CR", "").replace("Cr", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The longest code in the narration that has digits (a transaction id, a phone number, a cheque number). */
    static String reference(String narration) {
        if (narration == null) {
            return null;
        }
        Matcher m = REFERENCE.matcher(narration.toUpperCase(Locale.ROOT));
        String best = null;
        while (m.find()) {
            if (best == null || m.group().length() > best.length()) {
                best = m.group();
            }
        }
        return best == null ? null : cut(best, 128);
    }

    private static String cell(List<String> r, int i) {
        return i >= 0 && i < r.size() && r.get(i) != null ? r.get(i) : "";
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static final class Row0 {
        LocalDate date;
        LocalDate valueDate;
        String narration;
        BigDecimal amount;
        BigDecimal balance;
        BigDecimal signed;
        Boolean in;
    }
}
