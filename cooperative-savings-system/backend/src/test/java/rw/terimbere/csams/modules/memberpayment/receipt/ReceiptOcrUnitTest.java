package rw.terimbere.csams.modules.memberpayment.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptOcrEngine.OcrException;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptTextParser.Parsed;
import rw.terimbere.csams.modules.memberpayment.service.PaymentReferenceNormalizer;

/** Parsing, engine availability and discrepancy rules. No image is recognised here: text in, structured fields out. */
class ReceiptOcrUnitTest {

    private static final String MOMO_RECEIPT = """
            MTN MoMo
            Transaction successful
            Financial Transaction Id: 4418293847
            Amount: RWF 15,000
            To: 0788123456
            From: 0781234567
            Date: 2026-03-05 14:32:10
            """;

    @Test
    void parsesTheFieldsOfAMobileMoneyReceiptWithConfidenceScaledByTheEngineReading() {
        Parsed parsed = ReceiptTextParser.parse(MOMO_RECEIPT, 0.95);

        assertThat(parsed.reference().value()).isEqualTo("4418293847");
        assertThat(parsed.amount().value()).isEqualByComparingTo("15000");
        assertThat(parsed.currency().value()).isEqualTo("RWF");
        assertThat(parsed.status().value()).isEqualTo(ReceiptStatus.SUCCESS_INDICATED);
        assertThat(parsed.provider().value()).isEqualTo(PaymentMethod.MTN_MOMO);
        assertThat(parsed.recipient().value()).endsWith("788123456");
        assertThat(parsed.paidAt().value().toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(parsed.amount().confidence()).isBetween(0.5, 1.0);

        Parsed blurry = ReceiptTextParser.parse(MOMO_RECEIPT, 0.3);
        assertThat(blurry.amount().confidence()).as("a poor reading lowers every field").isLessThan(parsed.amount().confidence());
    }

    @Test
    void contradictoryOrMissingFieldsAreLeftUnknownNeverGuessed() {
        Parsed contradictory = ReceiptTextParser.parse("Payment successful\nTransaction failed\nAmount: 500", 0.9);
        assertThat(contradictory.status() == null || contradictory.status().value() == ReceiptStatus.UNKNOWN).isTrue();

        Parsed empty = ReceiptTextParser.parse("", 0.9);
        assertThat(empty.amount()).isNull();
        assertThat(empty.reference()).isNull();
        assertThat(ReceiptTextParser.parse(null, 0.9).amount()).isNull();
    }

    @Test
    void tesseractTsvBecomesLinesAndAMeanConfidenceOnAZeroToOneScale() {
        String header = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";
        String tsv = header
                + "\n5\t1\t1\t1\t1\t1\t0\t0\t1\t1\t90\tAmount:"
                + "\n5\t1\t1\t1\t1\t2\t0\t0\t1\t1\t70\t500"
                + "\n5\t1\t1\t1\t2\t1\t0\t0\t1\t1\t80\tDone"
                + "\n4\t1\t1\t1\t2\t0\t0\t0\t1\t1\t-1\t";

        ReceiptOcrEngine.OcrResult result = TesseractCliOcrEngine.parseTsv(tsv);

        assertThat(result.text()).isEqualTo("Amount: 500\nDone");
        assertThat(result.meanConfidence()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(TesseractCliOcrEngine.parseTsv("garbage").meanConfidence()).isZero();
    }

    @Test
    void anEngineWhoseBinaryIsMissingReportsUnavailableAndRefusesToRead() {
        TesseractCliOcrEngine engine = new TesseractCliOcrEngine("definitely-not-installed-ocr-binary", "eng", 5);

        assertThat(engine.available()).isFalse();
        assertThatThrownBy(() -> engine.read(new byte[] {1, 2, 3}, "png")).isInstanceOf(OcrException.class);
    }

    @Test
    void theRealFirstBytesDecideTheFileTypeNotTheNameOrTheDeclaredType() {
        assertThat(ReceiptExtractionService.detectType(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})).isEqualTo("png");
        assertThat(ReceiptExtractionService.detectType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1})).isEqualTo("jpg");
        assertThat(ReceiptExtractionService.detectType("%PDF-1.7".getBytes())).isNull();
        assertThat(ReceiptExtractionService.detectType("<script>alert(1)</script>".getBytes())).isNull();
        assertThat(ReceiptExtractionService.detectType(new byte[0])).isNull();
    }

    @Test
    void everyDifferenceBetweenTheReceiptAndTheClaimIsReportedAndOnlyEverAddsDoubt() {
        MemberPayment payment = MemberPayment.builder()
                .amount(new BigDecimal("15000"))
                .currency("RWF")
                .paymentMethod(PaymentMethod.MTN_MOMO)
                .paymentDate(LocalDate.of(2026, 3, 5))
                .normalizedReference(PaymentReferenceNormalizer.normalize("4418293847"))
                .build();

        PaymentReceiptExtraction matching = extraction("4418293847", "15000", "RWF", "MTN_MOMO", "SUCCESS_INDICATED", "0.9000");
        assertThat(ReceiptExtractionService.discrepancies(matching, payment)).isEmpty();

        PaymentReceiptExtraction forged = extraction("9999999999", "1500", "USD", "AIRTEL_MONEY", "FAILED_INDICATED", "0.2000");
        forged.setDuplicateOfPaymentId(java.util.UUID.randomUUID());
        List<String> found = ReceiptExtractionService.discrepancies(forged, payment);
        assertThat(found).contains(
                "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "REFERENCE_MISMATCH", "PROVIDER_MISMATCH",
                "RECEIPT_NOT_SUCCESSFUL", "DUPLICATE_RECEIPT", "LOW_CONFIDENCE");

        PaymentReceiptExtraction unavailable = extraction("9999999999", "1", "USD", "MTN_MOMO", "SUCCESS_INDICATED", "0.9000");
        unavailable.setStatus(ReceiptExtractionService.UNAVAILABLE);
        assertThat(ReceiptExtractionService.discrepancies(unavailable, payment)).as("nothing was read").isEmpty();
    }

    private static PaymentReceiptExtraction extraction(
            String reference, String amount, String currency, String provider, String status, String confidence) {
        return PaymentReceiptExtraction.builder()
                .status(ReceiptExtractionService.COMPLETED)
                .extractedReference(reference)
                .extractedAmount(new BigDecimal(amount))
                .extractedCurrency(currency)
                .extractedProvider(provider)
                .extractedStatus(status)
                .meanConfidence(new BigDecimal(confidence))
                .build();
    }
}
