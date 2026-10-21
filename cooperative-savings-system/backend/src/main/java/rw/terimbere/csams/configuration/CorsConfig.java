package rw.terimbere.csams.configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> exact = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        for (String origin : parseOrigins(corsProperties.getAllowedOrigins())) {
            if (origin.contains("*")) {
                patterns.add(origin);
            } else {
                exact.add(origin);
            }
        }
        if (!exact.isEmpty()) {
            configuration.setAllowedOrigins(exact);
        }
        if (!patterns.isEmpty()) {
            configuration.setAllowedOriginPatterns(patterns);
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of(
                "X-Request-Id",
                "Authorization",
                "Content-Disposition",
                "Content-Type",
                "Content-Length"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> parseOrigins(String allowedOrigins) {
        if (!StringUtils.hasText(allowedOrigins)) {
            return List.of(
                    "http://localhost:5173",
                    "https://terimbere-csams.vercel.app",
                    "https://wealthcommunity.ousuite.com");
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
