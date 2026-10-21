package rw.terimbere.csams.modules.incomeexpense.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.incomeexpense.dto.IncomeExpenseCreateRequest;
import rw.terimbere.csams.modules.incomeexpense.dto.IncomeExpenseRejectRequest;
import rw.terimbere.csams.modules.incomeexpense.dto.IncomeExpenseResponse;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseApprovalStatus;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.service.IncomeExpenseService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/transactions")
@RequiredArgsConstructor
@Tag(name = "Income & Expenses", description = "Other income, general/interest expenses, and adjustments")
@SecurityRequirement(name = "bearerAuth")
public class IncomeExpenseController {

    private final IncomeExpenseService incomeExpenseService;

    @GetMapping
    @PreAuthorize("hasAuthority('INCOME_EXPENSE_READ')")
    @Operation(summary = "List income/expense transactions")
    public ResponseEntity<ApiResponse<PageResponse<IncomeExpenseResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) IncomeExpenseCategory category,
            @RequestParam(required = false) IncomeExpenseApprovalStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                incomeExpenseService.list(cooperativeId, category, status, from, to, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('INCOME_EXPENSE_WRITE')")
    @Operation(summary = "Create a PENDING income/expense transaction")
    public ResponseEntity<ApiResponse<IncomeExpenseResponse>> create(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody IncomeExpenseCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(incomeExpenseService.create(cooperativeId, request, httpRequest)));
    }

    @GetMapping("/{transactionId}")
    @PreAuthorize("hasAuthority('INCOME_EXPENSE_READ')")
    @Operation(summary = "Get transaction by id")
    public ResponseEntity<ApiResponse<IncomeExpenseResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID transactionId) {
        return ResponseEntity.ok(ApiResponse.ok(incomeExpenseService.get(cooperativeId, transactionId)));
    }

    @PostMapping("/{transactionId}/approve")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Approve and post to ledger")
    public ResponseEntity<ApiResponse<IncomeExpenseResponse>> approve(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID transactionId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                incomeExpenseService.approve(cooperativeId, transactionId, httpRequest)));
    }

    @PostMapping("/{transactionId}/reject")
    @PreAuthorize("hasAuthority('INCOME_EXPENSE_WRITE')")
    @Operation(summary = "Reject a pending transaction")
    public ResponseEntity<ApiResponse<IncomeExpenseResponse>> reject(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID transactionId,
            @Valid @RequestBody IncomeExpenseRejectRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                incomeExpenseService.reject(cooperativeId, transactionId, request, httpRequest)));
    }
}
