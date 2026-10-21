package rw.terimbere.csams.modules.memberpayment.receipt;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A stand-in OCR engine for tests: it "reads" whatever text the test scripts, so the extraction, matching and
 * verification workflow can be exercised without Tesseract. It is available only while a script is set; without one the
 * real Tesseract engine (absent on most machines) decides, exactly like production.
 *
 * <p>This proves the workflow, not recognition quality: no real image is ever recognised by it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScriptedOcrEngine implements ReceiptOcrEngine {

    private static volatile OcrResult script;
    private static volatile OcrException failure;

    public static void script(String text, double confidence) {
        failure = null;
        script = new OcrResult(text, confidence);
    }

    public static void fail(String code) {
        script = null;
        failure = new OcrException(code, "scripted failure");
    }

    public static void clear() {
        script = null;
        failure = null;
    }

    @Override
    public String name() {
        return "scripted-test-engine";
    }

    @Override
    public boolean available() {
        return script != null || failure != null;
    }

    @Override
    public OcrResult read(byte[] image, String extension) throws OcrException {
        if (failure != null) {
            throw failure;
        }
        return script;
    }
}
