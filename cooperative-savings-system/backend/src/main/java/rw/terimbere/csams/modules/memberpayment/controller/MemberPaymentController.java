package rw.terimbere.csams.modules.memberpayment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.memberpayment.dto.LoanBreakdownRequest;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentEventResponse;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentResponse;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentSubmitRequest;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentSummaryResponse;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentPostingResponse;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentRejectRequest;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentVerifyRequest;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.service.MemberPaymentService;
import rw.terimbere.csams.modules.memberpayment.service.PaymentPostingService;
import jakarta.servlet.http.HttpServletRequest;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Member payment claims: submission, review and verification.
 *
 * <p><b>This API never moves money.</b> The furthest state reachable here is VERIFIED, meaning an authorized
 * person verified the claim; contribution balances, loans, shares and the ledger are untouched, and there is
 * a posting route that applies a verified payment atomically; reversal is refused. Each route's {@code @PreAuthorize} is only a first gate: the service
 * re-checks scheme membership, payer/submitter independence and the write authority of every allocation type.
 *
 * <p>Lists use a fixed, documented order (newest first; the review queue is oldest first), a default page size of
 * 20 and a maximum of 100. A client {@code sort} parameter is ignored.
 */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/payments")
@RequiredArgsConstructor
@Tag(name = "Member payments", description = "Payment claims, review and verification (no financial posting)")
@SecurityRequirement(name = "bearerAuth")
public class MemberPaymentController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final MemberPaymentService paymentService;
    private final PaymentPostingService postingService;

    @PostMapping
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(
            summary = "Submit a payment claim for the authenticated member",
            description = "Recorded for the caller only. Optional Idempotency-Key header: repeating the same request"
                    + " returns the original payment. Does not change any balance.")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> submit(
            @PathVariable UUID cooperativeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MemberPaymentSubmitRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                paymentService.create(cooperativeId, request.toCommand(cleanIdempotencyKey(idempotencyKey)))));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "The caller's own payments, newest first")
    public ResponseEntity<ApiResponse<PageResponse<MemberPaymentSummaryResponse>>> mine(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + MemberPaymentService.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.listMine(cooperativeId, status, page, size)));
    }

    @GetMapping("/review-queue")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(summary = "Payments awaiting review (SUBMITTED and UNDER_REVIEW), oldest first")
    public ResponseEntity<ApiResponse<PageResponse<MemberPaymentSummaryResponse>>> reviewQueue(
            @PathVariable UUID cooperativeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + MemberPaymentService.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.reviewQueue(cooperativeId, page, size)));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(
            summary = "All payments of the scheme (staff), newest first",
            description = "Use status=VERIFIED for payments that are verified and awaiting posting.")
    public ResponseEntity<ApiResponse<PageResponse<MemberPaymentSummaryResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) UUID memberUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + MemberPaymentService.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.listForStaff(cooperativeId, status, memberUserId, page, size)));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Payment details (the payer, or staff)")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.get(cooperativeId, paymentId)));
    }

    @GetMapping("/{paymentId}/allocations")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Allocations of a payment (the payer, or staff)")
    public ResponseEntity<ApiResponse<List<MemberPaymentResponse.AllocationResponse>>> allocations(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.get(cooperativeId, paymentId).allocations()));
    }

    @GetMapping("/{paymentId}/events")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Status history of a payment, oldest first (the payer, or staff)")
    public ResponseEntity<ApiResponse<List<MemberPaymentEventResponse>>> events(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.listEvents(cooperativeId, paymentId)));
    }

    @PostMapping("/{paymentId}/start-review")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(summary = "Begin reviewing a payment (SUBMITTED to UNDER_REVIEW)")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> startReview(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.startReview(cooperativeId, paymentId)));
    }

    @PutMapping("/{paymentId}/allocations/{allocationId}/loan-breakdown")
    @PreAuthorize("hasAuthority('LOAN_WRITE')")
    @Operation(
            summary = "Record the principal/interest/penalty split of a loan-repayment allocation",
            description = "Does not repay the loan. Only while the payment is SUBMITTED or UNDER_REVIEW.")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> recordLoanBreakdown(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID paymentId,
            @PathVariable UUID allocationId,
            @Valid @RequestBody LoanBreakdownRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.recordLoanBreakdown(
                cooperativeId,
                paymentId,
                allocationId,
                request.getPrincipalAmount(),
                request.getInterestAmount(),
                request.getPenaltyAmount())));
    }

    @PostMapping("/{paymentId}/verify")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(
            summary = "Verify a payment claim (UNDER_REVIEW to VERIFIED) with confirmation evidence",
            description = "Records how receipt of the funds was confirmed. VERIFIED does not post anything: "
                    + "balances do not change. Repeating identical evidence is a no-op.")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> verify(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentVerifyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.verify(cooperativeId, paymentId, request)));
    }

    @PostMapping("/{paymentId}/verification-evidence")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(
            summary = "Complete the confirmation evidence of a payment verified before evidence was required",
            description = "Audited. Until it is done such a payment cannot be posted. Existing evidence is never replaced.")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> completeEvidence(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentVerifyRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.completeVerificationEvidence(cooperativeId, paymentId, request)));
    }

    @PostMapping("/{paymentId}/post")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(
            summary = "Post a verified payment to the books (VERIFIED to POSTED), atomically",
            description = "Every allocation is applied or none is. The poster must differ from the verifier, the "
                    + "evidence recorder, the payer and the submitter. Posting a POSTED payment is a no-op.")
    public ResponseEntity<ApiResponse<PaymentPostingResponse>> post(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(postingService.post(cooperativeId, paymentId, http)));
    }

    @PostMapping("/{paymentId}/retry-posting")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(summary = "Retry a posting that failed earlier (the payment is still VERIFIED)")
    public ResponseEntity<ApiResponse<PaymentPostingResponse>> retryPosting(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(postingService.retry(cooperativeId, paymentId, http)));
    }

    @GetMapping("/{paymentId}/posting")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(summary = "Posting status and the immutable per-allocation posting results (staff)")
    public ResponseEntity<ApiResponse<PaymentPostingResponse>> posting(
            @PathVariable UUID cooperativeId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(postingService.getPosting(cooperativeId, paymentId)));
    }

    public record WithdrawRequest(String reason) {}

    @PostMapping("/{paymentId}/withdraw")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The payer withdraws a payment nobody has started to review (it becomes rejected by the payer)")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> withdraw(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID paymentId,
            @RequestBody(required = false) WithdrawRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.withdraw(cooperativeId, paymentId, request == null ? null : request.reason())));
    }

    @PostMapping("/{paymentId}/reject")
    @PreAuthorize("hasAnyAuthority('CONTRIBUTION_WRITE','LOAN_WRITE')")
    @Operation(summary = "Reject a payment (from SUBMITTED, UNDER_REVIEW or VERIFIED)")
    public ResponseEntity<ApiResponse<MemberPaymentResponse>> reject(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentRejectRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.reject(cooperativeId, paymentId, request.getReason())));
    }

    private static String cleanIdempotencyKey(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String key = header.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ValidationException(
                    "Invalid Idempotency-Key",
                    Map.of("Idempotency-Key", "must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters"));
        }
        return key;
    }
}
