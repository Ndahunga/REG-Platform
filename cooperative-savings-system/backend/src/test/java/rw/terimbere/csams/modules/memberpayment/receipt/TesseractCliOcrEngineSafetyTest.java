package rw.terimbere.csams.modules.memberpayment.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptOcrEngine.OcrException;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptOcrEngine.OcrResult;

/**
 * The OCR subprocess is bounded: a hanging engine is killed at the time limit (even if it never closes its output), a
 * flooding one is cut off, a failing one is reported, and nothing is run through a shell. A stand-in process (this JVM
 * started with a tiny main) plays the engine, so no Tesseract is needed.
 */
class TesseractCliOcrEngineSafetyTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    /** The stand-in "engine". */
    public static final class FakeEngineMain {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "hang" -> Thread.sleep(120_000);
                case "flood" -> {
                    byte[] chunk = new byte[64 * 1024];
                    java.util.Arrays.fill(chunk, (byte) 'x');
                    for (int i = 0; i < 64; i++) {
                        System.out.write(chunk);
                    }
                    System.out.flush();
                    Thread.sleep(120_000);
                }
                case "fail" -> System.exit(3);
                default -> {
                    System.out.println("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext");
                    System.out.println("5\t1\t1\t1\t1\t1\t0\t0\t1\t1\t90\tAmount:");
                    System.out.println("5\t1\t1\t1\t1\t2\t0\t0\t1\t1\t80\t500");
                }
            }
        }
    }

    private static TesseractCliOcrEngine engine(String mode, long timeoutSeconds) {
        return new TesseractCliOcrEngine("unused", "eng", timeoutSeconds) {
            @Override
            protected List<String> command(Path file) {
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                return List.of(java, "-cp", System.getProperty("java.class.path"), FakeEngineMain.class.getName(), mode);
            }
        };
    }

    @Test
    void aSuccessfulRunIsParsedAndItsTemporaryFileIsRemoved() throws Exception {
        OcrResult result = engine("ok", 20).read(PNG, "png");
        assertThat(result.text()).isEqualTo("Amount: 500");
        assertThat(result.meanConfidence()).isCloseTo(0.85, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void anEngineThatHangsWithoutClosingItsOutputIsKilledAtTheTimeLimit() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> engine("hang", 2).read(PNG, "png"))
                .isInstanceOfSatisfying(OcrException.class, e -> assertThat(e.getCode()).isEqualTo("OCR_TIMEOUT"));
        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("returned near the limit, not after the 120 s sleep").isLessThan(30);
    }

    @Test
    void anEngineThatFloodsItsOutputIsCutOffAndStopped() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> engine("flood", 30).read(PNG, "png"))
                .isInstanceOfSatisfying(OcrException.class, e -> assertThat(e.getCode()).isEqualTo("OCR_OUTPUT_TOO_LARGE"));
        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("did not wait for the engine to finish").isLessThan(25);
    }

    @Test
    void aFailingEngineIsReportedAndAnUnsupportedFormatIsNeverStarted() {
        assertThatThrownBy(() -> engine("fail", 20).read(PNG, "png"))
                .isInstanceOfSatisfying(OcrException.class, e -> assertThat(e.getCode()).isEqualTo("OCR_FAILED"));
        assertThatThrownBy(() -> engine("ok", 20).read(PNG, "pdf"))
                .isInstanceOfSatisfying(OcrException.class, e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_FORMAT"));
    }
}
