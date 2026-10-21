package rw.terimbere.csams.modules.contact.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where public contact messages go and which verified sender they are sent from. Both come from server config. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.support")
public class SupportContactProperties {

    /** Recipient of every contact message. Never taken from the request. */
    private String emailTo = "support@ozufy.com";

    /** Verified sender address. Falls back to the shared MAIL_FROM when not set. */
    private String emailFrom = "";
}
