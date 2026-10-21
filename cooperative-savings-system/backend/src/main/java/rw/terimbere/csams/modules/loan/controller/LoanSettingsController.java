package rw.terimbere.csams.modules.loan.controller;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.loan.dto.LoanSettingsResponse;
import rw.terimbere.csams.modules.loan.dto.LoanSettingsUpdateRequest;
import rw.terimbere.csams.modules.loan.service.LoanSettingsService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/loan-settings")
@RequiredArgsConstructor
@Tag(name = "Loan Settings", description = "Per-cooperative loan configuration")
@SecurityRequirement(name = "bearerAuth")
public class LoanSettingsController {

    private final LoanSettingsService loanSettingsService;

    @GetMapping
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Get loan settings (creates defaults if missing)")
    public ResponseEntity<ApiResponse<LoanSettingsResponse>> get(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(loanSettingsService.getOrCreate(cooperativeId)));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('LOAN_WRITE')")
    @Operation(summary = "Update loan settings")
    public ResponseEntity<ApiResponse<LoanSettingsResponse>> update(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody LoanSettingsUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(loanSettingsService.update(cooperativeId, request, httpRequest)));
    }
}
