package rw.terimbere.csams.modules.historicalimport;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet;

/** Test builder for opening-balance migration workbooks (all cells written as text, as a user might). */
final class MigrationWorkbooks {

    private final List<List<String>> members = new ArrayList<>();
    private final List<List<String>> shares = new ArrayList<>();
    private final List<List<String>> loans = new ArrayList<>();

    static MigrationWorkbooks create() {
        return new MigrationWorkbooks();
    }

    MigrationWorkbooks member(
            String fullName, String username, String email, String phone, String nationalId, String contributions) {
        members.add(List.of(fullName, username, email, phone, nationalId, contributions));
        return this;
    }

    MigrationWorkbooks member(String fullName, String username, String contributions) {
        return member(fullName, username, username + "@test.local", "0781234567", "", contributions);
    }

    MigrationWorkbooks shares(String username, String shareCount, String capital) {
        shares.add(List.of(username, shareCount, capital));
        return this;
    }

    MigrationWorkbooks loan(
            String username,
            String type,
            String amount,
            String rate,
            String issued,
            String term,
            String paid,
            String outstanding,
            String lastPayment) {
        loans.add(List.of(username, type, amount, rate, issued, term, paid, outstanding, lastPayment));
        return this;
    }

    byte[] bytes() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // The opening workbook is two sheets; loans travel in their own one-sheet workbook. A builder that holds
            // members or shares writes the opening workbook (plus a loan sheet only if loans were added, so tests can
            // check that it is refused); one that holds only loans writes the loan workbook.
            boolean loansOnly = members.isEmpty() && shares.isEmpty() && !loans.isEmpty();
            if (!loansOnly) {
                write(workbook, HistoricalMigrationSheet.MEMBER_REGISTRATION, members);
                write(workbook, HistoricalMigrationSheet.SHARE_CAPITAL_REGISTRATION, shares);
            }
            if (loansOnly || !loans.isEmpty()) {
                write(workbook, HistoricalMigrationSheet.LOAN_REGISTRATION, loans);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    MockMultipartFile file(String name) throws Exception {
        return file(name, bytes());
    }

    static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile(
                "file", name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    /** A workbook in the retired 16-sheet format (Instructions + Members ...). */
    static byte[] legacyWorkbook() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Instructions").createRow(0).createCell(0).setCellValue("old");
            Sheet members = workbook.createSheet("Members");
            members.createRow(0).createCell(0).setCellValue("Username");
            workbook.createSheet("Contributions").createRow(0).createCell(0).setCellValue("Username");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void write(Workbook workbook, HistoricalMigrationSheet type, List<List<String>> rows) {
        Sheet sheet = workbook.createSheet(type.getSheetName());
        Row header = sheet.createRow(0);
        for (int i = 0; i < type.getHeaders().size(); i++) {
            header.createCell(i).setCellValue(type.getHeaders().get(i));
        }
        int r = 1;
        for (List<String> values : rows) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < values.size(); c++) {
                if (!values.get(c).isEmpty()) {
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
        }
    }
}
