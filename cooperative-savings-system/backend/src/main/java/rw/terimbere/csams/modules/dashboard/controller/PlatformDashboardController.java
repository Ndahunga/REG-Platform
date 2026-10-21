package rw.terimbere.csams.modules.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.dashboard.dto.PlatformOverviewResponse;
import rw.terimbere.csams.modules.dashboard.service.DashboardService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/platform/dashboard")
@RequiredArgsConstructor
@Tag(name = "Platform dashboard", description = "System-wide overview for Super Admin")
@SecurityRequirement(name = "bearerAuth")
public class PlatformDashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Platform-wide counts for Super Admin home")
    public ResponseEntity<ApiResponse<PlatformOverviewResponse>> overview() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.platformOverview()));
    }
}
