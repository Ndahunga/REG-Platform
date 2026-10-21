package rw.terimbere.csams.modules.settings.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.settings.dto.CooperativeSettingsResponse;
import rw.terimbere.csams.modules.settings.dto.CooperativeSettingsUpdateRequest;
import rw.terimbere.csams.modules.settings.service.CooperativeSettingsService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/settings")
@RequiredArgsConstructor
@Tag(name = "Cooperative Settings", description = "Per-cooperative preferences and notification toggles")
@SecurityRequirement(name = "bearerAuth")
public class CooperativeSettingsController {

    private final CooperativeSettingsService settingsService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SETTINGS_MANAGE', 'COOPERATIVE_WRITE')")
    @Operation(summary = "Get cooperative settings (creates defaults if missing)")
    public ResponseEntity<ApiResponse<CooperativeSettingsResponse>> get(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.getOrCreate(cooperativeId)));
    }

    @PutMapping
    @PreAuthorize("hasAnyAuthority('SETTINGS_MANAGE', 'COOPERATIVE_WRITE')")
    @Operation(summary = "Update cooperative settings")
    public ResponseEntity<ApiResponse<CooperativeSettingsResponse>> update(
            @PathVariable UUID cooperativeId, @Valid @RequestBody CooperativeSettingsUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.update(cooperativeId, request)));
    }
}
