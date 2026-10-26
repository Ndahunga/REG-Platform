package rw.terimbere.csams.modules.cooperative.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.cooperative.service.OperatingModelChangeService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/operating-model/changes")
@Tag(name = "Operating model changes")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class OperatingModelChangeController {

    private final OperatingModelChangeService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Scheduled, applied and cancelled operating model changes")
    public ResponseEntity<ApiResponse<List<OperatingModelChangeService.ChangeResponse>>> list(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(cooperativeId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Schedule a change of operating model from a month that has not begun")
    public ResponseEntity<ApiResponse<OperatingModelChangeService.ChangeResponse>> schedule(
            @PathVariable UUID cooperativeId, @RequestBody OperatingModelChangeService.ChangeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.schedule(cooperativeId, request)));
    }

    @PostMapping("/{changeId}/cancel")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Cancel a scheduled change before its month begins")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable UUID cooperativeId, @PathVariable UUID changeId) {
        service.cancel(cooperativeId, changeId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
