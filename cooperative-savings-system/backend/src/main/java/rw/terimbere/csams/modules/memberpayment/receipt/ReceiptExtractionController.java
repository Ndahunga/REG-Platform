package rw.terimbere.csams.modules.memberpayment.receipt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptExtractionService.ExtractionResponse;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/**
 * Receipt reading for officers. Everything returned is advisory: {@code proofOfPayment} is always false, and no route
 * here verifies, confirms or posts a payment.
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/payments/{paymentId}/receipt")
@RequiredArgsConstructor
@Tag(name = "Payment receipts")
@SecurityRequirement(name = "bearerAuth")
public class ReceiptExtractionController {

    private final ReceiptExtractionService service;

    @PostMapping("/extract")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(
            summary = "Read the uploaded receipt of a payment (OCR)",
            description = "Advisory only. Idempotent per receipt content. Reports UNAVAILABLE when no OCR engine is installed.")
    public ResponseEntity<ApiResponse<ExtractionResponse>> extract(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(service.extract(cooperativeId, paymentId)));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(summary = "The extractions of a payment's receipt, with confidence and discrepancies (officers)")
    public ResponseEntity<ApiResponse<List<ExtractionResponse>>> list(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(service.forPayment(cooperativeId, paymentId)));
    }

    @PostMapping("/{extractionId}/review")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(summary = "Record that an officer reviewed an extraction (once, never overwritten)")
    public ResponseEntity<ApiResponse<ExtractionResponse>> review(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID paymentId,
            @PathVariable UUID extractionId,
            @Valid @RequestBody(required = false) ReviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.review(cooperativeId, extractionId, request == null ? null : request.getNote())));
    }

    @Data
    @NoArgsConstructor
    public static class ReviewRequest {
        @Size(max = 2000)
        private String note;
    }
}
