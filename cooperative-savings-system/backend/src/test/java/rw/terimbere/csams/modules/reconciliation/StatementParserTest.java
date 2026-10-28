package rw.terimbere.csams.modules.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class StatementParserTest {

    @Test
    void readsACsvWithDebitAndCreditColumns() throws Exception {
        String csv = """
                Date,Narration,Debit,Credit,Balance
                02/09/2026,MOMO deposit TX123456 KAMANZI JEAN,,2000,12000
                03/09/2026,Monthly ledger fee,500,,11500
                """;
        StatementParser.Parsed parsed = StatementParser.parse("s.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.lines()).hasSize(2);
        StatementParser.Line in = parsed.lines().get(0);
        assertThat(in.in()).isTrue();
        assertThat(in.amount()).isEqualByComparingTo("2000");
        assertThat(in.date()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(in.reference()).isEqualTo("TX123456");
        assertThat(parsed.lines().get(1).in()).isFalse();
        assertThat(parsed.closingBalance()).isEqualByComparingTo("11500");
    }

    @Test
    void readsAPdfStatementAndTellsInFromOutByTheBalance() throws Exception {
        byte[] pdf = pdf(List.of(
                "Opening Balance 10,000.00",
                "02-09-2026 02-09-2026 Deposit TX99887766 MUKAMANA 3,000.00 13,000.00",
                "05-09-2026 05-09-2026 Loan disbursement 1,000.00 12,000.00",
                "06-09-2026 06-09-2026 Ledger fee 200.00 11,800.00"));
        StatementParser.Parsed parsed = StatementParser.parse("s.pdf", pdf);
        assertThat(parsed.lines()).extracting(StatementParser.Line::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("3000"), new BigDecimal("1000"), new BigDecimal("200"));
        assertThat(parsed.lines()).extracting(StatementParser.Line::in).containsExactly(true, false, false);
        assertThat(parsed.closingBalance()).isEqualByComparingTo("11800");
    }

    @Test
    void matcherMatchesOnReferenceAndSuggestsOnPhone() {
        var candidate = new StatementMatcher.Candidate("PAYMENT", java.util.UUID.randomUUID(), null, "Jean Kamanzi", "0788123456",
                new BigDecimal("2000"), LocalDate.of(2026, 9, 2), "TX123456", "x");
        assertThat(StatementMatcher.decide("MOMO TX123456", null, new BigDecimal("2000"), LocalDate.of(2026, 9, 2), List.of(candidate)).status())
                .isEqualTo("MATCHED");
        assertThat(StatementMatcher.decide("From 250788123456", null, new BigDecimal("2000"), LocalDate.of(2026, 9, 3), List.of(candidate)).status())
                .isEqualTo("SUGGESTED");
        assertThat(StatementMatcher.decide("From 250788123456", null, new BigDecimal("2500"), LocalDate.of(2026, 9, 3), List.of(candidate)).status())
                .isEqualTo("UNMATCHED");
        assertThat(StatementMatcher.looksLikeBankCharge("Monthly ledger fee")).isTrue();
    }

    private static byte[] pdf(List<String> rows) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
                float y = 740;
                for (String row : rows) {
                    cs.beginText();
                    cs.newLineAtOffset(30, y);
                    cs.showText(row);
                    cs.endText();
                    y -= 16;
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
