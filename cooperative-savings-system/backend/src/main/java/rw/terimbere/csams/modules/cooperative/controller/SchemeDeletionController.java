package rw.terimbere.csams.modules.cooperative.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import rw.terimbere.csams.modules.cooperative.service.SchemeDeletionService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{id}/permanent-deletion")
@RequiredArgsConstructor
@Tag(name = "Cooperatives")
@SecurityRequirement(name = "bearerAuth")
public class SchemeDeletionController {

    private final SchemeDeletionService service;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "What deleting this scheme for good would remove (nothing is changed)")
    public ResponseEntity<ApiResponse<SchemeDeletionService.Preview>> preview(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.preview(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Delete this scheme and all its data for good (type its name and give a reason)")
    public ResponseEntity<ApiResponse<SchemeDeletionService.Result>> delete(
            @PathVariable UUID id, @RequestBody SchemeDeletionService.DeleteRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(service.delete(id, request, http)));
    }
}
