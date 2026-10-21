package rw.terimbere.csams.modules.systemhealth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "System Health", description = "Public health and authenticated system information")
public class SystemInfoController {

    private final Environment environment;
    private final DataSource dataSource;
    private final ObjectProvider<Flyway> flywayProvider;
    private final String applicationName;
    private final String applicationVersion;

    public SystemInfoController(
            Environment environment,
            DataSource dataSource,
            ObjectProvider<Flyway> flywayProvider,
            @Value("${spring.application.name:terimbere-csams}") String applicationName,
            @Value("${app.version:1.0.0-SNAPSHOT}") String applicationVersion) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.flywayProvider = flywayProvider;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
    }

    @GetMapping("/public/health")
    @Operation(summary = "Public health check")
    public ResponseEntity<Map<String, String>> publicHealth() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/system/info")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "System information (SUPER_ADMIN only)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> systemInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", applicationName);
        info.put("version", applicationVersion);
        info.put("profiles", environment.getActiveProfiles());
        info.put("timestamp", Instant.now().toString());
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("dbReachable", isDbReachable());
        info.put("flywayVersion", currentFlywayVersion());
        return ResponseEntity.ok(ApiResponse.ok(info));
    }

    private boolean isDbReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception ex) {
            return false;
        }
    }

    private String currentFlywayVersion() {
        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) {
            return null;
        }
        try {
            MigrationInfo current = flyway.info().current();
            return current == null || current.getVersion() == null
                    ? null
                    : current.getVersion().getVersion();
        } catch (Exception ex) {
            return null;
        }
    }
}
