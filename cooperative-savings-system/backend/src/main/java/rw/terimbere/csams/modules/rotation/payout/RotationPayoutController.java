package rw.terimbere.csams.modules.rotation.payout;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService.LateRecovery;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService.PeriodEntitlement;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.ConfirmDisbursement;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.EventResponse;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.PayoutResponse;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.ReasonRequest;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.RequestPayout;
import rw.terimbere.csams.modules.rotation.repository.RotationPeriodRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Rotating entitlements and their payouts. Entitlements are derived from what was actually collected; a payout needs a
 * request, an independent approval and an explicit confirmation of the actual disbursement.
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/rotation")
@Tag(name = "Rotation payouts")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class RotationPayoutController {

    private static final String PAYOUT_WRITE = "PAYOUT_WRITE";

    private final RotationEntitlementService entitlementService;
    private final RotationPayoutService payoutService;
    private final RotationPeriodRepository periodRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final RotationRecipientObligationService obligationService;

    // ------------------------------------------------------------ members' obligations

    @GetMapping("/obligations")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Every member's contributions paid, payout received, remaining and overdue amounts (officers)")
    public ResponseEntity<ApiResponse<List<RotationRecipientObligationService.MemberObligation>>> obligations(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(obligationService.overview(cooperativeId)));
    }

    @GetMapping("/obligations/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "My own contributions paid, payout received, remaining and overdue amounts")
    public ResponseEntity<ApiResponse<RotationRecipientObligationService.MemberObligation>> myObligation(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(obligationService.mine(cooperativeId)));
    }

    @GetMapping("/obligations/members/{memberUserId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One member's rotation obligations (officers)")
    public ResponseEntity<ApiResponse<RotationRecipientObligationService.MemberObligation>> memberObligation(
            @PathVariable UUID cooperativeId, @PathVariable UUID memberUserId) {
        return ResponseEntity.ok(ApiResponse.ok(obligationService.forMember(cooperativeId, memberUserId)));
    }

    // ------------------------------------------------------------ entitlements

    @GetMapping("/entitlements")
    @PreAuthorize("hasAuthority('PAYOUT_WRITE')")
    @Operation(summary = "Every rotation period's expected, collected, distributed and outstanding amounts (officers)")
    public ResponseEntity<ApiResponse<List<PeriodEntitlement>>> overview(@PathVariable UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        return ResponseEntity.ok(ApiResponse.ok(entitlementService.overview(cooperativeId)));
    }

    @GetMapping("/entitlements/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The periods of which the caller is the original scheduled recipient")
    public ResponseEntity<ApiResponse<List<PeriodEntitlement>>> mine(@PathVariable UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        return ResponseEntity.ok(ApiResponse.ok(entitlementService.mine(cooperativeId, actor.getId())));
    }

    @GetMapping("/entitlements/{periodId}/recoveries")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Collections attributed to one period, late ones flagged (officers, or the period's recipient)")
    public ResponseEntity<ApiResponse<List<LateRecovery>>> recoveries(
            @PathVariable UUID cooperativeId, @PathVariable UUID periodId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        RotationPeriod period = periodRepository
                .findByIdAndCooperativeId(periodId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", periodId));
        boolean staff = actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) || actor.hasAuthority(PAYOUT_WRITE);
        if (!staff && !actor.getId().equals(period.getScheduledRecipientUserId())) {
            throw new ResourceNotFoundException("Rotation period", periodId);
        }
        return ResponseEntity.ok(ApiResponse.ok(entitlementService.recoveries(cooperativeId, periodId)));
    }

    // ------------------------------------------------------------ payouts

    @PostMapping("/payouts")
    @PreAuthorize("hasAuthority('PAYOUT_WRITE')")
    @Operation(
            summary = "Request a payout of a period's collected, undistributed entitlement",
            description = "Defaults to the most the entitlement and the available funds allow (a partial payout if needed). Moves no money.")
    public ResponseEntity<ApiResponse<PayoutResponse>> request(
            @PathVariable UUID cooperativeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RequestPayout request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.request(cooperativeId, request, idempotencyKey, http)));
    }

    @GetMapping("/payouts/eligibility")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Which periods can have a payout requested right now, and why not (officers)")
    public ResponseEntity<ApiResponse<List<RotationPayoutDtos.PeriodPayoutEligibility>>> eligibility(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.eligibility(cooperativeId)));
    }

    @GetMapping("/payouts")
    @PreAuthorize("hasAuthority('PAYOUT_WRITE')")
    @Operation(summary = "All rotation payouts of the scheme (officers)")
    public ResponseEntity<ApiResponse<List<PayoutResponse>>> list(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.list(cooperativeId)));
    }

    @GetMapping("/payouts/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The caller's own rotation payouts")
    public ResponseEntity<ApiResponse<List<PayoutResponse>>> mine2(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.mine(cooperativeId)));
    }

    @GetMapping("/payouts/{payoutId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One payout (officers, or its recipient)")
    public ResponseEntity<ApiResponse<PayoutResponse>> get(@PathVariable UUID cooperativeId, @PathVariable UUID payoutId) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.get(cooperativeId, payoutId)));
    }

    @GetMapping("/payouts/{payoutId}/events")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Immutable status history of a payout")
    public ResponseEntity<ApiResponse<List<EventResponse>>> events(@PathVariable UUID cooperativeId, @PathVariable UUID payoutId) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.events(cooperativeId, payoutId)));
    }

    @PostMapping("/payouts/{payoutId}/approve")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Approve a payout (a different officer from the requester; the recipient never)")
    public ResponseEntity<ApiResponse<PayoutResponse>> approve(
            @PathVariable UUID cooperativeId, @PathVariable UUID payoutId, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.approve(cooperativeId, payoutId, http)));
    }

    @PostMapping("/payouts/{payoutId}/reject")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Reject a requested payout")
    public ResponseEntity<ApiResponse<PayoutResponse>> reject(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID payoutId,
            @Valid @RequestBody ReasonRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.reject(cooperativeId, payoutId, request.getReason(), http)));
    }

    @PostMapping("/payouts/{payoutId}/cancel")
    @PreAuthorize("hasAuthority('PAYOUT_WRITE')")
    @Operation(summary = "Cancel a payout that was not yet disbursed")
    public ResponseEntity<ApiResponse<PayoutResponse>> cancel(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID payoutId,
            @Valid @RequestBody ReasonRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.cancel(cooperativeId, payoutId, request.getReason(), http)));
    }

    @PostMapping("/payouts/{payoutId}/confirm-disbursement")
    @PreAuthorize("hasAuthority('PAYOUT_WRITE')")
    @Operation(
            summary = "Confirm that an approved payout was ACTUALLY disbursed (method and reference required)",
            description = "Writes the ledger debit. Only this confirmation completes a payout financially.")
    public ResponseEntity<ApiResponse<PayoutResponse>> confirm(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID payoutId,
            @Valid @RequestBody ConfirmDisbursement request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.confirmDisbursement(cooperativeId, payoutId, request, http)));
    }
}
