package rw.terimbere.csams.modules.memberpayment.receipt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/**
 * The member's look at a receipt before submitting a payment. It reads the uploaded file and reports what it appears to
 * say; it changes no balance and verifies nothing.
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/payments/receipt-analysis")
@RequiredArgsConstructor
@Tag(name = "Receipt analysis")
@SecurityRequirement(name = "bearerAuth")
public class ReceiptAnalysisController {

    private final ReceiptAnalysisService service;

    public record AnalysisRequest(
            @NotBlank @Size(max = 512) String fileKey,
            @DecimalMin("0.01") BigDecimal enteredAmount) {}

    @PostMapping
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(
            summary = "Read an uploaded receipt before the payment is submitted",
            description = "Returns the amount, reference and date the receipt appears to show, what it claims happened,"
                    + " and what to do next. Advisory only: it never verifies, confirms or posts a payment.")
    public ResponseEntity<ApiResponse<ReceiptAnalysisResponse>> analyze(
            @PathVariable UUID cooperativeId, @Valid @RequestBody AnalysisRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(service.analyze(cooperativeId, request.fileKey(), request.enteredAmount())));
    }
}
