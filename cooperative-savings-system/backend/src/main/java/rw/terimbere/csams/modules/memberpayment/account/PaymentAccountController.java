package rw.terimbere.csams.modules.memberpayment.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.memberpayment.account.PaymentAccountService.AccountRequest;
import rw.terimbere.csams.modules.memberpayment.account.PaymentAccountService.AccountResponse;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/payments/accounts")
@RequiredArgsConstructor
@Tag(name = "Payment accounts")
@SecurityRequirement(name = "bearerAuth")
public class PaymentAccountController {

    private final PaymentAccountService service;

    @GetMapping
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "The accounts the scheme asks members to pay into (masked for members)")
    public ResponseEntity<ApiResponse<List<AccountResponse>>> list(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(cooperativeId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('SETTINGS_MANAGE', 'COOPERATIVE_WRITE')")
    @Operation(summary = "Register a payment account of the scheme")
    public ResponseEntity<ApiResponse<AccountResponse>> add(
            @PathVariable UUID cooperativeId, @RequestBody AccountRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.add(cooperativeId, request)));
    }

    @PostMapping("/{accountId}/deactivate")
    @PreAuthorize("hasAnyAuthority('SETTINGS_MANAGE', 'COOPERATIVE_WRITE')")
    @Operation(summary = "Stop using a payment account (kept for history)")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID cooperativeId, @PathVariable UUID accountId) {
        service.deactivate(cooperativeId, accountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/{accountId}")
    @PreAuthorize("hasAnyAuthority('SETTINGS_MANAGE', 'COOPERATIVE_WRITE')")
    @Operation(summary = "Permanently delete a payment account that was never used (409 PAYMENT_ACCOUNT_IN_USE otherwise)")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID cooperativeId, @PathVariable UUID accountId) {
        service.delete(cooperativeId, accountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
