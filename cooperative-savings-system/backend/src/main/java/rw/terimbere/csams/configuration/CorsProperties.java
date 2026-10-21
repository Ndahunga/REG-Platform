package rw.terimbere.csams.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /**
     * Comma-separated list of allowed origins.
     * Exact hosts (https://app.example.com) and wildcard patterns (https://*.vercel.app) are both allowed.
     */
    private String allowedOrigins =
            "http://localhost:5173,https://terimbere-csams.vercel.app,https://wealthcommunity.ousuite.com";
}
