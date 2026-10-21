package rw.terimbere.csams.modules.memberpayment.verification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService.AttentionItem;
import rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService.CheckResponse;
import rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService.Overview;
import rw.terimbere.csams.modules.memberpayment.verification.TrustedTransactionService.ImportRequest;
import rw.terimbere.csams.modules.memberpayment.verification.TrustedTransactionService.ImportResponse;
import rw.terimbere.csams.modules.memberpayment.verification.TrustedTransactionService.TransactionResponse;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/**
 * Trusted verification for officers. A CONFIRMED outcome is evidence, not a status change: nothing here verifies or
 * posts a payment.
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/payments")
@RequiredArgsConstructor
@Tag(name = "Payment verification")
@SecurityRequirement(name = "bearerAuth")
public class PaymentVerificationController {

    private static final String STAFF = "hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')";

    private final PaymentVerificationCheckService checkService;
    private final TrustedTransactionService transactionService;
    private final rw.terimbere.csams.modules.memberpayment.receipt.ReceiptExtractionService receiptService;

    @GetMapping("/verification-capabilities")
    @PreAuthorize(STAFF)
    @Operation(summary = "What this deployment can read and confirm: OCR availability, PDF support, strictness, callbacks")
    public ResponseEntity<ApiResponse<Capabilities>> capabilities(@PathVariable UUID cooperativeId) {
        var ocr = receiptService.capabilities(cooperativeId);
        return ResponseEntity.ok(ApiResponse.ok(new Capabilities(
                ocr.ocrAvailable(),
                ocr.ocrEngine(),
                ocr.supportedReceiptTypes(),
                ocr.pdfSupported(),
                ocr.maxReceiptBytes(),
                ocr.ocrTimeoutSeconds(),
                checkService.strictVerification(),
                checkService.providerCallbackConfigured())));
    }

    @GetMapping("/{paymentId}/verification-checks")
    @PreAuthorize(STAFF)
    @Operation(summary = "Verification outcomes of a payment and whether automatic provider confirmation exists")
    public ResponseEntity<ApiResponse<Overview>> overview(@PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(checkService.overview(cooperativeId, paymentId)));
    }

    @PostMapping("/{paymentId}/verification-checks/recheck")
    @PreAuthorize(STAFF)
    @Operation(summary = "Match the payment against the trusted records now (records an outcome only)")
    public ResponseEntity<ApiResponse<CheckResponse>> recheck(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(checkService.recheck(cooperativeId, paymentId)));
    }

    @PostMapping("/{paymentId}/verification-checks/cash-confirmation")
    @PreAuthorize(STAFF)
    @Operation(summary = "Confirm that the cash of a cash payment was received (not the payer or submitter)")
    public ResponseEntity<ApiResponse<CheckResponse>> confirmCash(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody CashConfirmationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(checkService.confirmCash(cooperativeId, paymentId, request.getNote())));
    }

    @GetMapping("/reconciliation/attention")
    @PreAuthorize(STAFF)
    @Operation(summary = "Open payments whose evidence is conflicting, failed, unmatched or pending")
    public ResponseEntity<ApiResponse<List<AttentionItem>>> attention(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(checkService.attention(cooperativeId)));
    }

    @PostMapping("/trusted-transactions")
    @PreAuthorize(STAFF)
    @Operation(summary = "Import statement lines (idempotent). Recording never verifies or posts a payment")
    public ResponseEntity<ApiResponse<ImportResponse>> importTransactions(
            @PathVariable UUID cooperativeId, @RequestBody ImportRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.importBatch(cooperativeId, request)));
    }

    @GetMapping("/trusted-transactions")
    @PreAuthorize(STAFF)
    @Operation(summary = "Recently recorded trusted transactions of the scheme")
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> transactions(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.list(cooperativeId)));
    }

    /** Secrets are never returned: only whether any callback channel is configured. */
    public record Capabilities(
            boolean ocrAvailable,
            String ocrEngine,
            List<String> supportedReceiptTypes,
            boolean pdfSupported,
            long maxReceiptBytes,
            long ocrTimeoutSeconds,
            boolean strictVerification,
            boolean providerCallbackConfigured) {}

    @Data
    @NoArgsConstructor
    public static class CashConfirmationRequest {
        @NotBlank
        @Size(max = 1000)
        private String note;
    }
}
