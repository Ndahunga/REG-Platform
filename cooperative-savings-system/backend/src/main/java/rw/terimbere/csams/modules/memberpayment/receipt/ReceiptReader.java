package rw.terimbere.csams.modules.memberpayment.receipt;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptOcrEngine.OcrException;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptOcrEngine.OcrResult;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptTextParser.Parsed;

/**
 * Turns the bytes of an uploaded receipt into what the receipt says: verifies what the content really is, hands images to
 * the OCR engine, and parses the text. It is the one place that reads receipts, used both before a payment is submitted
 * (the member's own analysis) and after (the officer's extraction), so both always agree.
 *
 * <p>It never throws for a bad or unreadable receipt: every outcome is a status (COMPLETED, FAILED, UNAVAILABLE,
 * UNSUPPORTED) with a stable code. What it returns is a claim made by an image, never evidence that money was received.
 */
@Component
@RequiredArgsConstructor
public class ReceiptReader {

    private static final Logger log = LoggerFactory.getLogger(ReceiptReader.class);

    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /** What reading a receipt produced; {@code ocr} and {@code parsed} are present only when COMPLETED. */
    public record Outcome(String status, String failureCode, String engine, String contentType, OcrResult ocr, Parsed parsed) {}

    private final List<ReceiptOcrEngine> engines;
    private final ReceiptStatusClassifier statusClassifier;
    private final ReceiptPdfReader pdfReader;

    public Outcome read(byte[] content, String declaredType) {
        if (isPdf(content)) {
            return readPdf(content, declaredType);
        }
        String detected = detectType(content);
        if (detected == null) {
            // anything else is recorded, never handed to an engine
            return new Outcome(UNSUPPORTED, "UNSUPPORTED_FORMAT", null, null, null, null);
        }
        if (declaredType != null
                && ((declaredType.toLowerCase().startsWith("image/")
                                && !declaredType.toLowerCase().contains(detected.equals("png") ? "png" : "jp"))
                        || declaredType.toLowerCase().contains("pdf"))) {
            return new Outcome(FAILED, "CONTENT_TYPE_MISMATCH", null, detected, null, null);
        }
        ReceiptOcrEngine engine = availableEngine();
        if (engine == null) {
            return new Outcome(UNAVAILABLE, "OCR_ENGINE_UNAVAILABLE", null, detected, null, null);
        }
        return recognise(engine, content, detected.equals("png") ? "png" : "jpg", detected);
    }

    /** A PDF with a text layer is read directly; one that is only a picture goes through OCR like any image. */
    private Outcome readPdf(byte[] content, String declaredType) {
        if (declaredType != null && declaredType.toLowerCase().startsWith("image/")) {
            return new Outcome(FAILED, "CONTENT_TYPE_MISMATCH", null, "pdf", null, null);
        }
        ReceiptPdfReader.Content pdf = pdfReader.read(content);
        if (pdf.failureCode() != null) {
            return new Outcome(FAILED, pdf.failureCode(), null, "pdf", null, null);
        }
        if (pdf.hasText()) {
            // the text of a PDF is exact, not recognised: full confidence in the reading itself
            OcrResult text = new OcrResult(pdf.text(), 1.0);
            return new Outcome(COMPLETED, null, "pdf-text", "pdf", text, ReceiptTextParser.parse(pdf.text(), 1.0, statusClassifier));
        }
        ReceiptOcrEngine engine = availableEngine();
        if (engine == null) {
            return new Outcome(UNAVAILABLE, "OCR_ENGINE_UNAVAILABLE", null, "pdf", null, null);
        }
        return recognise(engine, pdf.jpeg(), "jpg", "pdf");
    }

    private Outcome recognise(ReceiptOcrEngine engine, byte[] image, String extension, String contentType) {
        try {
            OcrResult ocr = engine.read(image, extension);
            if (ocr == null || !StringUtils.hasText(ocr.text())) {
                return new Outcome(FAILED, "NO_TEXT_FOUND", engine.name(), contentType, null, null);
            }
            return new Outcome(
                    COMPLETED, null, engine.name(), contentType, ocr,
                    ReceiptTextParser.parse(ocr.text(), ocr.meanConfidence(), statusClassifier));
        } catch (OcrException e) {
            return new Outcome(FAILED, e.getCode(), engine.name(), contentType, null, null);
        } catch (RuntimeException e) {
            log.warn("Receipt OCR failed unexpectedly: {}", e.getClass().getSimpleName());
            return new Outcome(FAILED, "OCR_FAILED", engine.name(), contentType, null, null);
        }
    }

    private ReceiptOcrEngine availableEngine() {
        return engines.stream().filter(ReceiptOcrEngine::available).findFirst().orElse(null);
    }

    public static boolean isPdf(byte[] content) {
        return content.length > 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
    }

    /** "png" or "jpg" from the real first bytes, or null for anything else. */
    public static String detectType(byte[] content) {
        if (content.length >= 4
                && (content[0] & 0xFF) == 0x89
                && content[1] == 'P'
                && content[2] == 'N'
                && content[3] == 'G') {
            return "png";
        }
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        return null;
    }
}
