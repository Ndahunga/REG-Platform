package rw.terimbere.csams.modules.membercredit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/**
 * A member's held credit: balances and history (a member sees their own, an officer anyone's), and the officer's
 * decision to apply credit to a contribution or a loan. Nothing here receives money: credit is created only by posting a
 * verified payment.
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/payments/credits")
@RequiredArgsConstructor
@Tag(name = "Member credit")
@SecurityRequirement(name = "bearerAuth")
public class MemberCreditController {

    private final MemberCreditService service;

    public record ApplyBody(
            @NotNull UUID memberUserId,
            @NotNull CreditPurpose purpose,
            UUID bucketLoanId,
            @NotNull PaymentAllocationType targetType,
            @Min(2000) @Max(2100) Integer periodYear,
            @Min(1) @Max(12) Integer periodMonth,
            UUID loanId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @Size(max = 500) String note) {}

    @GetMapping
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Held credit by member and purpose (a member sees only their own)")
    public ResponseEntity<ApiResponse<MemberCreditService.Overview>> overview(
            @PathVariable UUID cooperativeId, @RequestParam(required = false) UUID memberUserId) {
        return ResponseEntity.ok(ApiResponse.ok(service.overview(cooperativeId, memberUserId)));
    }

    @GetMapping("/entries")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "The journal of held credit (a member sees only their own)")
    public ResponseEntity<ApiResponse<List<MemberCreditService.EntryView>>> history(
            @PathVariable UUID cooperativeId, @RequestParam(required = false) UUID memberUserId) {
        return ResponseEntity.ok(ApiResponse.ok(service.history(cooperativeId, memberUserId)));
    }

    @PostMapping("/apply")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(
            summary = "Apply a member's held credit to a contribution or a loan",
            description = "An officer's decision. Requires an Idempotency-Key; repeating the same request changes nothing.")
    public ResponseEntity<ApiResponse<MemberCreditService.EntryView>> apply(
            @PathVariable UUID cooperativeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ApplyBody body) {
        return ResponseEntity.ok(ApiResponse.ok(service.apply(
                cooperativeId,
                new MemberCreditService.ApplyRequest(
                        body.memberUserId(), body.purpose(), body.bucketLoanId(), body.targetType(), body.periodYear(),
                        body.periodMonth(), body.loanId(), body.amount(), body.note()),
                idempotencyKey)));
    }
}
