package rw.terimbere.csams.modules.notification.mail;

import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Simple branded email body. Recipient locale is not persisted on User, so copy stays English.
 */
public final class NotificationEmailCopy {

    public static final String BRAND = "OuWealth Community";
    public static final String FOOTER =
            "This is an automated message from OuWealth Community.\nPlease do not reply to this email.";

    private NotificationEmailCopy() {}

    public static String subject(String title) {
        String cleaned = StringUtils.hasText(title) ? title.trim() : BRAND;
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200);
    }

    /**
     * Visible From uses the brand name; the address always comes from {@code MAIL_FROM}.
     */
    public static InternetAddress fromAddress(String from) throws UnsupportedEncodingException {
        return new InternetAddress(from.trim(), BRAND, "UTF-8");
    }

    public static String plainText(String title, String body) {
        String content = StringUtils.hasText(body) ? body.trim() : (StringUtils.hasText(title) ? title.trim() : "");
        StringBuilder text = new StringBuilder();
        text.append(BRAND);
        if (StringUtils.hasText(content)) {
            text.append("\n\n").append(content);
        }
        text.append("\n\n").append(FOOTER);
        return text.toString();
    }

    public static String html(String title, String body) {
        String content = StringUtils.hasText(body)
                ? HtmlUtils.htmlEscape(body.trim()).replace("\n", "<br>")
                : HtmlUtils.htmlEscape(StringUtils.hasText(title) ? title.trim() : "");
        return """
                <!DOCTYPE html>
                <html lang="en">
                <body style="font-family: Arial, Helvetica, sans-serif; color: #1a1a1a; line-height: 1.5;">
                  <p style="font-size: 18px; font-weight: 700; color: #0b3d2e; margin: 0 0 16px;">%s</p>
                  %s
                  <hr style="border: none; border-top: 1px solid #ddd; margin: 24px 0 12px;">
                  <p style="font-size: 12px; color: #666; margin: 0;">%s</p>
                </body>
                </html>
                """
                .formatted(
                        HtmlUtils.htmlEscape(BRAND),
                        StringUtils.hasText(content) ? "<p style=\"margin: 0;\">" + content + "</p>" : "",
                        HtmlUtils.htmlEscape(FOOTER).replace("\n", "<br>"));
    }
}
