package rw.terimbere.csams.modules.loan.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import rw.terimbere.csams.modules.loan.dto.LoanApplicationFormResponse;
import rw.terimbere.csams.modules.loan.dto.LoanApproveRequest;
import rw.terimbere.csams.modules.loan.dto.LoanEligibilityResponse;
import rw.terimbere.csams.modules.loan.dto.LoanGuarantorRespondRequest;
import rw.terimbere.csams.modules.loan.dto.LoanGuarantorResponse;
import rw.terimbere.csams.modules.loan.dto.LoanRejectRequest;
import rw.terimbere.csams.modules.loan.dto.LoanRepaymentCreateRequest;
import rw.terimbere.csams.modules.loan.dto.LoanRepaymentPreviewRequest;
import rw.terimbere.csams.modules.loan.dto.LoanRepaymentResponse;
import rw.terimbere.csams.modules.loan.dto.LoanRequestCreateRequest;
import rw.terimbere.csams.modules.loan.dto.LoanResponse;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleResponse;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleWhatsAppShareRequest;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.service.LoanGuarantorService;
import rw.terimbere.csams.modules.loan.service.LoanScheduleWhatsAppShareService;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareResponse;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppStatusResponse;
import rw.terimbere.csams.modules.loan.service.LoanService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/loans")
@RequiredArgsConstructor
@Tag(name = "Loans", description = "Loan requests, approvals, disbursement, and repayments")
@SecurityRequirement(name = "bearerAuth")
public class LoanController {

    private final LoanService loanService;
    private final LoanGuarantorService loanGuarantorService;
    private final LoanScheduleWhatsAppShareService loanScheduleWhatsAppShareService;

