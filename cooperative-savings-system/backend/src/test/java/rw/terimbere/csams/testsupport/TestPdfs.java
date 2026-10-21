package rw.terimbere.csams.testsupport;

import com.lowagie.text.Document;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Builds small real PDFs and JPEGs for the receipt tests, needing no image codec or cryptography library. */
public final class TestPdfs {

    private TestPdfs() {}

    /** A PDF whose pages carry the given text lines (one page per entry). */
    public static byte[] withText(String... pages) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, out);
        document.open();
        for (int i = 0; i < pages.length; i++) {
            if (i > 0) {
                document.newPage();
            }
            for (String line : pages[i].split("\n")) {
                document.add(new Paragraph(line));
            }
        }
        document.close();
        return out.toByteArray();
    }

    /** A PDF that is only a picture (a screenshot saved as a PDF): no text layer at all. */
    public static byte[] imageOnly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, out);
        document.open();
        document.add(Image.getInstance(jpeg()));
        document.close();
        return out.toByteArray();
    }

    /** A PDF with a page that has no visible content. */
    public static byte[] blank() throws Exception {
        return withText(" ");
    }

    /**
     * A PDF that declares standard encryption. It is written by hand because the PDF library here cannot write encrypted
     * files without an extra cryptography dependency; its keys are junk, so it can never be opened without the password.
     */
    public static byte[] encrypted() {
        String pdf = String.join(
                "\n",
                "%PDF-1.4",
                "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj",
                "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj",
                "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 10 10]>>endobj",
                "4 0 obj<</Filter/Standard/V 1/R 2/O(aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)/U(bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb)/P -4>>endobj",
                "trailer<</Root 1 0 R/Size 5/Encrypt 4 0 R/ID[<00112233445566778899aabbccddeeff><00112233445566778899aabbccddeeff>]>>",
                "%%EOF",
                "");
        return pdf.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A 1x1 JPEG (a real one, so no image codec is needed on the machine running the tests). */
    public static byte[] jpeg() {
        return Base64.getDecoder()
                .decode("/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////"
                        + "wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=");
    }
}
