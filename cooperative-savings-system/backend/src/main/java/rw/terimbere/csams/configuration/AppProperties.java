package rw.terimbere.csams.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.security")
public class AppProperties {

    private int maxFailedLoginAttempts = 5;
    private int lockDurationMinutes = 15;
}
