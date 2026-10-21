package rw.terimbere.csams.modules.notification.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    /**
     * Master switch. When false the email channel logs and skips even if SMTP is present.
     */
    private boolean enabled = true;

    /** SMTP host. Empty means the email channel stays disabled. */
    private String host = "";

    /** Envelope From address. Required together with host before any message is sent. */
    private String from = "";

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(host) && StringUtils.hasText(from);
    }
}
