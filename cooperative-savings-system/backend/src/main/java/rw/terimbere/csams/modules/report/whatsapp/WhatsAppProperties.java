package rw.terimbere.csams.modules.report.whatsapp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.whatsapp")
public class WhatsAppProperties {

    /**
     * Master switch. Report sharing and notification delivery stay off unless this is true
     * and credentials are present.
     */
    private boolean enabled = false;

    /** WhatsApp Cloud API permanent/system user token. Never expose to the frontend. */
    private String accessToken = "";

    /** Meta WhatsApp phone-number ID (not the human-readable phone number). */
    private String phoneNumberId = "";

    /** Graph API version, e.g. {@code v21.0}. */
    private String apiVersion = "v21.0";

    /**
     * Optional pre-approved template name for business-initiated document messages.
     * When blank, a session document message is sent (works only in the 24-hour customer window).
     */
    private String templateName = "";

    private String templateLanguage = "en";

    public boolean isConfigured() {
        return enabled
                && StringUtils.hasText(accessToken)
                && StringUtils.hasText(phoneNumberId)
                && StringUtils.hasText(apiVersion);
    }

    public boolean hasTemplate() {
        return StringUtils.hasText(templateName);
    }
}
