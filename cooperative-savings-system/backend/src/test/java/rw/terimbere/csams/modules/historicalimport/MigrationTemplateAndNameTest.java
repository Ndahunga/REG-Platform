package rw.terimbere.csams.modules.historicalimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.user.entity.User;

class MigrationTemplateAndNameTest {

    private static byte[] template() throws Exception {
        return template("write");
    }

    private static byte[] template(String method) throws Exception {
        Class<?> writer = Class.forName("rw.terimbere.csams.modules.historicalimport.service.MigrationTemplateWriter");
        Method write = writer.getDeclaredMethod(method);
        write.setAccessible(true);
        return (byte[]) write.invoke(null);
    }

    @Test
    void template_hasExactlyTwoSheetsWithExactNamesAndHeadersAndNoDataRows() throws Exception {
        byte[] bytes = template();
        assertThat(bytes[0]).isEqualTo((byte) 0x50);
        assertThat(bytes[1]).isEqualTo((byte) 0x4B);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                names.add(workbook.getSheetName(i));
            }
            assertThat(names).containsExactly("Member Registration", "Share Capital Registration");
            assertThat(workbook.getSheet("Loan Registration")).as("loans are imported separately").isNull();
            assertThat(workbook.getSheet("Instructions")).isNull();
            assertThat(workbook.getSheet("Contributions")).isNull();

            assertThat(headers(workbook.getSheet("Member Registration")))
                    .containsExactly(
                            "Full Name",
                            "Username",
                            "Email",
                            "Phone Number",
                            "National ID (Optional)",
                            "Total Contributions to Date (RWF)");
            assertThat(headers(workbook.getSheet("Share Capital Registration")))
                    .containsExactly("Username", "Share Count", "Share Capital Contributed to Date (RWF)");
            for (int i = 0; i < 2; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                // header only: nothing that could be imported by accident
                assertThat(sheet.getLastRowNum()).isZero();
                assertThat(sheet.getPaneInformation()).isNotNull();
                assertThat(((org.apache.poi.xssf.usermodel.XSSFCellStyle) sheet.getRow(0).getCell(0).getCellStyle()).getFont().getBold()).isTrue();
            }
        }
    }

    @Test
    void loanTemplate_isASeparateOneSheetWorkbookWithTheLoanHeadersAndNoDataRows() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template("writeLoans")))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("Loan Registration");
            assertThat(headers(workbook.getSheetAt(0)))
                    .containsExactly(
                            "Username",
                            "Repayment Type",
                            "Loan Amount (RWF)",
                            "Interest Rate (%)",
                            "Loan Issued Date",
                            "Loan Term (Months)",
                            "Amount Paid to Date (RWF)",
                            "Outstanding Principal (RWF)",
                            "Last Payment Date");
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    private static List<String> headers(Sheet sheet) {
        List<String> headers = new ArrayList<>();
        Row row = sheet.getRow(0);
        for (int c = 0; c < row.getLastCellNum(); c++) {
            headers.add(row.getCell(c).getStringCellValue());
        }
        return headers;
    }

    // ------------------------------------------------------------------ name split

    private static String[] split(String fullName) throws Exception {
        Class<?> splitter = Class.forName("rw.terimbere.csams.modules.historicalimport.service.MemberNameSplitter");
        Method split = splitter.getDeclaredMethod("split", String.class);
        split.setAccessible(true);
        Object name = split.invoke(null, fullName);
        Method first = name.getClass().getDeclaredMethod("firstName");
        Method last = name.getClass().getDeclaredMethod("lastName");
        first.setAccessible(true);
        last.setAccessible(true);
        return new String[] {(String) first.invoke(name), (String) last.invoke(name)};
    }

    @Test
    void multiWordName_firstWordIsFirstNameRestIsLastName() throws Exception {
        assertThat(split("Jean Claude Uwimana")).containsExactly("Jean", "Claude Uwimana");
        assertThat(split("Alice Mukamana")).containsExactly("Alice", "Mukamana");
    }

    @Test
    void repeatedWhitespaceIsCollapsedAndTrimmed() throws Exception {
        assertThat(split("  Jean   Claude \t Uwimana  ")).containsExactly("Jean", "Claude Uwimana");
    }

    @Test
    void oneWordName_keepsEmptyLastName_withoutFakeSurname() throws Exception {
        String[] name = split("Mugisha");
        assertThat(name).containsExactly("Mugisha", "");
        User user = User.builder().firstName(name[0]).lastName(name[1]).build();
        assertThat(user.getFullName()).isEqualTo("Mugisha");
    }
}
