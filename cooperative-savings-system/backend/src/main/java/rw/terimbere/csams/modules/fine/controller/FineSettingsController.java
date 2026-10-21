package rw.terimbere.csams.modules.fine.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import rw.terimbere.csams.modules.fine.dto.FineSettingsResponse;
import rw.terimbere.csams.modules.fine.dto.FineSettingsUpdateRequest;
import rw.terimbere.csams.modules.fine.service.FineSettingsService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/fine-settings")
@RequiredArgsConstructor
@Tag(name = "Fine Settings", description = "Per-cooperative fine configuration")
@SecurityRequirement(name = "bearerAuth")
public class FineSettingsController {

    private final FineSettingsService fineSettingsService;

    @GetMapping
    @PreAuthorize("hasAuthority('FINE_READ')")
    @Operation(summary = "Get fine settings (creates defaults if missing)")
    public ResponseEntity<ApiResponse<FineSettingsResponse>> get(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(fineSettingsService.getOrCreate(cooperativeId)));
    }

    @PutMapping
    @PreAuthorize("hasRole('PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Update fine settings (President only)")
    public ResponseEntity<ApiResponse<FineSettingsResponse>> update(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody FineSettingsUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(fineSettingsService.update(cooperativeId, request, httpRequest)));
    }
}
