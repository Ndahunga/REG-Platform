package rw.terimbere.csams.modules.cooperative.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeCreateRequest;
import rw.terimbere.csams.modules.cooperative.dto.OperatingModelClassificationRequest;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeResponse;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeStatusUpdateRequest;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeSummaryResponse;
import rw.terimbere.csams.modules.cooperative.dto.CooperativeUpdateRequest;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.service.CooperativeService;
import rw.terimbere.csams.modules.subscription.dto.CooperativeSubscriptionResponse;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives")
@RequiredArgsConstructor
@Tag(name = "Cooperatives", description = "Cooperative management")
@SecurityRequirement(name = "bearerAuth")
public class CooperativeController {

    private final CooperativeService cooperativeService;

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Create a cooperative (SUPER_ADMIN)")
    public ResponseEntity<ApiResponse<CooperativeResponse>> create(
            @Valid @RequestBody CooperativeCreateRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.create(request, httpRequest)));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List cooperatives (scoped by membership; SUPER_ADMIN sees all)")
    public ResponseEntity<ApiResponse<PageResponse<CooperativeResponse>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CooperativeStatus status,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.list(q, status, pageable)));
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List cooperatives for current user selector",
            description =
                    "Preferred over JWT coopIds after membership changes. JWT coopIds stay stale until token refresh.")
    public ResponseEntity<ApiResponse<List<CooperativeSummaryResponse>>> mine() {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.listMine()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get cooperative by id")
    public ResponseEntity<ApiResponse<CooperativeResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.getById(id)));
    }

    @GetMapping("/{id}/subscription")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get platform subscription for a cooperative",
            description =
                    "Minimal read of the current OuWealth platform subscription (NONE, TRIAL, ACTIVE, etc.). "
                            + "Authenticated members of the cooperative may call it; SUPER_ADMIN bypasses membership. "
                            + "This is not a billing, checkout, or payment API.")
    public ResponseEntity<ApiResponse<CooperativeSubscriptionResponse>> getSubscription(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.getSubscription(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Update cooperative")
    public ResponseEntity<ApiResponse<CooperativeResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody CooperativeUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.update(id, request, httpRequest)));
    }

    @PostMapping("/{id}/operating-model")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Classify a legacy scheme's operating model once (409 if it is already set)")
    public ResponseEntity<ApiResponse<CooperativeResponse>> classifyOperatingModel(
            @PathVariable UUID id,
            @Valid @RequestBody OperatingModelClassificationRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.classifyOperatingModel(id, request, httpRequest)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Update cooperative status (activate/suspend/inactive/archive)")
    public ResponseEntity<ApiResponse<CooperativeResponse>> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody CooperativeStatusUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.updateStatus(id, request, httpRequest)));
    }

    @PostMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE') or hasAuthority('FILE_MANAGE')")
    @Operation(summary = "Upload cooperative logo")
    public ResponseEntity<ApiResponse<CooperativeResponse>> uploadLogo(
            @PathVariable UUID id, @RequestParam("file") MultipartFile file, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(cooperativeService.uploadLogo(id, file, httpRequest)));
    }
}
