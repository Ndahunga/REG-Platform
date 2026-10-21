package rw.terimbere.csams.modules.investment.controller;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.investment.dto.InvestmentCreateRequest;
import rw.terimbere.csams.modules.investment.dto.InvestmentLossRequest;
import rw.terimbere.csams.modules.investment.dto.InvestmentResponse;
import rw.terimbere.csams.modules.investment.dto.InvestmentReturnCreateRequest;
import rw.terimbere.csams.modules.investment.dto.InvestmentReturnResponse;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
import rw.terimbere.csams.modules.investment.service.InvestmentService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/investments")
@RequiredArgsConstructor
@Tag(name = "Investments", description = "Group fund investments, activation, returns, and losses")
@SecurityRequirement(name = "bearerAuth")
public class InvestmentController {

    private final InvestmentService investmentService;

    @GetMapping
    @PreAuthorize("hasAuthority('INVESTMENT_READ')")
    @Operation(summary = "List investments")
    public ResponseEntity<ApiResponse<PageResponse<InvestmentResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) InvestmentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(investmentService.list(cooperativeId, status, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('INVESTMENT_WRITE')")
    @Operation(summary = "Create a PLANNED investment")
    public ResponseEntity<ApiResponse<InvestmentResponse>> create(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody InvestmentCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(investmentService.create(cooperativeId, request, httpRequest)));
    }

    @GetMapping("/{investmentId}")
    @PreAuthorize("hasAuthority('INVESTMENT_READ')")
    @Operation(summary = "Get investment by id")
    public ResponseEntity<ApiResponse<InvestmentResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID investmentId) {
        return ResponseEntity.ok(ApiResponse.ok(investmentService.get(cooperativeId, investmentId)));
    }

    @PostMapping("/{investmentId}/activate")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Activate a PLANNED investment (posts INVESTMENT_OUTFLOW debit)")
    public ResponseEntity<ApiResponse<InvestmentResponse>> activate(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID investmentId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(investmentService.activate(cooperativeId, investmentId, httpRequest)));
    }

    @PostMapping("/{investmentId}/cancel")
    @PreAuthorize("hasAuthority('INVESTMENT_WRITE')")
    @Operation(summary = "Cancel a PLANNED investment")
    public ResponseEntity<ApiResponse<InvestmentResponse>> cancel(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID investmentId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(investmentService.cancel(cooperativeId, investmentId, httpRequest)));
    }

    @PostMapping("/{investmentId}/returns")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Record capital and/or profit return")
    public ResponseEntity<ApiResponse<InvestmentReturnResponse>> recordReturn(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID investmentId,
            @Valid @RequestBody InvestmentReturnCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                investmentService.recordReturn(cooperativeId, investmentId, request, httpRequest)));
    }

    @GetMapping("/{investmentId}/returns")
    @PreAuthorize("hasAuthority('INVESTMENT_READ')")
    @Operation(summary = "List returns for an investment")
    public ResponseEntity<ApiResponse<List<InvestmentReturnResponse>>> listReturns(
            @PathVariable UUID cooperativeId, @PathVariable UUID investmentId) {
        return ResponseEntity.ok(ApiResponse.ok(investmentService.listReturns(cooperativeId, investmentId)));
    }

    @PostMapping("/{investmentId}/record-loss")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Write off remaining capital as LOSS_RECORDED (no capital return credit)")
    public ResponseEntity<ApiResponse<InvestmentResponse>> recordLoss(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID investmentId,
            @RequestBody(required = false) InvestmentLossRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                investmentService.recordLoss(cooperativeId, investmentId, request, httpRequest)));
    }
}
