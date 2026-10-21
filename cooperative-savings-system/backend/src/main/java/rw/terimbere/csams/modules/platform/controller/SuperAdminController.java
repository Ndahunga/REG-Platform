package rw.terimbere.csams.modules.platform.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.platform.dto.SuperAdminCreateRequest;
import rw.terimbere.csams.modules.platform.dto.SuperAdminResponse;
import rw.terimbere.csams.modules.platform.dto.SuperAdminStatusRequest;
import rw.terimbere.csams.modules.platform.service.SuperAdminService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/platform/super-admins")
@RequiredArgsConstructor
@Tag(name = "Platform administrators", description = "System SUPER_ADMIN accounts (platform-wide access)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminController {

    private final SuperAdminService superAdminService;

    @GetMapping
    @Operation(summary = "List system super admins")
    public ResponseEntity<ApiResponse<List<SuperAdminResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(superAdminService.list()));
    }

    @PostMapping
    @Operation(summary = "Create a system super admin (no cooperative membership)")
    public ResponseEntity<ApiResponse<SuperAdminResponse>> create(
            @Valid @RequestBody SuperAdminCreateRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(
                        "Super admin account created", superAdminService.create(request, httpRequest)));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Deactivate (INACTIVE) or reactivate (ACTIVE) a system super admin")
    public ResponseEntity<ApiResponse<SuperAdminResponse>> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody SuperAdminStatusRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Super admin status updated", superAdminService.changeStatus(id, request.getStatus(), httpRequest)));
    }
}
