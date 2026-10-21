package rw.terimbere.csams.modules.rotation.reconciliation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;
import rw.terimbere.csams.modules.rotation.reconciliation.LegacyReconciliationService.Candidate;
import rw.terimbere.csams.modules.rotation.reconciliation.LegacyReconciliationService.EventResponse;
import rw.terimbere.csams.modules.rotation.reconciliation.LegacyReconciliationService.ReconciliationResponse;
import rw.terimbere.csams.modules.rotation.reconciliation.LegacyReconciliationService.RequestCommand;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/** Reconciliation of rotating contributions recorded without verification evidence. Never creates a ledger entry. */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/rotation/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Rotation reconciliation")
@SecurityRequirement(name = "bearerAuth")
public class LegacyReconciliationController {

    private static final String VIEW = "hasAnyAuthority('CONTRIBUTION_WRITE','FUND_AUTHORIZE')";

    private final LegacyReconciliationService service;

    @GetMapping("/candidates")
    @PreAuthorize(VIEW)
    @Operation(summary = "Rotating obligations with paid money that is not yet verified")
    public ResponseEntity<ApiResponse<List<Candidate>>> candidates(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.candidates(cooperativeId)));
    }

    @GetMapping("/requests")
    @PreAuthorize(VIEW)
    @Operation(summary = "Reconciliation requests of the scheme")
    public ResponseEntity<ApiResponse<List<ReconciliationResponse>>> list(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(cooperativeId)));
    }

    @PostMapping("/requests")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Request reconciliation of an unverified rotating contribution")
    public ResponseEntity<ApiResponse<ReconciliationResponse>> request(
            @PathVariable UUID cooperativeId, @Valid @RequestBody RequestPayload body) {
        return ResponseEntity.ok(ApiResponse.ok(service.request(
                cooperativeId,
                new RequestCommand(body.getContributionId(), body.getAmount(), body.getMethod(), body.getBasis(), body.getReference()))));
    }

    @PostMapping("/requests/{id}/approve")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Confirm the amount as received (independent authorizer). Creates no ledger entry")
    public ResponseEntity<ApiResponse<ReconciliationResponse>> approve(
            @PathVariable UUID cooperativeId, @PathVariable UUID id, @RequestBody(required = false) NoteBody body) {
        return ResponseEntity.ok(ApiResponse.ok(service.approve(cooperativeId, id, body == null ? null : body.getNote())));
    }

    @PostMapping("/requests/{id}/decline")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Decline a reconciliation request")
    public ResponseEntity<ApiResponse<ReconciliationResponse>> decline(
            @PathVariable UUID cooperativeId, @PathVariable UUID id, @RequestBody(required = false) NoteBody body) {
        return ResponseEntity.ok(ApiResponse.ok(service.decline(cooperativeId, id, body == null ? null : body.getNote())));
    }

    @PostMapping("/requests/{id}/withdraw")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Withdraw your own open reconciliation request")
    public ResponseEntity<ApiResponse<ReconciliationResponse>> withdraw(
            @PathVariable UUID cooperativeId, @PathVariable UUID id, @RequestBody(required = false) NoteBody body) {
        return ResponseEntity.ok(ApiResponse.ok(service.withdraw(cooperativeId, id, body == null ? null : body.getNote())));
    }

    @GetMapping("/requests/{id}/events")
    @PreAuthorize(VIEW)
    @Operation(summary = "Immutable history of a reconciliation request")
    public ResponseEntity<ApiResponse<List<EventResponse>>> events(@PathVariable UUID cooperativeId, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.events(cooperativeId, id)));
    }

    @Data
    @NoArgsConstructor
    public static class RequestPayload {
        @NotNull
        private UUID contributionId;

        @NotNull
        private BigDecimal amount;

        @NotNull
        private VerificationMethod method;

        @Size(max = 1000)
        private String basis;

        @Size(max = 128)
        private String reference;
    }

    @Data
    @NoArgsConstructor
    public static class NoteBody {
        @Size(max = 2000)
        private String note;
    }
}
