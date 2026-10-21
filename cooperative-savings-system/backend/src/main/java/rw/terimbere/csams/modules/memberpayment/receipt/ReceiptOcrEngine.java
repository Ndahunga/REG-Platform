package rw.terimbere.csams.modules.memberpayment.receipt;

/**
 * The seam between the receipt workflow and whatever reads text from an image. Implementations are optional
 * infrastructure: when none is available the workflow records that honestly (status UNAVAILABLE) instead of inventing
 * a result. What an engine returns is text and how sure it is; it is never evidence that money was received.
 */
public interface ReceiptOcrEngine {

    /** A short stable name stored with each extraction ("tesseract-cli"). */
    String name();

    /** True when the engine can run in this environment right now. Must be cheap and must never throw. */
    boolean available();

    /**
     * Reads the text of an image. {@code extension} is derived from the verified content (png or jpg), never from the
     * uploaded file name. Throws {@link OcrException} with a stable code on any failure.
     */
    OcrResult read(byte[] image, String extension) throws OcrException;

    /** The recognised text and the engine's mean confidence in it, from 0 to 1. */
    record OcrResult(String text, double meanConfidence) {}

    class OcrException extends Exception {
        private final String code;

        public OcrException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
