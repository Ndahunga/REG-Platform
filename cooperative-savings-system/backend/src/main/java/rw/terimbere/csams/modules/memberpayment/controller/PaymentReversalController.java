package rw.terimbere.csams.modules.memberpayment.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentReversalDtos.ReasonRequest;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentReversalDtos.ReversalResponse;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentReversalDtos.WithdrawRequest;
import rw.terimbere.csams.modules.memberpayment.service.PaymentReversalService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/**
 * Contribution-only payment reversal: a request by one officer and an independent approval by another, which executes
 * the compensating accounting. Loan-inclusive payments are refused. There is no status-only reversal anywhere.
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/payments")
@RequiredArgsConstructor
@Tag(name = "Payment reversals")
@SecurityRequirement(name = "bearerAuth")
public class PaymentReversalController {

    private final PaymentReversalService reversalService;

    @PostMapping("/{paymentId}/reversals")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(
            summary = "Request the reversal of a posted payment (contributions, loan repayments and member credit)",
            description = "Needs a reason. Moves no money: a different officer must approve the request.")
    public ResponseEntity<ApiResponse<ReversalResponse>> request(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody ReasonRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(reversalService.request(cooperativeId, paymentId, request.getReason(), http)));
    }

    @GetMapping("/{paymentId}/reversals")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','FUND_AUTHORIZE')")
    @Operation(summary = "Reversal requests of one payment (officers)")
    public ResponseEntity<ApiResponse<List<ReversalResponse>>> forPayment(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(reversalService.forPayment(cooperativeId, paymentId)));
    }

    @GetMapping("/reversals")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','FUND_AUTHORIZE')")
    @Operation(summary = "All reversal requests of the scheme, newest first (officers)")
    public ResponseEntity<ApiResponse<List<ReversalResponse>>> list(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(reversalService.list(cooperativeId)));
    }

    @PostMapping("/reversals/{reversalId}/approve")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(
            summary = "Approve a reversal request (independent fund authority, never the requester) and execute the compensating accounting",
            description = "Atomic and idempotent. Refused when a later change or a rotation payout makes it inconsistent.")
    public ResponseEntity<ApiResponse<ReversalResponse>> approve(
            @PathVariable UUID cooperativeId, @PathVariable UUID reversalId, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(reversalService.approve(cooperativeId, reversalId, null, http)));
    }

    @PostMapping("/reversals/{reversalId}/withdraw")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(
            summary = "Withdraw your own pending reversal request",
            description = "Only the requester, only while pending. Moves no money; recorded as an immutable event.")
    public ResponseEntity<ApiResponse<ReversalResponse>> withdraw(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID reversalId,
            @Valid @RequestBody(required = false) WithdrawRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(
                reversalService.withdraw(cooperativeId, reversalId, request == null ? null : request.getNote(), http)));
    }

    @PostMapping("/reversals/{reversalId}/decline")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Decline a reversal request (independent fund authority, with a reason)")
    public ResponseEntity<ApiResponse<ReversalResponse>> decline(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID reversalId,
            @Valid @RequestBody ReasonRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(reversalService.decline(cooperativeId, reversalId, request.getReason(), http)));
    }
}
