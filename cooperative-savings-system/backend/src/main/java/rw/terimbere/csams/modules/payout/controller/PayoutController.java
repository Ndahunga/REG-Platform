package rw.terimbere.csams.modules.payout.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.payout.dto.PayoutLineResponse;
import rw.terimbere.csams.modules.payout.dto.PayoutMarkPaidRequest;
import rw.terimbere.csams.modules.payout.dto.PayoutPreviewRequest;
import rw.terimbere.csams.modules.payout.dto.PayoutRunResponse;
import rw.terimbere.csams.modules.payout.dto.PayoutStatementResponse;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;
import rw.terimbere.csams.modules.payout.service.PayoutService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/payouts")
@RequiredArgsConstructor
@Tag(name = "Payouts", description = "Group payout preview, confirmation, and member history")
@SecurityRequirement(name = "bearerAuth")
public class PayoutController {

    private final PayoutService payoutService;

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('PAYOUT_WRITE')")
    @Operation(summary = "Preview a payout run (computes and freezes line snapshots as PREVIEWED)")
    public ResponseEntity<ApiResponse<PayoutRunResponse>> preview(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody PayoutPreviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.preview(cooperativeId, request, httpRequest)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYOUT_READ')")
    @Operation(summary = "List payout runs")
    public ResponseEntity<ApiResponse<PageResponse<PayoutRunResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) PayoutRunStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.list(cooperativeId, status, pageable)));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('PAYOUT_READ')")
    @Operation(summary = "Current user's payout lines across runs")
    public ResponseEntity<ApiResponse<List<PayoutLineResponse>>> my(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.myLines(cooperativeId)));
    }

    @GetMapping("/{runId}")
    @PreAuthorize("hasAuthority('PAYOUT_READ')")
    @Operation(summary = "Get payout run with lines (members without WRITE see only their own line)")
    public ResponseEntity<ApiResponse<PayoutRunResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID runId) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.get(cooperativeId, runId)));
    }

    @PostMapping("/{runId}/confirm")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Confirm PREVIEWED run: post MEMBER_PAYOUT ledger debits and freeze amounts")
    public ResponseEntity<ApiResponse<PayoutRunResponse>> confirm(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID runId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.confirm(cooperativeId, runId, httpRequest)));
    }

    @PostMapping("/{runId}/mark-paid")
    @PreAuthorize("hasAuthority('PAYOUT_WRITE')")
    @Operation(summary = "Mark confirmed lines or whole run as PAID")
    public ResponseEntity<ApiResponse<PayoutRunResponse>> markPaid(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID runId,
            @RequestBody(required = false) PayoutMarkPaidRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(payoutService.markPaid(cooperativeId, runId, request, httpRequest)));
    }

    @PostMapping("/{runId}/cancel")
    @PreAuthorize("hasAuthority('PAYOUT_WRITE')")
    @Operation(summary = "Cancel a DRAFT or PREVIEWED payout run")
    public ResponseEntity<ApiResponse<PayoutRunResponse>> cancel(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID runId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.cancel(cooperativeId, runId, httpRequest)));
    }

    @GetMapping("/{runId}/statement")
    @PreAuthorize("hasAuthority('PAYOUT_READ')")
    @Operation(summary = "JSON payout statement (prepared for Excel download later)")
    public ResponseEntity<ApiResponse<PayoutStatementResponse>> statement(
            @PathVariable UUID cooperativeId, @PathVariable UUID runId) {
        return ResponseEntity.ok(ApiResponse.ok(payoutService.statement(cooperativeId, runId)));
    }
}
