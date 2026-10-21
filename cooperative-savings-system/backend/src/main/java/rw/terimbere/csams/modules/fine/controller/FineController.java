package rw.terimbere.csams.modules.fine.controller;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.fine.dto.FineCreateRequest;
import rw.terimbere.csams.modules.fine.dto.FineGenerateRequest;
import rw.terimbere.csams.modules.fine.dto.FineGenerateResponse;
import rw.terimbere.csams.modules.fine.dto.FinePaymentCreateRequest;
import rw.terimbere.csams.modules.fine.dto.FinePaymentResponse;
import rw.terimbere.csams.modules.fine.dto.FinePaymentReviewRequest;
import rw.terimbere.csams.modules.fine.dto.FineResponse;
import rw.terimbere.csams.modules.fine.dto.FineUpdateRequest;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/fines")
@RequiredArgsConstructor
@Tag(name = "Fines", description = "Manual/automatic fines and fine payments")
@SecurityRequirement(name = "bearerAuth")
public class FineController {

    private final FineService fineService;

    @GetMapping
    @PreAuthorize("hasAuthority('FINE_READ')")
    @Operation(summary = "List fines with optional filters")
    public ResponseEntity<ApiResponse<PageResponse<FineResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) FineStatus status,
            @RequestParam(required = false) UUID memberUserId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.list(cooperativeId, status, memberUserId, pageable)));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('FINE_READ')")
    @Operation(summary = "List current user's fines")
    public ResponseEntity<ApiResponse<PageResponse<FineResponse>>> myFines(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) FineStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.myFines(cooperativeId, status, pageable)));
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('FINE_READ')")
    @Operation(summary = "List fine payments for the cooperative (payment queue)")
    public ResponseEntity<ApiResponse<PageResponse<FinePaymentResponse>>> listPaymentQueue(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) FinePaymentStatus status,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate toDate,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.listPaymentQueue(
                cooperativeId, status, fromDate, toDate, q, pageable)));
    }

    @GetMapping("/{fineId}")
    @PreAuthorize("hasAuthority('FINE_READ')")
    @Operation(summary = "Get fine by id")
    public ResponseEntity<ApiResponse<FineResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID fineId) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.get(cooperativeId, fineId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('FINE_WRITE')")
    @Operation(summary = "Issue a manual fine")
    public ResponseEntity<ApiResponse<FineResponse>> createManual(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody FineCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.createManual(cooperativeId, request, httpRequest)));
    }

    @PostMapping("/generate-automatic")
    @PreAuthorize("hasAuthority('FINE_WRITE')")
    @Operation(summary = "Generate automatic fines for overdue unpaid contributions")
    public ResponseEntity<ApiResponse<FineGenerateResponse>> generateAutomatic(
            @PathVariable UUID cooperativeId,
            @RequestBody(required = false) FineGenerateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(fineService.generateAutomatic(cooperativeId, request, httpRequest)));
    }

    @PostMapping("/{fineId}/waive")
    @PreAuthorize("hasAuthority('FINE_WRITE')")
    @Operation(summary = "Waive a fine")
    public ResponseEntity<ApiResponse<FineResponse>> waive(
            @PathVariable UUID cooperativeId, @PathVariable UUID fineId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.waive(cooperativeId, fineId, httpRequest)));
    }

    @PostMapping("/{fineId}/cancel")
    @PreAuthorize("hasAuthority('FINE_WRITE')")
    @Operation(summary = "Cancel a fine")
    public ResponseEntity<ApiResponse<FineResponse>> cancel(
            @PathVariable UUID cooperativeId, @PathVariable UUID fineId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.cancel(cooperativeId, fineId, httpRequest)));
    }

    @PatchMapping("/{fineId}")
    @PreAuthorize("hasRole('PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Edit an unpaid fine (President only)")
    public ResponseEntity<ApiResponse<FineResponse>> update(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID fineId,
            @Valid @RequestBody FineUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.updateFine(cooperativeId, fineId, request, httpRequest)));
    }

    @DeleteMapping("/{fineId}")
    @PreAuthorize("hasRole('PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Remove an unpaid fine (President only)")
    public ResponseEntity<ApiResponse<FineResponse>> delete(
            @PathVariable UUID cooperativeId, @PathVariable UUID fineId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.deleteFine(cooperativeId, fineId, httpRequest)));
    }

    @PostMapping("/{fineId}/payments")
    @PreAuthorize("hasAuthority('FINE_READ')")
    @Operation(summary = "Submit a fine payment (member own or admin)")
    public ResponseEntity<ApiResponse<FinePaymentResponse>> submitPayment(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID fineId,
            @Valid @RequestBody FinePaymentCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(fineService.submitPayment(cooperativeId, fineId, request, httpRequest)));
    }

    @GetMapping("/{fineId}/payments")
    @PreAuthorize("hasAuthority('FINE_READ')")
    @Operation(summary = "List payments for a fine")
    public ResponseEntity<ApiResponse<List<FinePaymentResponse>>> listPayments(
            @PathVariable UUID cooperativeId, @PathVariable UUID fineId) {
        return ResponseEntity.ok(ApiResponse.ok(fineService.listPayments(cooperativeId, fineId)));
    }

    @PostMapping("/{fineId}/payments/{paymentId}/approve")
    @PreAuthorize("hasAuthority('FINE_WRITE')")
    @Operation(summary = "Approve a pending fine payment (posts ledger credit)")
    public ResponseEntity<ApiResponse<FinePaymentResponse>> approvePayment(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID fineId,
            @PathVariable UUID paymentId,
            @RequestBody(required = false) FinePaymentReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                fineService.approvePayment(cooperativeId, fineId, paymentId, request, httpRequest)));
    }

    @PostMapping("/{fineId}/payments/{paymentId}/reject")
    @PreAuthorize("hasAuthority('FINE_WRITE')")
    @Operation(summary = "Reject a pending fine payment")
    public ResponseEntity<ApiResponse<FinePaymentResponse>> rejectPayment(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID fineId,
            @PathVariable UUID paymentId,
            @RequestBody(required = false) FinePaymentReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                fineService.rejectPayment(cooperativeId, fineId, paymentId, request, httpRequest)));
    }
}
