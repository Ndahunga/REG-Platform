package rw.terimbere.csams.modules.memberpayment.receipt;

import com.lowagie.text.exceptions.BadPasswordException;
import com.lowagie.text.pdf.PRStream;
import com.lowagie.text.pdf.PdfArray;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads a PDF receipt: the text it carries, or, when it is only a picture (a screenshot saved as a PDF), the embedded
 * JPEG so that the OCR engine can read it.
 *
 * <p>A PDF is untrusted input, so reading is bounded everywhere: only the first few pages are looked at, a document with
 * many pages is refused (a receipt has one or two), the text taken is capped, a document that needs a password is refused
 * (one that only restricts editing opens normally and is read), nothing
 * is executed or rendered, and the whole job runs on a small bounded pool with a time limit, so a hostile file can cost a
 * few seconds of one thread at most and never the server. Every outcome is a stable failure code, never an exception.
 */
@Component
public class ReceiptPdfReader {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPdfReader.class);

    static final int MAX_PAGES_ALLOWED = 10;
    static final int MAX_PAGES_READ = 3;
    static final int MAX_TEXT_CHARS = 20_000;
    /** Fewer visible characters than this means the PDF carries no real text layer. */
    static final int MIN_TEXT_CHARS = 20;

    /** What a PDF held: its text, or an embedded JPEG when it has none; or why neither could be had. */
    public record Content(String text, byte[] jpeg, String failureCode) {
        static Content failure(String code) {
            return new Content(null, null, code);
        }

        public boolean hasText() {
            return text != null;
        }
    }

    private final ExecutorService pool = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "receipt-pdf-reader");
                thread.setDaemon(true);
                return thread;
            });

    @Value("${app.ocr.timeout-seconds:30}")
    private long timeoutSeconds;

    public Content read(byte[] pdf) {
        Future<Content> job;
        try {
            job = pool.submit(() -> readNow(pdf));
        } catch (RejectedExecutionException busy) {
            return Content.failure("PDF_BUSY");
        }
        try {
            return job.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            job.cancel(true);
            return Content.failure("PDF_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            job.cancel(true);
            return Content.failure("PDF_UNREADABLE");
        } catch (ExecutionException failed) {
            log.warn("Receipt PDF could not be read: {}", failed.getCause() == null ? "?" : failed.getCause().getClass().getSimpleName());
            return Content.failure("PDF_UNREADABLE");
        }
    }

    private static Content readNow(byte[] pdf) {
        PdfReader reader = null;
        try {
            reader = new PdfReader(pdf);
            int pages = reader.getNumberOfPages();
            if (pages < 1) {
                return Content.failure("PDF_UNREADABLE");
            }
            if (pages > MAX_PAGES_ALLOWED) {
                return Content.failure("PDF_TOO_MANY_PAGES");
            }
            StringBuilder text = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= Math.min(pages, MAX_PAGES_READ) && text.length() < MAX_TEXT_CHARS; page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            String visible = text.toString();
            if (visible.replaceAll("\\s+", "").length() >= MIN_TEXT_CHARS) {
                return new Content(visible.length() > MAX_TEXT_CHARS ? visible.substring(0, MAX_TEXT_CHARS) : visible, null, null);
            }
            byte[] jpeg = firstEmbeddedJpeg(reader, Math.min(pages, MAX_PAGES_READ));
            return jpeg == null ? Content.failure("PDF_NO_TEXT") : new Content(null, jpeg, null);
        } catch (BadPasswordException locked) {
            return Content.failure("PDF_ENCRYPTED");
        } catch (IOException | RuntimeException broken) {
            return Content.failure("PDF_UNREADABLE");
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    /** The first embedded image stored as a JPEG (DCTDecode) on the first pages, or null. */
    private static byte[] firstEmbeddedJpeg(PdfReader reader, int pages) throws IOException {
        for (int page = 1; page <= pages; page++) {
            PdfDictionary dictionary = reader.getPageN(page);
            PdfDictionary resources = dictionary == null ? null : dictionary.getAsDict(PdfName.RESOURCES);
            PdfDictionary xobjects = resources == null ? null : resources.getAsDict(PdfName.XOBJECT);
            if (xobjects == null) {
                continue;
            }
            for (PdfName name : xobjects.getKeys()) {
                PdfObject object = xobjects.getDirectObject(name);
                if (object instanceof PRStream stream
                        && PdfName.IMAGE.equals(stream.getAsName(PdfName.SUBTYPE))
                        && usesJpeg(stream)) {
                    return PdfReader.getStreamBytesRaw(stream);
                }
            }
        }
        return null;
    }

    private static boolean usesJpeg(PRStream stream) {
        PdfObject filter = stream.getDirectObject(PdfName.FILTER);
        if (filter instanceof PdfName name) {
            return PdfName.DCTDECODE.equals(name);
        }
        if (filter instanceof PdfArray array && array.size() == 1) {
            return PdfName.DCTDECODE.equals(array.getAsName(0));
        }
        return false;
    }
}
