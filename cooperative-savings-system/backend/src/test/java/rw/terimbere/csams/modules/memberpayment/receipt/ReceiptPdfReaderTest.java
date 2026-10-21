package rw.terimbere.csams.modules.memberpayment.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import rw.terimbere.csams.testsupport.TestPdfs;

/** PDF receipts: the text layer is read, a picture-only PDF yields its image, and every hostile or broken file is a code. */
class ReceiptPdfReaderTest {

    private final ReceiptPdfReader reader = readerWithTimeout(20);

    private static ReceiptPdfReader readerWithTimeout(long seconds) {
        ReceiptPdfReader r = new ReceiptPdfReader();
        ReflectionTestUtils.setField(r, "timeoutSeconds", seconds);
        return r;
    }

    @Test
    void aPdfWithATextLayerIsReadDirectly() throws Exception {
        byte[] pdf = TestPdfs.withText("MTN MoMo\nTransaction successful\nFinancial Transaction Id: 4418293847\nAmount: RWF 80,000");
        ReceiptPdfReader.Content content = reader.read(pdf);
        assertThat(content.failureCode()).isNull();
        assertThat(content.hasText()).isTrue();
        assertThat(content.text()).contains("Transaction successful").contains("4418293847").contains("80,000");
        assertThat(content.jpeg()).isNull();
    }

    @Test
    void aPictureOnlyPdfYieldsItsEmbeddedJpegForOcr() throws Exception {
        ReceiptPdfReader.Content content = reader.read(TestPdfs.imageOnly());
        assertThat(content.failureCode()).isNull();
        assertThat(content.hasText()).isFalse();
        assertThat(content.jpeg()).isNotNull();
        assertThat(content.jpeg()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(content.jpeg()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void aPdfWithNothingReadableIsReportedNotGuessed() throws Exception {
        assertThat(reader.read(TestPdfs.blank()).failureCode()).isEqualTo("PDF_NO_TEXT");
    }

    @Test
    void brokenRandomAndTruncatedFilesAreUnreadableNotExceptions() throws Exception {
        assertThat(reader.read("%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8)).failureCode()).isEqualTo("PDF_UNREADABLE");
        byte[] garbage = new byte[4096];
        new Random(7).nextBytes(garbage);
        System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.UTF_8), 0, garbage, 0, 9);
        assertThat(reader.read(garbage).failureCode()).isEqualTo("PDF_UNREADABLE");
        byte[] valid = TestPdfs.withText("Amount: RWF 1,000 Transaction successful reference ABC12345");
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length / 2);
        assertThat(reader.read(truncated).failureCode()).isIn("PDF_UNREADABLE", "PDF_NO_TEXT");
    }

    @Test
    void aPdfThatNeedsAPasswordIsRefusedNeverRead() {
        ReceiptPdfReader.Content content = reader.read(TestPdfs.encrypted());
        assertThat(content.failureCode()).isIn("PDF_ENCRYPTED", "PDF_UNREADABLE");
        assertThat(content.hasText()).isFalse();
    }

    @Test
    void aDocumentWithManyPagesIsNotAReceiptAndIsRefused() throws Exception {
        String[] pages = new String[ReceiptPdfReader.MAX_PAGES_ALLOWED + 2];
        java.util.Arrays.fill(pages, "Amount: RWF 1,000 page of a long document");
        assertThat(reader.read(TestPdfs.withText(pages)).failureCode()).isEqualTo("PDF_TOO_MANY_PAGES");
    }

    @Test
    void onlyTheFirstPagesAreReadAndTheTextIsCapped() throws Exception {
        String filler = "x".repeat(300);
        String[] pages = new String[ReceiptPdfReader.MAX_PAGES_ALLOWED];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = "page" + (i + 1) + " " + filler;
        }
        ReceiptPdfReader.Content content = reader.read(TestPdfs.withText(pages));
        assertThat(content.text()).contains("page1").contains("page3").doesNotContain("page4");
        assertThat(content.text().length()).isLessThanOrEqualTo(ReceiptPdfReader.MAX_TEXT_CHARS);
    }

    @Test
    void theReadingRoutesAreIndependentOfTheImageEngineSoATextPdfNeedsNoOcr() throws Exception {
        ReceiptReader receiptReader = new ReceiptReader(java.util.List.of(), ReceiptStatusClassifier.withDefaults(), reader);
        ReceiptReader.Outcome outcome = receiptReader.read(
                TestPdfs.withText("MTN MoMo\nTransaction successful\nFinancial Transaction Id: 4418293847\nAmount: RWF 80,000"),
                "application/pdf");
        assertThat(outcome.status()).isEqualTo(ReceiptReader.COMPLETED);
        assertThat(outcome.engine()).isEqualTo("pdf-text");
        assertThat(outcome.parsed().amount().value()).isEqualByComparingTo("80000");
        assertThat(outcome.parsed().status().value().name()).isEqualTo("SUCCESS_INDICATED");

        // a picture-only PDF needs an OCR engine: without one it is reported unavailable, never invented
        assertThat(receiptReader.read(TestPdfs.imageOnly(), "application/pdf").status()).isEqualTo(ReceiptReader.UNAVAILABLE);
        // a PDF that claims to be an image, and an image that claims to be a PDF, are both refused
        assertThat(receiptReader.read(TestPdfs.blank(), "image/png").failureCode()).isEqualTo("CONTENT_TYPE_MISMATCH");
        assertThat(receiptReader.read(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4}, "application/pdf").failureCode())
                .isEqualTo("CONTENT_TYPE_MISMATCH");
    }
}
