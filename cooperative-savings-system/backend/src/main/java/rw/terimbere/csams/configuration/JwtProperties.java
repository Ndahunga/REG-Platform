package rw.terimbere.csams.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String accessSecret;
    private String refreshSecret;
    private long accessExpirationMs = 900_000L;
    private long refreshExpirationMs = 604_800_000L;
    private String refreshCookieName = "csams_refresh_token";
    private boolean refreshCookieSecure = false;
    private String refreshCookieSameSite = "Lax";
}