    @PostMapping
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Request a loan (self) or admin-issue for a member")
    public ResponseEntity<ApiResponse<LoanResponse>> create(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody LoanRequestCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.requestLoan(cooperativeId, request, httpRequest)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "List loans with optional filters")
    public ResponseEntity<ApiResponse<PageResponse<LoanResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) LoanStatus status,
            @RequestParam(required = false) UUID memberUserId,
            @RequestParam(required = false, defaultValue = "false") boolean pendingApproval,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.ok(loanService.list(cooperativeId, status, memberUserId, pendingApproval, pageable)));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "List current user's loans")
    public ResponseEntity<ApiResponse<PageResponse<LoanResponse>>> myLoans(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) LoanStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.myLoans(cooperativeId, status, pageable)));
    }

    @GetMapping("/application-preview")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Populate the loan application form from the current member profile")
    public ResponseEntity<ApiResponse<LoanApplicationFormResponse>> applicationPreview(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.applicationPreview(cooperativeId)));
    }

    @PostMapping("/repayment-preview")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Preview flat monthly interest, total repayment, and equal installments")
    public ResponseEntity<ApiResponse<LoanScheduleResponse>> repaymentPreview(
            @PathVariable UUID cooperativeId, @Valid @RequestBody LoanRepaymentPreviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.previewRepayment(cooperativeId, request)));
    }

    @GetMapping("/eligibility")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Check loan eligibility including outstanding loan balance")
    public ResponseEntity<ApiResponse<LoanEligibilityResponse>> eligibility(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) UUID memberUserId,
            @RequestParam(required = false) java.math.BigDecimal amount) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.eligibility(cooperativeId, memberUserId, amount)));
    }

    @GetMapping("/guarantor-requests")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "List guarantor requests for the current member")
    public ResponseEntity<ApiResponse<List<LoanGuarantorResponse>>> myGuarantorRequests(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(loanGuarantorService.myRequests(cooperativeId)));
    }

    @GetMapping("/{loanId}")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Get loan by id")
    public ResponseEntity<ApiResponse<LoanResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID loanId) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.get(cooperativeId, loanId)));
    }

    @PostMapping("/{loanId}/guarantor/respond")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Accept or reject a guarantor request for this loan")
    public ResponseEntity<ApiResponse<LoanGuarantorResponse>> respondToGuarantorRequest(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID loanId,
            @Valid @RequestBody LoanGuarantorRespondRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(loanGuarantorService.respond(cooperativeId, loanId, request, httpRequest)));
    }

    @PostMapping("/{loanId}/approve")
    @PreAuthorize("hasAuthority('LOAN_APPROVE') or hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Record the next required loan approval (Loan Officer then President/Vice President)")
    public ResponseEntity<ApiResponse<LoanResponse>> approve(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID loanId,
            @Valid @RequestBody(required = false) LoanApproveRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(loanService.approve(cooperativeId, loanId, request, httpRequest)));
    }

    @PostMapping("/{loanId}/reject")
    @PreAuthorize("hasAuthority('LOAN_APPROVE') or hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Reject a loan awaiting first or second approval")
    public ResponseEntity<ApiResponse<LoanResponse>> reject(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID loanId,
            @Valid @RequestBody LoanRejectRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(loanService.reject(cooperativeId, loanId, request, httpRequest)));
    }

    @PostMapping("/{loanId}/disburse")
    @PreAuthorize("hasAuthority('LOAN_WRITE')")
    @Operation(summary = "Disburse an approved loan (posts ledger debit)")
    public ResponseEntity<ApiResponse<LoanResponse>> disburse(
            @PathVariable UUID cooperativeId, @PathVariable UUID loanId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.disburse(cooperativeId, loanId, httpRequest)));
    }

    @PostMapping("/{loanId}/write-off")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE') or hasRole('SUPER_ADMIN')")
    @Operation(summary = "Write off an active/overdue loan")
    public ResponseEntity<ApiResponse<LoanResponse>> writeOff(
            @PathVariable UUID cooperativeId, @PathVariable UUID loanId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.writeOff(cooperativeId, loanId, httpRequest)));
    }

    @PostMapping("/{loanId}/repayments")
    @PreAuthorize("hasAuthority('LOAN_WRITE')")
    @Operation(summary = "Record a loan repayment")
    public ResponseEntity<ApiResponse<LoanRepaymentResponse>> repay(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID loanId,
            @Valid @RequestBody LoanRepaymentCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(loanService.recordRepayment(cooperativeId, loanId, request, httpRequest)));
    }

    @GetMapping("/{loanId}/repayments")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "List repayments for a loan")
    public ResponseEntity<ApiResponse<List<LoanRepaymentResponse>>> listRepayments(
            @PathVariable UUID cooperativeId, @PathVariable UUID loanId) {
        return ResponseEntity.ok(ApiResponse.ok(loanService.listRepayments(cooperativeId, loanId)));
    }

    @GetMapping("/{loanId}/schedule/export")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Download the amortization schedule as PDF or Excel")
    public void exportSchedule(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID loanId,
            @RequestParam(defaultValue = "pdf") String format,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse)
            throws IOException {
        LoanService.ScheduleExport export = loanService.exportSchedule(cooperativeId, loanId, format, httpRequest);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(export.filename(), StandardCharsets.UTF_8)
                .build();
        httpResponse.setStatus(HttpServletResponse.SC_OK);
        httpResponse.setContentType(export.contentType());
        httpResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        httpResponse.setContentLength(export.content().length);
        httpResponse.getOutputStream().write(export.content());
        httpResponse.flushBuffer();
    }

    @GetMapping("/{loanId}/schedule/whatsapp-status")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Whether official WhatsApp schedule sharing is configured (no secrets)")
    public ResponseEntity<ApiResponse<ReportWhatsAppStatusResponse>> scheduleWhatsAppStatus(
            @PathVariable UUID cooperativeId, @PathVariable UUID loanId) {
        return ResponseEntity.ok(
                ApiResponse.ok(loanScheduleWhatsAppShareService.status(cooperativeId, loanId)));
    }

    @PostMapping("/{loanId}/schedule/share-whatsapp")
    @PreAuthorize("hasAuthority('LOAN_READ')")
    @Operation(summary = "Generate the existing repayment schedule PDF and send it via WhatsApp Cloud API")
    public ResponseEntity<ApiResponse<ReportWhatsAppShareResponse>> shareScheduleWhatsApp(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID loanId,
            @Valid @RequestBody LoanScheduleWhatsAppShareRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                loanScheduleWhatsAppShareService.share(cooperativeId, loanId, request, httpRequest)));
    }
}
