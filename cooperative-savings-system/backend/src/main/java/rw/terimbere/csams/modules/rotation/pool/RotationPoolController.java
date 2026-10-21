package rw.terimbere.csams.modules.rotation.pool;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import rw.terimbere.csams.modules.rotation.pool.RotationPoolService.PeriodPool;
import rw.terimbere.csams.modules.rotation.pool.RotationPoolService.SchemePool;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/** The rotating pool: its accounting, its entries, and each period's scheduled payout against it. */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/rotation/pool")
@Tag(name = "Rotation pool")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class RotationPoolController {

    private final RotationPoolService poolService;

    public record EntryRequest(
            @NotNull PoolEntryType type, @NotNull BigDecimal amount, LocalDate effectiveDate, String reason, String reference) {}

    public record EntryResponse(
            UUID id,
            PoolEntryType type,
            BigDecimal amount,
            LocalDate effectiveDate,
            String reason,
            String reference,
            UUID recordedBy,
            java.time.Instant createdAt) {
        static EntryResponse of(RotationPoolEntry e) {
            return new EntryResponse(
                    e.getId(), e.getEntryType(), e.getAmount(), e.getEffectiveDate(), e.getReason(), e.getReference(), e.getRecordedBy(), e.getCreatedAt());
        }
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Collected, available and unused pool of the scheme (officers)")
    public ResponseEntity<ApiResponse<SchemePool>> scheme(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(poolService.scheme(cooperativeId)));
    }

    @GetMapping("/entries")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The pool's opening, top-up and expense records (officers)")
    public ResponseEntity<ApiResponse<List<EntryResponse>>> entries(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(poolService.entries(cooperativeId).stream().map(EntryResponse::of).toList()));
    }

    @PostMapping("/entries")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Record the opening pool, a top-up or an expense (fund authority); an earmark, never a cash movement")
    public ResponseEntity<ApiResponse<EntryResponse>> record(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody EntryRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest http) {
        RotationPoolEntry saved = poolService.record(
                cooperativeId,
                new RotationPoolService.Command(
                        request.type(), request.amount(), request.effectiveDate(), request.reason(), request.reference()),
                idempotencyKey,
                http);
        return ResponseEntity.ok(ApiResponse.ok(EntryResponse.of(saved)));
    }

    @GetMapping("/periods/{periodId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "A period's scheduled payout, collected and available pool, and any shortfall (officers)")
    public ResponseEntity<ApiResponse<PeriodPool>> period(@PathVariable UUID cooperativeId, @PathVariable UUID periodId) {
        return ResponseEntity.ok(ApiResponse.ok(poolService.forPeriod(cooperativeId, periodId)));
    }
}
