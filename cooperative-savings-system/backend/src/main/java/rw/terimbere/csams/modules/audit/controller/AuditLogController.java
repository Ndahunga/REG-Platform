package rw.terimbere.csams.modules.audit.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.audit.dto.AuditLogResponse;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "Cooperative-scoped audit trail")
@SecurityRequirement(name = "bearerAuth")
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    @Operation(summary = "List audit logs for a cooperative")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                auditService.listForCooperative(cooperativeId, action, userId, entityType, from, to, pageable)));
    }

    @GetMapping("/{auditId}")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    @Operation(summary = "Get a single audit log entry")
    public ResponseEntity<ApiResponse<AuditLogResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID auditId) {
        return ResponseEntity.ok(ApiResponse.ok(auditService.getForCooperative(cooperativeId, auditId)));
    }
}
