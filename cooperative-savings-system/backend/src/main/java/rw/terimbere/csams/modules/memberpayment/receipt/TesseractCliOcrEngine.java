package rw.terimbere.csams.modules.memberpayment.receipt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OCR through the Tesseract command-line program, when it is installed. This is the only engine bundled: it needs no
 * credentials and no network, but it does need the {@code tesseract} executable (and its language data) on the host.
 * Where it is missing {@link #available()} is false and the workflow records UNAVAILABLE.
 *
 * <p>Safety: the program is started directly (never through a shell) with a fixed argument list, on a temporary file
 * whose extension comes from the verified content; the file is deleted afterwards; the run has a hard timeout and the
 * output is size-capped, so a hostile or pathological image cannot hang or flood the server.
 */
@Component
@Order(100)
public class TesseractCliOcrEngine implements ReceiptOcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractCliOcrEngine.class);
    private static final int MAX_OUTPUT_BYTES = 512 * 1024;
    private static final long AVAILABILITY_TTL_MILLIS = 60_000;

    private final String executable;
    private final String language;
    private final long timeoutSeconds;

    private volatile Boolean available;
    private volatile long checkedAt;

    public TesseractCliOcrEngine(
            @Value("${app.ocr.tesseract.path:tesseract}") String executable,
            @Value("${app.ocr.tesseract.language:eng}") String language,
            @Value("${app.ocr.timeout-seconds:30}") long timeoutSeconds) {
        this.executable = executable;
        this.language = language;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "tesseract-cli";
    }

    @Override
    public boolean available() {
        long now = System.currentTimeMillis();
        Boolean cached = available;
        if (cached != null && now - checkedAt < AVAILABILITY_TTL_MILLIS) {
            return cached;
        }
        boolean result = probe();
        available = result;
        checkedAt = now;
        return result;
    }

    /** Asks the binary for its version: a missing, broken or hanging installation is simply "not available". */
    private boolean probe() {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "--version").redirectErrorStream(true).start();
            process.getInputStream().readNBytes(4096);
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Override
    public OcrResult read(byte[] image, String extension) throws OcrException {
        if (!"png".equals(extension) && !"jpg".equals(extension)) {
            throw new OcrException("UNSUPPORTED_FORMAT", "Only PNG and JPEG images can be read");
        }
        Path file = null;
        try {
            file = Files.createTempFile("receipt-", "." + extension);
            Files.write(file, image);
            Process process = new ProcessBuilder(command(file))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // The time limit is enforced by a watchdog, not by waiting after the read: an engine that hangs without closing
            // its output would otherwise block the read forever and the limit would never apply.
            AtomicBoolean timedOut = new AtomicBoolean();
            Thread watchdog = new Thread(() -> {
                try {
                    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "receipt-ocr-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            byte[] output;
            try {
                output = readCapped(process.getInputStream());
            } catch (OcrException | IOException e) {
                process.destroyForcibly(); // never leave a runaway engine behind
                if (timedOut.get()) {
                    throw new OcrException("OCR_TIMEOUT", "The OCR engine did not finish in time");
                }
                throw e;
            }
            process.waitFor();
            watchdog.interrupt();
            if (timedOut.get()) {
                throw new OcrException("OCR_TIMEOUT", "The OCR engine did not finish in time");
            }
            if (process.exitValue() != 0) {
                throw new OcrException("OCR_FAILED", "The OCR engine could not read this image");
            }
            return parseTsv(new String(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Tesseract could not run: {}", e.getClass().getSimpleName());
            throw new OcrException("OCR_ENGINE_UNAVAILABLE", "The OCR engine could not be started");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("OCR_INTERRUPTED", "The OCR run was interrupted");
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // the temp directory is cleaned by the host
                }
            }
        }
    }

    /** The command line: no shell, a fixed argument list, the image given only as a temp file path. */
    protected List<String> command(Path file) {
        return List.of(executable, file.toString(), "stdout", "-l", language, "--psm", "6", "tsv");
    }

    private static byte[] readCapped(InputStream in) throws IOException, OcrException {
        byte[] data = in.readNBytes(MAX_OUTPUT_BYTES + 1);
        if (data.length > MAX_OUTPUT_BYTES) {
            throw new OcrException("OCR_OUTPUT_TOO_LARGE", "The OCR output was unreasonably large");
        }
        return data;
    }

    /**
     * Turns Tesseract's TSV (one row per word: level, page, block, paragraph, line, word, box, confidence, text) into
     * lines of text and the mean word confidence on a 0 to 1 scale. Rows without a recognised word (confidence -1) are
     * layout rows and are ignored.
     */
    static OcrResult parseTsv(String tsv) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentKey = null;
        double sum = 0;
        int words = 0;
        for (String row : tsv.split("\\R")) {
            String[] cols = row.split("\t", -1);
            if (cols.length < 12 || "level".equals(cols[0])) {
                continue;
            }
            double conf;
            try {
                conf = Double.parseDouble(cols[10]);
            } catch (NumberFormatException e) {
                continue;
            }
            String text = cols[11].trim();
            if (conf < 0 || text.isEmpty()) {
                continue;
            }
            String key = cols[2] + "." + cols[3] + "." + cols[4];
            if (currentKey != null && !currentKey.equals(key)) {
                lines.add(current.toString());
                current.setLength(0);
            }
            currentKey = key;
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(text);
            sum += conf;
            words++;
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        double mean = words == 0 ? 0 : Math.max(0, Math.min(1, sum / words / 100.0));
        return new OcrResult(String.join("\n", lines), mean);
    }
}
