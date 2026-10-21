package rw.terimbere.csams.modules.report.export;

import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.BaseFont;
import java.awt.Color;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared PDF branding: OuWealth navy, Candara table type, and the product logo.
 */
final class PdfReportStyle {

    static final Color BRAND_BLUE = new Color(0x1B, 0x4D, 0x8C);
    static final Color BRAND_BLUE_DARK = new Color(0x14, 0x3A, 0x6B);
    static final Color BRAND_ORANGE = new Color(0xFF, 0x7A, 0x00);
    static final Color ZEBRA = new Color(0xF3, 0xF6, 0xFA);
    static final Color TOTALS_BG = new Color(255, 243, 224);
    static final Color GRID = new Color(210, 222, 235);
    static final Color META_BG = new Color(245, 248, 252);
    static final Color MUTED = new Color(90, 98, 112);

    private static final BaseFont CANDARA = loadFace("/fonts/Candara.ttf", "Candara.ttf");
    private static final BaseFont CANDARA_BOLD = loadFace("/fonts/Candara-Bold.ttf", "Candarab.ttf");
    /**
     * Temporary raster wordmark (same family as the frontend light-surface asset).
     * Replace with an official transparent SVG/high-resolution PNG when supplied.
     * Do not upscale or stretch; scaleToFit preserves aspect ratio.
     */
    private static final byte[] LOGO_PNG = loadResource("/branding/ouwealth-community-logo.png");
    private static final float LOGO_MAX_WIDTH = 168f;
    private static final float LOGO_MAX_HEIGHT = 46f;

    private PdfReportStyle() {}

    static boolean hasCandara() {
        return CANDARA != null;
    }

    static boolean hasLogo() {
        return LOGO_PNG != null && LOGO_PNG.length > 0;
    }

    static Image logo() {
        if (!hasLogo()) {
            return null;
        }
        try {
            Image image = Image.getInstance(LOGO_PNG);
            float nativeWidth = image.getPlainWidth();
            float nativeHeight = image.getPlainHeight();
            image.scaleToFit(Math.min(LOGO_MAX_WIDTH, nativeWidth), Math.min(LOGO_MAX_HEIGHT, nativeHeight));
            return image;
        } catch (Exception ex) {
            return null;
        }
    }

    static Font table(float size, Color color) {
        return face(false, size, Font.NORMAL, color);
    }

    static Font tableBold(float size, Color color) {
        return face(true, size, Font.BOLD, color);
    }

    static Font heading(float size, Color color) {
        return face(true, size, Font.BOLD, color);
    }

    private static Font face(boolean bold, float size, int fallbackStyle, Color color) {
        BaseFont base = bold && CANDARA_BOLD != null ? CANDARA_BOLD : CANDARA;
        if (base != null) {
            return new Font(base, size, Font.NORMAL, color);
        }
        String family = bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA;
        return FontFactory.getFont(family, size, fallbackStyle, color);
    }

    private static BaseFont loadFace(String classpath, String windowsFileName) {
        byte[] bytes = loadResource(classpath);
        if (bytes == null) {
            bytes = loadWindowsFont(windowsFileName);
        }
        if (bytes == null) {
            return null;
        }
        try {
            return BaseFont.createFont(
                    windowsFileName, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
        } catch (Exception ex) {
            return null;
        }
    }

    private static byte[] loadWindowsFont(String fileName) {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) {
            windir = "C:\\Windows";
        }
        Path path = Path.of(windir, "Fonts", fileName);
        try {
            return Files.exists(path) ? Files.readAllBytes(path) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static byte[] loadResource(String path) {
        try (InputStream in = PdfReportStyle.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception ex) {
            return null;
        }
    }
}
