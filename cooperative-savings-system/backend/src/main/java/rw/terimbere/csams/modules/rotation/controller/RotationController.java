package rw.terimbere.csams.modules.rotation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.CancelRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.ConfigurationRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.ConfigurationResponse;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.CycleCreateRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.CycleResponse;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.PeriodResponse;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.RecipientChangeRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.RecipientChangeResponse;
import rw.terimbere.csams.modules.rotation.service.RotationConfigurationService;
import rw.terimbere.csams.modules.rotation.service.RotationCycleService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/**
 * Rotation configuration and schedule (Task 3B-1). Storage only: no endpoint here posts, allocates or distributes money.
 * The route-level check is only a first gate; the services enforce scheme membership, officer rights and the operating
 * model. Reads need no lock; every mutation takes the scheme fund lock inside its own transaction.
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/rotation")
@Tag(name = "Rotation")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class RotationController {

    private final RotationConfigurationService configurationService;
    private final RotationCycleService cycleService;

    @PostMapping("/configurations")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Create an effective-dated rotating/retained configuration (insert-only)")
    public ResponseEntity<ApiResponse<ConfigurationResponse>> createConfiguration(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody ConfigurationRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(configurationService.create(cooperativeId, request, http)));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/configurations/{configurationId}")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Delete a configuration that has not taken effect and was never used (409 otherwise)")
    public ResponseEntity<ApiResponse<Void>> deleteConfiguration(
            @PathVariable UUID cooperativeId, @PathVariable UUID configurationId, HttpServletRequest http) {
        configurationService.delete(cooperativeId, configurationId, http);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/configurations")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Configuration history, newest first")
    public ResponseEntity<ApiResponse<List<ConfigurationResponse>>> configurations(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(configurationService.history(cooperativeId)));
    }

    @GetMapping("/configurations/effective")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The configuration in force for a month (data is null when none is effective yet)")
    public ResponseEntity<ApiResponse<ConfigurationResponse>> effective(
            @PathVariable UUID cooperativeId, @RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(ApiResponse.ok(
                configurationService.effective(cooperativeId, year, month).orElse(null)));
    }

    @PostMapping("/cycles")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(
            summary = "Create a draft cycle with its monthly schedule",
            description = "Optional Idempotency-Key header: repeating the same request returns the original cycle.")
    public ResponseEntity<ApiResponse<CycleResponse>> createCycle(
            @PathVariable UUID cooperativeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CycleCreateRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.create(cooperativeId, request, idempotencyKey, http)));
    }

    @PostMapping("/share-cycles")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(
            summary = "Create a draft share-based cycle: a fixed payout per share, one turn per share, several turns a month allowed",
            description = "Optional Idempotency-Key header. The response carries a funding check (turns vs rotated contributions).")
    public ResponseEntity<ApiResponse<CycleResponse>> createShareCycle(
            @PathVariable UUID cooperativeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody rw.terimbere.csams.modules.rotation.dto.RotationDtos.ShareCycleCreateRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.createShareCycle(cooperativeId, request, idempotencyKey, http)));
    }

    @PutMapping("/turns/{turnId}/member")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Give a turn to another member before its month begins (reason required, audited)")
    public ResponseEntity<ApiResponse<CycleResponse>> reassignTurn(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID turnId,
            @Valid @RequestBody rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnReassignRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.reassignTurn(cooperativeId, turnId, request, http)));
    }

    @GetMapping("/cycles")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Cycles with their schedules (members see active and completed cycles only)")
    public ResponseEntity<ApiResponse<List<CycleResponse>>> cycles(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.list(cooperativeId)));
    }

    @GetMapping("/cycles/{cycleId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One cycle with its schedule")
    public ResponseEntity<ApiResponse<CycleResponse>> cycle(
            @PathVariable UUID cooperativeId, @PathVariable UUID cycleId) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.get(cooperativeId, cycleId)));
    }

    @PostMapping("/cycles/{cycleId}/activate")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Activate a draft cycle (it then holds its months)")
    public ResponseEntity<ApiResponse<CycleResponse>> activate(
            @PathVariable UUID cooperativeId, @PathVariable UUID cycleId, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.activate(cooperativeId, cycleId, http)));
    }

    @PostMapping("/cycles/{cycleId}/cancel")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Cancel a draft or not-yet-started cycle (history is kept, months are released)")
    public ResponseEntity<ApiResponse<CycleResponse>> cancel(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID cycleId,
            @Valid @RequestBody CancelRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.cancel(cooperativeId, cycleId, request, http)));
    }

    @GetMapping("/periods")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The live period and scheduled recipient for a month (empty when none)")
    public ResponseEntity<ApiResponse<List<PeriodResponse>>> periods(
            @PathVariable UUID cooperativeId, @RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.periodsForMonth(cooperativeId, year, month)));
    }

    @PutMapping("/periods/{periodId}/recipient")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Reassign a period's recipient before it begins (reason required, audited)")
    public ResponseEntity<ApiResponse<PeriodResponse>> changeRecipient(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID periodId,
            @Valid @RequestBody RecipientChangeRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.changeRecipient(cooperativeId, periodId, request, http)));
    }

    @GetMapping("/periods/{periodId}/recipient-changes")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Reassignment history of a period")
    public ResponseEntity<ApiResponse<List<RecipientChangeResponse>>> recipientChanges(
            @PathVariable UUID cooperativeId, @PathVariable UUID periodId) {
        return ResponseEntity.ok(ApiResponse.ok(cycleService.recipientChanges(cooperativeId, periodId)));
    }
}
