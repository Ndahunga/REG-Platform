package rw.terimbere.csams.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI terimbereCsamsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TERIMBERE CSAMS API")
                        .version("1.0.0")
                        .description(
                                "Cooperative Savings Account Management System (CSAMS) REST API. "
                                        + "Phase 1 provides foundation security, auditing, and shared infrastructure.")
                        .contact(new Contact().name("TERIMBERE CSAMS").email("support@terimbere.rw")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .name(BEARER_SCHEME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
