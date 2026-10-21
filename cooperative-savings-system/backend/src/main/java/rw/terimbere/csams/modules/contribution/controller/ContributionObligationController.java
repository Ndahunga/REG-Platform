package rw.terimbere.csams.modules.contribution.controller;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.contribution.obligation.ContributionDueDayService;
import rw.terimbere.csams.modules.contribution.obligation.ContributionObligationService;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.DueDayRequest;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.DueDayResponse;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.MaterializeRequest;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.MaterializeResponse;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/**
 * Contribution due day and obligation snapshots (Task 3B-2). Nothing here posts, allocates or collects money: an
 * obligation is an expectation. The per-period amounts, due dates, overdue amounts and expected rotating/retained
 * components are returned by the existing contribution endpoints (period grid, history, my contributions, preview).
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}")
@Tag(name = "Contribution obligations")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class ContributionObligationController {

    private final ContributionDueDayService dueDayService;
    private final ContributionObligationService obligationService;

    @GetMapping("/contribution-due-day")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The scheme's monthly contribution due day (configured or not) and its history")
    public ResponseEntity<ApiResponse<DueDayResponse>> dueDay(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(dueDayService.view(cooperativeId)));
    }

    @PostMapping("/contribution-due-day")
    @PreAuthorize("hasAuthority('COOPERATIVE_WRITE')")
    @Operation(summary = "Set the due day from next month on (explicit confirmation required; history is kept)")
    public ResponseEntity<ApiResponse<DueDayResponse>> setDueDay(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody DueDayRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(dueDayService.set(cooperativeId, request, http)));
    }

    @PostMapping("/contributions/obligations/materialize")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(
            summary = "Create the missing obligation rows of active members for the current or next month",
            description = "Idempotent: existing rows are never changed. Posts nothing and records no payment.")
    public ResponseEntity<ApiResponse<MaterializeResponse>> materialize(
            @PathVariable UUID cooperativeId, @Valid @RequestBody MaterializeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                obligationService.materialize(cooperativeId, request.year(), request.month())));
    }
}
