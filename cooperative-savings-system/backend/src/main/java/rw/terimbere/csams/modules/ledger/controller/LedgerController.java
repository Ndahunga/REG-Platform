package rw.terimbere.csams.modules.ledger.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.ledger.dto.LedgerEntryResponse;
import rw.terimbere.csams.modules.ledger.service.LedgerQueryService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "Read-only financial ledger entries")
@SecurityRequirement(name = "bearerAuth")
public class LedgerController {

    private final LedgerQueryService ledgerQueryService;
    private final rw.terimbere.csams.modules.ledger.service.LedgerSplitReconciliationService splitReconciliationService;

    @GetMapping("/split-reconciliation")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    @Operation(summary = "Reconcile the rotating and retained parts of the contribution cash in the ledger")
    public ResponseEntity<ApiResponse<rw.terimbere.csams.modules.ledger.service.LedgerSplitReconciliationService.Reconciliation>>
            splitReconciliation(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(splitReconciliationService.reconcile(cooperativeId)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    @Operation(summary = "List ledger entries with filters")
    public ResponseEntity<ApiResponse<PageResponse<LedgerEntryResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) LedgerTransactionType transactionType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID memberUserId,
            @RequestParam(required = false) String sourceEntityType,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(ledgerQueryService.list(
                cooperativeId, transactionType, from, to, memberUserId, sourceEntityType, pageable)));
    }

    @GetMapping("/{entryId}")
    @PreAuthorize("hasAuthority('LEDGER_READ')")
    @Operation(summary = "Get ledger entry by id")
    public ResponseEntity<ApiResponse<LedgerEntryResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID entryId) {
        return ResponseEntity.ok(ApiResponse.ok(ledgerQueryService.get(cooperativeId, entryId)));
    }
}
