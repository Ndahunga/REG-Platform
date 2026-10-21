package rw.terimbere.csams.modules.historicalimport.strategy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;

/** The scheme's one migration strategy: opening balances or detailed transaction history, never both. */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/migration-strategy")
@RequiredArgsConstructor
@Tag(name = "Historical Import", description = "How a scheme brings its past into the system")
@SecurityRequirement(name = "bearerAuth")
public class MigrationStrategyController {

    private final HistoricalMigrationGuard guard;
    private final CooperativeAuthorizationService authorizationService;

    public record StrategyRequest(@NotNull MigrationStrategy strategy, LocalDate cutoverDate) {}

    public record ResolveRequest(@NotNull MigrationStrategy strategy, LocalDate cutoverDate, String note) {}

    public record StrategyResponse(
            MigrationStrategy strategy,
            LocalDate cutoverDate,
            String source,
            boolean needsReview,
            boolean locked,
            java.time.Instant resolvedAt) {}

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The scheme's migration strategy (null while none is chosen)")
    public ResponseEntity<ApiResponse<StrategyResponse>> get(@PathVariable UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        return ResponseEntity.ok(ApiResponse.ok(view(cooperativeId, guard.current(cooperativeId))));
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Choose the scheme's migration strategy; refused once history has been brought in another way")
    public ResponseEntity<ApiResponse<StrategyResponse>> choose(
            @PathVariable UUID cooperativeId, @Valid @RequestBody StrategyRequest request) {
        authorizationService.requireMembership(cooperativeId);
        var principal = authorizationService.currentPrincipal();
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) && !CooperativeOfficerRoles.isLeadership(principal)) {
            throw new ForbiddenException("Only cooperative leadership can choose the migration strategy");
        }
        MigrationStrategyRecord saved = guard.choose(cooperativeId, request.strategy(), request.cutoverDate(), principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(view(cooperativeId, saved)));
    }

    /**
     * Resolves a scheme's migration review: the President or Vice President (fund authority) or a platform administrator
     * says which strategy is authoritative and how the scheme was reconciled. Audited; never guessed.
     */
    @PostMapping("/resolve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Resolve a scheme's migration review (fund authority or platform administrator)")
    public ResponseEntity<ApiResponse<StrategyResponse>> resolve(
            @PathVariable UUID cooperativeId, @Valid @RequestBody ResolveRequest request) {
        authorizationService.requireMembership(cooperativeId);
        var principal = authorizationService.currentPrincipal();
        CooperativeOfficerRoles.requireFundAuthorize(principal);
        MigrationStrategyRecord saved =
                guard.resolveReview(cooperativeId, request.strategy(), request.cutoverDate(), request.note(), principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(view(cooperativeId, saved)));
    }

    private StrategyResponse view(UUID cooperativeId, MigrationStrategyRecord record) {
        if (record == null) {
            return new StrategyResponse(null, null, null, false, false, null);
        }
        return new StrategyResponse(
                record.getStrategy(),
                record.getCutoverDate(),
                record.getSource(),
                record.isNeedsReview(),
                guard.anythingImported(cooperativeId),
                record.getResolvedAt());
    }
}
