package rw.terimbere.csams.modules.memberpayment.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.filemanagement.entity.StoredFile;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.membercredit.CreditPurpose;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentCreateCommand;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentEventResponse;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentSummaryResponse;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentResponse;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentAllocation;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentEvent;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentVerifyRequest;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptAnalysisService;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentAllocationRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentEventRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.pagination.PageMapper;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Payment aggregate: creation, allocation validation and the verified-before-posted state machine.
 *
 * <p><b>What this is not:</b> a payment here is a claim, not a financial transaction. This service never writes
 * the ledger, balances, contributions or loan repayments. {@link #post} only records that a verified payment was
 * authorized and marked posted exactly once; the atomic posting orchestration belongs to a later task.
 *
 * <p><b>Safety properties</b>
 * <ul>
 *   <li>Every status change locks the payment row ({@code PESSIMISTIC_WRITE}) and goes through
 *       {@link PaymentStatus#requireTransitionTo}, so concurrent or repeated requests cannot double-apply.</li>
 *   <li>Lookups are always scoped by Saving Scheme; another scheme's payment is "not found".</li>
 *   <li>Verification is maker-checker: neither the payer nor the submitter may verify; no one reviews a payment
 *       that is theirs. Reviewers need the same write authority the existing flows require for each allocation.</li>
 *   <li>Receipt-analysis fields are never read here.</li>
 *   <li>Duplicate references and idempotency keys are enforced by unique constraints; a lost race is resolved
 *       after the failed transaction, never by swallowing the error inside it.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MemberPaymentService {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final int MAX_AMOUNT_SCALE = 4;
    private static final int MAX_REASON_LENGTH = 2000;
    public static final String DUPLICATE_REFERENCE_CODE = "DUPLICATE_PAYMENT_REFERENCE";
    public static final String IDEMPOTENCY_KEY_REUSED_CODE = "IDEMPOTENCY_KEY_REUSED";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String LOAN_WRITE = "LOAN_WRITE";

    private final MemberPaymentRepository paymentRepository;
    private final MemberPaymentAllocationRepository allocationRepository;
    private final MemberPaymentEventRepository eventRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final LoanRepository loanRepository;
    private final ContributionRepository contributionRepository;
    private final FileManagementService fileManagementService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final UserRepository userRepository;
    private final rw.terimbere.csams.modules.memberpayment.receipt.ReceiptAnalysisService receiptAnalysisService;
    private final rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService
            verificationCheckService;
    private final rw.terimbere.csams.modules.memberpayment.verification.VerificationAuditService verificationAudit;

    // ------------------------------------------------------------------ create

    /**
     * Records a payment claim (status SUBMITTED). Idempotent per (scheme, submitter, idempotency key).
     * Not annotated {@code @Transactional}: the write runs in its own transaction so a unique-constraint race can
     * be resolved afterwards in a fresh one.
     */
    public MemberPaymentResponse create(UUID cooperativeId, MemberPaymentCreateCommand command) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (command == null) {
            throw new BusinessException("INVALID_PAYMENT", "Payment details are required");
        }
        MemberPaymentResponse response;
        try {
            // the receipt rule runs first and outside the transaction: reading an image can take seconds
            ReceiptAnalysisService.ReceiptGate gate = receiptAnalysisService.gate(
                    cooperativeId,
                    principal,
                    command.evidenceFileKey(),
                    command.amount(),
                    command.transactionReference(),
                    Boolean.TRUE.equals(command.manualReview()));
            response = transactionTemplate.execute(status -> doCreate(cooperativeId, command, principal, gate));
        } catch (DataIntegrityViolationException ex) {
            response = transactionTemplate.execute(status -> resolveCreateRace(cooperativeId, command, principal, ex));
        }
        return viewFor(principal, response);
    }

    private MemberPaymentResponse doCreate(
            UUID cooperativeId,
            MemberPaymentCreateCommand command,
            UserPrincipal principal,
            ReceiptAnalysisService.ReceiptGate gate) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        UUID memberId = command.memberUserId() == null ? principal.getId() : command.memberUserId();

        // 1. Shape and arithmetic (no database access).
        BigDecimal amount = requirePositiveAmount(command.amount(), "Payment amount");
        LocalDate paymentDate = requirePaymentDate(command.paymentDate());
        if (command.paymentMethod() == null) {
            throw new BusinessException("INVALID_PAYMENT", "Payment method is required");
        }
        String normalizedReference = PaymentReferenceNormalizer.normalize(command.transactionReference());
        if (command.paymentMethod().requiresReference() && normalizedReference == null) {
            throw new BusinessException(
                    "PAYMENT_REFERENCE_REQUIRED",
                    "A transaction reference is required for " + command.paymentMethod());
        }
        List<DraftAllocation> drafts = draftAllocations(command.allocations());
        BigDecimal allocated = drafts.stream().map(DraftAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(amount) != 0) {
            throw new BusinessException(
                    "ALLOCATION_TOTAL_MISMATCH",
                    "Allocations total " + allocated.toPlainString() + " but the payment is " + amount.toPlainString());
        }

        // 2. Who may record this payment, for whom.
        CooperativeMembership payer = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        if (!"ACTIVE".equals(payer.getMembershipStatus())) {
            throw new BusinessException("PAYER_NOT_ACTIVE_MEMBER", "The paying member is not an active member");
        }
        if (!memberId.equals(principal.getId())) {
            requireStaffFor(principal, allocationTypes(drafts));
        }

        // 3. Idempotent replay: same key + same claim returns the original; same key + different claim is refused.
        String idempotencyKey = trimToNull(command.idempotencyKey());
        if (idempotencyKey != null) {
            MemberPayment existing = paymentRepository
                    .findByCooperativeIdAndSubmittedByAndIdempotencyKey(cooperativeId, principal.getId(), idempotencyKey)
                    .orElse(null);
            if (existing != null) {
                return replay(existing, memberId, amount, paymentDate, command, normalizedReference);
            }
        }

        // 4. Relationships: every allocation must point at this member's own records in this scheme.
        validateRelationships(cooperativeId, memberId, drafts);
        validateWithinOutstanding(cooperativeId, memberId, drafts);
        String evidenceKey = validateEvidence(cooperativeId, principal, command.evidenceFileKey());

        // 5. Duplicate reference (the unique constraint is the real guard; this gives a precise early error).
        String referenceKey = PaymentReferenceNormalizer.key(command.paymentMethod(), normalizedReference);
        if (referenceKey != null) {
            paymentRepository.findByCooperativeIdAndReferenceKey(cooperativeId, referenceKey).ifPresent(existing -> {
                // Deliberately no detail about the other payment (it may belong to another member).
                throw new ConflictException(
                        DUPLICATE_REFERENCE_CODE,
                        "This transaction reference is already used by a payment in this Saving Scheme");
            });
        }

        MemberPayment payment = paymentRepository.saveAndFlush(MemberPayment.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .amount(amount)
                .allocatedAmount(allocated)
                .currency(cooperative.getCurrency())
                .paymentDate(paymentDate)
                .paymentMethod(command.paymentMethod())
                .transactionReference(trimToNull(command.transactionReference()))
                .normalizedReference(normalizedReference)
                .referenceKey(referenceKey)
                .evidenceFileKey(evidenceKey)
                .receiptAnalysisId(gate.analysisId())
                .receiptStatus(gate.receiptStatus())
                .receiptConfidence(gate.confidence())
                .extractedAmount(gate.extractedAmount())
                .extractedReference(gate.extractedReference())
                .receiptAnalyzedAt(gate.analyzedAt())
                .receiptReviewRequested(gate.reviewRequested())
                .receiptRecipientMatch(gate.recipientMatch() == null ? null : gate.recipientMatch().name())
                .receiptAmountAmbiguous(gate.amountAmbiguous())
                .idempotencyKey(idempotencyKey)
                .notes(trimToNull(command.notes()))
                .status(PaymentStatus.SUBMITTED)
                .submittedBy(principal.getId())
                .submittedAt(java.time.Instant.now())
                .build());
        List<MemberPaymentAllocation> saved = allocationRepository.saveAllAndFlush(drafts.stream()
                .map(d -> d.toEntity(payment))
                .toList());

        recordEvent(payment, principal, null, PaymentStatus.SUBMITTED, null);
        audit(principal, cooperativeId, AuditableAction.PAYMENT_SUBMIT, payment, null, PaymentStatus.SUBMITTED);
        return MemberPaymentResponse.from(payment, saved);
    }

    private MemberPaymentResponse resolveCreateRace(
            UUID cooperativeId,
            MemberPaymentCreateCommand command,
            UserPrincipal principal,
            DataIntegrityViolationException original) {
        UUID memberId = command.memberUserId() == null ? principal.getId() : command.memberUserId();
        String idempotencyKey = trimToNull(command.idempotencyKey());
        if (idempotencyKey != null) {
            MemberPayment existing = paymentRepository
                    .findByCooperativeIdAndSubmittedByAndIdempotencyKey(cooperativeId, principal.getId(), idempotencyKey)
                    .orElse(null);
            if (existing != null) {
                return replay(
                        existing,
                        memberId,
                        requirePositiveAmount(command.amount(), "Payment amount"),
                        command.paymentDate(),
                        command,
                        PaymentReferenceNormalizer.normalize(command.transactionReference()));
            }
        }
        String normalized = PaymentReferenceNormalizer.normalize(command.transactionReference());
        String referenceKey = PaymentReferenceNormalizer.key(command.paymentMethod(), normalized);
        if (referenceKey != null
                && paymentRepository.findByCooperativeIdAndReferenceKey(cooperativeId, referenceKey).isPresent()) {
            throw new ConflictException(
                    DUPLICATE_REFERENCE_CODE,
                    "This transaction reference is already used by a payment in this Saving Scheme");
        }
        throw original;
    }

    private MemberPaymentResponse replay(
            MemberPayment existing,
            UUID memberId,
            BigDecimal amount,
            LocalDate paymentDate,
            MemberPaymentCreateCommand command,
            String normalizedReference) {
        boolean sameClaim = existing.getMemberUserId().equals(memberId)
                && existing.getAmount().compareTo(amount) == 0
                && Objects.equals(existing.getPaymentDate(), paymentDate)
                && existing.getPaymentMethod() == command.paymentMethod()
                && Objects.equals(existing.getNormalizedReference(), normalizedReference);
        if (!sameClaim) {
            throw new ConflictException(
                    IDEMPOTENCY_KEY_REUSED_CODE, "This idempotency key was already used for a different payment");
        }
        return toResponse(existing);
    }

    // --------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public MemberPaymentResponse get(UUID cooperativeId, UUID paymentId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        boolean own = payment.getMemberUserId().equals(principal.getId());
        if (!own && !isAnyStaff(principal)) {
            // Same answer as a missing payment: members cannot probe each other's payments.
            throw new ResourceNotFoundException("Payment", paymentId);
        }
        return viewFor(principal, toResponse(payment));
    }

    /**
     * The caller's own payments, newest first (submittedAt desc, id desc). Always scoped to the scheme and to the
     * caller; the payer filter is never taken from the request.
     */
    @Transactional(readOnly = true)
    public PageResponse<MemberPaymentSummaryResponse> listMine(
            UUID cooperativeId, PaymentStatus status, int page, int size) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        Specification<MemberPayment> spec = scoped(cooperativeId)
                .and((root, q, cb) -> cb.equal(root.get("memberUserId"), principal.getId()))
                .and(statusIn(status == null ? EnumSet.allOf(PaymentStatus.class) : EnumSet.of(status)));
        return summaries(paymentRepository.findAll(spec, pageRequest(page, size, false)), false);
    }

    /**
     * Staff: all payments of the scheme, newest first, optionally narrowed by status and payer. VERIFIED payments
     * (verified, awaiting posting) are reached with {@code status=VERIFIED}.
     */
    @Transactional(readOnly = true)
    public PageResponse<MemberPaymentSummaryResponse> listForStaff(
            UUID cooperativeId, PaymentStatus status, UUID memberUserId, int page, int size) {
        requireStaffViewer(cooperativeId);
        Specification<MemberPayment> spec = scoped(cooperativeId)
                .and(statusIn(status == null ? EnumSet.allOf(PaymentStatus.class) : EnumSet.of(status)));
        if (memberUserId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("memberUserId"), memberUserId));
        }
        return summaries(paymentRepository.findAll(spec, pageRequest(page, size, false)), true);
    }

    /** Staff: the active review queue (SUBMITTED and UNDER_REVIEW), oldest first so nothing starves. */
    @Transactional(readOnly = true)
    public PageResponse<MemberPaymentSummaryResponse> reviewQueue(UUID cooperativeId, int page, int size) {
        requireStaffViewer(cooperativeId);
        Specification<MemberPayment> spec = scoped(cooperativeId)
                .and(statusIn(EnumSet.of(PaymentStatus.SUBMITTED, PaymentStatus.UNDER_REVIEW)));
        return summaries(paymentRepository.findAll(spec, pageRequest(page, size, true)), true);
    }

    /** Status history of one payment, oldest first. Same visibility as {@link #get}. */
    @Transactional(readOnly = true)
    public List<MemberPaymentEventResponse> listEvents(UUID cooperativeId, UUID paymentId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        boolean staff = isAnyStaff(principal);
        if (!staff && !payment.getMemberUserId().equals(principal.getId())) {
            throw new ResourceNotFoundException("Payment", paymentId);
        }
        return eventRepository
                .findByCooperativeIdAndPaymentIdOrderByCreatedAtAsc(cooperativeId, paymentId)
                .stream()
                .map(e -> MemberPaymentEventResponse.from(e, staff))
                .toList();
    }

    // ------------------------------------------------------------ transitions

    @Transactional
    public MemberPaymentResponse startReview(UUID cooperativeId, UUID paymentId) {
        Locked locked = lockForReview(cooperativeId, paymentId);
        MemberPayment payment = locked.payment();
        PaymentStatus previous = payment.getStatus();
        previous.requireTransitionTo(PaymentStatus.UNDER_REVIEW);
        payment.setStatus(PaymentStatus.UNDER_REVIEW);
        payment.setReviewStartedBy(locked.principal().getId());
        payment.setReviewStartedAt(java.time.Instant.now());
        return finish(locked, previous, AuditableAction.PAYMENT_REVIEW_START, null);
    }

    /**
     * Records the principal/interest/penalty split of a loan-repayment allocation while the payment is still
     * being reviewed. The three parts must add up to the allocation amount.
     */
    @Transactional
    public MemberPaymentResponse recordLoanBreakdown(
            UUID cooperativeId,
            UUID paymentId,
            UUID allocationId,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal penalty) {
        Locked locked = lockForReview(cooperativeId, paymentId);
        MemberPayment payment = locked.payment();
        if (payment.getStatus() != PaymentStatus.SUBMITTED && payment.getStatus() != PaymentStatus.UNDER_REVIEW) {
            throw new BusinessException(
                    "PAYMENT_NOT_EDITABLE", "The loan breakdown can only be changed while the payment is under review");
        }
        MemberPaymentAllocation allocation = allocationRepository
                .findByIdAndPaymentIdAndCooperativeId(allocationId, paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation", allocationId));
        if (allocation.getAllocationType() != PaymentAllocationType.LOAN_REPAYMENT) {
            throw new BusinessException(
                    "INVALID_BREAKDOWN", "Only loan-repayment allocations have a principal/interest/penalty breakdown");
        }
        requireStaffFor(locked.principal(), Set.of(PaymentAllocationType.LOAN_REPAYMENT));
        applyBreakdown(allocation, principal, interest, penalty);
        allocationRepository.saveAndFlush(allocation);
        audit(locked.principal(), cooperativeId, AuditableAction.PAYMENT_BREAKDOWN_RECORD, payment, payment.getStatus(),
                payment.getStatus());
        locked.allocations().replaceAll(a -> a.getId().equals(allocationId) ? allocation : a);
        return MemberPaymentResponse.from(payment, locked.allocations());
    }

    /**
     * UNDER_REVIEW -> VERIFIED. Re-validates everything and records HOW the receipt of funds was confirmed (method,
     * written basis, external reference). OCR or an uploaded image alone is never a confirmation. Verification is not
     * posting: nothing financial changes.
     *
     * <p>Repeating the request with identical evidence is a safe no-op; different evidence is refused so recorded
     * evidence is never silently overwritten.
     */
    @Transactional
    public MemberPaymentResponse verify(UUID cooperativeId, UUID paymentId, PaymentVerifyRequest evidence) {
        Locked locked = lockForReview(cooperativeId, paymentId);
        MemberPayment payment = locked.payment();
        UserPrincipal actor = locked.principal();
        if (actor.getId().equals(payment.getSubmittedBy())) {
            verificationAudit.blocked(actor.getId(), cooperativeId, paymentId, "VERIFY", "SUBMITTER_CANNOT_VERIFY");
            throw new ForbiddenException("The person who submitted a payment cannot verify it");
        }
        VerificationEvidence checked = checkEvidence(payment, evidence);

        PaymentStatus previous = payment.getStatus();
        if (previous == PaymentStatus.VERIFIED || previous == PaymentStatus.POSTED) {
            if (!payment.hasVerificationEvidence()) {
                throw new ConflictException(
                        "EVIDENCE_COMPLETION_REQUIRED",
                        "This payment was verified without confirmation evidence; use the evidence-completion operation");
            }
            if (checked.matches(payment)) {
                return MemberPaymentResponse.from(payment, locked.allocations());
            }
            throw new ConflictException(
                    "VERIFICATION_EVIDENCE_EXISTS",
                    "This payment already has recorded verification evidence, which cannot be overwritten");
        }
        previous.requireTransitionTo(PaymentStatus.VERIFIED);
        // conflicting or failed trusted evidence blocks verification; when configured, authoritative confirmation is
        // required; and the transaction that confirms this payment is consumed so no other payment can use it
        verificationCheckService.enforceBeforeVerification(payment, actor, checked.method());

        BigDecimal allocated = locked.allocations().stream()
                .map(MemberPaymentAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(payment.getAmount()) != 0) {
            throw new BusinessException(
                    "ALLOCATION_TOTAL_MISMATCH", "Allocations do not account for the full payment amount");
        }
        for (MemberPaymentAllocation allocation : locked.allocations()) {
            // The loan engine is authoritative for the split; a staff-entered breakdown is optional, but when present it
            // must at least add up (it is reconciled against the engine's result at posting).
            if (allocation.getAllocationType() == PaymentAllocationType.LOAN_REPAYMENT && allocation.hasBreakdown()) {
                assertBreakdownReconciles(allocation);
            }
        }
        validateRelationships(
                cooperativeId,
                payment.getMemberUserId(),
                locked.allocations().stream().map(DraftAllocation::of).toList());

        payment.setAllocatedAmount(allocated);
        payment.setStatus(PaymentStatus.VERIFIED);
        payment.setVerifiedBy(actor.getId());
        payment.setVerifiedAt(java.time.Instant.now());
        payment.setVerificationMethod(checked.method());
        payment.setVerificationBasis(checked.basis());
        payment.setVerificationReference(checked.reference());
        return finish(locked, previous, AuditableAction.PAYMENT_VERIFY, null);
    }

    /**
     * Completes the confirmation evidence of a payment that was marked VERIFIED before evidence was required. Until
     * this is done such a payment is held and cannot be posted. Audited and recorded as an event; evidence that already
     * exists is never replaced.
     */
    @Transactional
    public MemberPaymentResponse completeVerificationEvidence(
            UUID cooperativeId, UUID paymentId, PaymentVerifyRequest evidence) {
        Locked locked = lockForReview(cooperativeId, paymentId);
        MemberPayment payment = locked.payment();
        UserPrincipal actor = locked.principal();
        if (actor.getId().equals(payment.getSubmittedBy())) {
            throw new ForbiddenException("The person who submitted a payment cannot confirm its evidence");
        }
        if (payment.getStatus() != PaymentStatus.VERIFIED) {
            throw new BusinessException(
                    "PAYMENT_NOT_VERIFIED", "Evidence can only be completed for a VERIFIED payment that has none");
        }
        VerificationEvidence checked = checkEvidence(payment, evidence);
        if (payment.hasVerificationEvidence()) {
            if (checked.matches(payment)) {
                return MemberPaymentResponse.from(payment, locked.allocations());
            }
            throw new ConflictException(
                    "VERIFICATION_EVIDENCE_EXISTS",
                    "This payment already has recorded verification evidence, which cannot be overwritten");
        }
        PaymentStatus status = payment.getStatus();
        payment.setVerificationMethod(checked.method());
        payment.setVerificationBasis(checked.basis());
        payment.setVerificationReference(checked.reference());
        payment.setEvidenceCompletedBy(actor.getId());
        payment.setEvidenceCompletedAt(java.time.Instant.now());
        return finish(locked, status, AuditableAction.PAYMENT_EVIDENCE_COMPLETE, "EVIDENCE_COMPLETED");
    }

    private record VerificationEvidence(VerificationMethod method, String basis, String reference) {
        boolean matches(MemberPayment payment) {
            return method == payment.getVerificationMethod()
                    && Objects.equals(reference, payment.getVerificationReference())
                    && basis.equals(payment.getVerificationBasis());
        }
    }

    private VerificationEvidence checkEvidence(MemberPayment payment, PaymentVerifyRequest evidence) {
        if (evidence == null || evidence.getMethod() == null) {
            throw new BusinessException("VERIFICATION_EVIDENCE_REQUIRED", "A verification method is required");
        }
        String basis = trimToNull(evidence.getBasis());
        if (basis == null) {
            throw new BusinessException(
                    "VERIFICATION_EVIDENCE_REQUIRED", "State what confirmation of the received funds you relied on");
        }
        String reference = trimToNull(evidence.getReference());
        if (evidence.getMethod().requiresReference() && reference == null) {
            throw new BusinessException(
                    "VERIFICATION_REFERENCE_REQUIRED",
                    "A transaction or reconciliation reference is required for " + evidence.getMethod());
        }
        if (!evidence.getMethod().fits(payment.getPaymentMethod())) {
            throw new BusinessException(
                    "VERIFICATION_METHOD_MISMATCH",
                    evidence.getMethod() + " cannot confirm a " + payment.getPaymentMethod() + " payment");
        }
        return new VerificationEvidence(evidence.getMethod(), basis, reference);
    }

    /** SUBMITTED / UNDER_REVIEW -> REJECTED. Releases the duplicate-reference claim. Never after verification. */
    @Transactional
    public MemberPaymentResponse reject(UUID cooperativeId, UUID paymentId, String reason) {
        String cleanReason = requireReason(reason, "A rejection reason is required");
        Locked locked = lockForReview(cooperativeId, paymentId);
        MemberPayment payment = locked.payment();
        PaymentStatus previous = payment.getStatus();
        if (previous == PaymentStatus.VERIFIED || previous == PaymentStatus.POSTED || previous == PaymentStatus.REVERSED) {
            // Funds were confirmed: the evidence, allocations, events and audit trail stay as they are. A verified payment
            // that cannot be posted remains VERIFIED and visible; nothing is refunded or turned into member credit.
            throw new BusinessException(
                    "VERIFIED_PAYMENT_CANNOT_BE_REJECTED",
                    "A payment whose funds were confirmed cannot be rejected; it stays verified until it can be posted"
                            + " or is resolved through a separately approved process");
        }
        previous.requireTransitionTo(PaymentStatus.REJECTED);
        payment.setStatus(PaymentStatus.REJECTED);
        payment.setRejectedBy(locked.principal().getId());
        payment.setRejectedAt(java.time.Instant.now());
        payment.setRejectionReason(cleanReason);
        payment.setReferenceKey(null);
        return finish(locked, previous, AuditableAction.PAYMENT_REJECT, cleanReason);
    }

    /**
     * The payer takes back a payment nobody has started to review. It becomes REJECTED (recorded as withdrawn by the
     * payer, in the history and the audit trail) and releases its reference so a corrected payment can be submitted. A
     * payment under review, verified or posted is never withdrawn: an officer rejects it or, once posted, reverses it.
     */
    @Transactional
    public MemberPaymentResponse withdraw(UUID cooperativeId, UUID paymentId, String reason) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeIdForUpdate(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        if (!principal.getId().equals(payment.getMemberUserId())) {
            // the same answer as for a payment that does not exist: nobody learns about other members' payments
            throw new ResourceNotFoundException("Payment", paymentId);
        }
        PaymentStatus previous = payment.getStatus();
        if (previous != PaymentStatus.SUBMITTED) {
            throw new BusinessException(
                    "PAYMENT_CANNOT_BE_WITHDRAWN",
                    "Only a payment that nobody has started to review can be withdrawn");
        }
        String clean = trimToNull(reason) == null ? "Withdrawn by the payer" : trimToNull(reason);
        List<MemberPaymentAllocation> allocations =
                new java.util.ArrayList<>(allocationRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId));
        previous.requireTransitionTo(PaymentStatus.REJECTED);
        payment.setStatus(PaymentStatus.REJECTED);
        payment.setRejectedBy(principal.getId());
        payment.setRejectedAt(java.time.Instant.now());
        payment.setRejectionReason(clean.length() > 500 ? clean.substring(0, 500) : clean);
        payment.setReferenceKey(null);
        return finish(new Locked(payment, allocations, principal), previous, AuditableAction.PAYMENT_WITHDRAW, "Withdrawn by the payer");
    }

    // ---------------------------------------------------------------- helpers

    private record Locked(MemberPayment payment, List<MemberPaymentAllocation> allocations, UserPrincipal principal) {}

    /**
     * Loads the payment of this scheme with a row lock and checks the caller may review it: member of the scheme,
     * holds the write authority of every allocation type, and is not the payer.
     */
    private Locked lockForReview(UUID cooperativeId, UUID paymentId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeIdForUpdate(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        List<MemberPaymentAllocation> allocations = new java.util.ArrayList<>(
                allocationRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId));
        requireStaffFor(principal, allocations.stream().map(MemberPaymentAllocation::getAllocationType).collect(
                java.util.stream.Collectors.toSet()));
        if (principal.getId().equals(payment.getMemberUserId())) {
            throw new ForbiddenException("You cannot review your own payment");
        }
        return new Locked(payment, allocations, principal);
    }

    private MemberPaymentResponse finish(Locked locked, PaymentStatus previous, AuditableAction action, String comment) {
        MemberPayment payment = paymentRepository.saveAndFlush(locked.payment());
        recordEvent(payment, locked.principal(), previous, payment.getStatus(), comment);
        audit(locked.principal(), payment.getCooperativeId(), action, payment, previous, payment.getStatus());
        return MemberPaymentResponse.from(payment, locked.allocations());
    }

    private void recordEvent(
            MemberPayment payment, UserPrincipal actor, PaymentStatus from, PaymentStatus to, String comment) {
        eventRepository.save(MemberPaymentEvent.builder()
                .paymentId(payment.getId())
                .cooperativeId(payment.getCooperativeId())
                .actorUserId(actor.getId())
                .actorRole(CooperativeOfficerRoles.displayRole(actor))
                .fromStatus(from)
                .toStatus(to)
                .comment(trimToNull(comment))
                .build());
    }

    private void audit(
            UserPrincipal actor,
            UUID cooperativeId,
            AuditableAction action,
            MemberPayment payment,
            PaymentStatus previous,
            PaymentStatus next) {
        // Only enum/uuid/number values go into the JSON, never free text.
        auditService.record(
                actor.getId(),
                cooperativeId,
                action,
                "MemberPayment",
                payment.getId(),
                previous == null ? null : "{\"status\":\"" + previous + "\"}",
                "{\"status\":\"" + next + "\",\"amount\":\"" + payment.getAmount().toPlainString() + "\"}",
                null,
                null);
    }

    private void requireStaffViewer(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        if (!isAnyStaff(authorizationService.currentPrincipal())) {
            throw new ForbiddenException("Payment review access is required");
        }
    }

    /** Staff see everything; the payer gets the redacted member view. */
    private static MemberPaymentResponse viewFor(UserPrincipal viewer, MemberPaymentResponse response) {
        return isAnyStaff(viewer) ? response : response.forMember(viewer.getId());
    }

    private static Specification<MemberPayment> scoped(UUID cooperativeId) {
        return (root, query, cb) -> cb.equal(root.get("cooperativeId"), cooperativeId);
    }

    private static Specification<MemberPayment> statusIn(Collection<PaymentStatus> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /**
     * Fixed, deterministic ordering (the id breaks ties); a client-supplied sort is never accepted.
     * Out-of-range paging is rejected with a 400 rather than silently changed.
     */
    private static PageRequest pageRequest(int page, int size, boolean oldestFirst) {
        if (page < 0) {
            throw new ValidationException("Invalid paging", Map.of("page", "must be 0 or greater"));
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException(
                    "Invalid paging", Map.of("size", "must be between 1 and " + MAX_PAGE_SIZE));
        }
        Sort.Direction direction = oldestFirst ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(page, size, Sort.by(direction, "submittedAt").and(Sort.by(direction, "id")));
    }

    private PageResponse<MemberPaymentSummaryResponse> summaries(Page<MemberPayment> page, boolean staffView) {
        List<UUID> ids = page.getContent().stream().map(MemberPayment::getId).toList();
        Map<UUID, List<PaymentAllocationType>> typesByPayment = ids.isEmpty()
                ? Map.of()
                : allocationRepository.findByPaymentIdIn(ids).stream()
                        .collect(Collectors.groupingBy(
                                MemberPaymentAllocation::getPaymentId,
                                Collectors.mapping(MemberPaymentAllocation::getAllocationType, Collectors.toList())));
        Map<UUID, String> names = Map.of();
        if (staffView && !page.isEmpty()) {
            names = userRepository
                    .findAllById(page.getContent().stream().map(MemberPayment::getMemberUserId).distinct().toList())
                    .stream()
                    .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));
        }
        Map<UUID, String> memberNames = names;
        return PageMapper.toPageResponse(
                page,
                p -> MemberPaymentSummaryResponse.from(
                        p,
                        typesByPayment.getOrDefault(p.getId(), List.of()).stream().distinct().sorted().toList(),
                        staffView ? memberNames.get(p.getMemberUserId()) : null));
    }

    private MemberPaymentResponse toResponse(MemberPayment payment) {
        return MemberPaymentResponse.from(
                payment, allocationRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId()));
    }

    /** An allocation before it is persisted (and also the shape used to re-validate persisted ones). */
    private record DraftAllocation(
            PaymentAllocationType type,
            BigDecimal amount,
            Integer periodYear,
            Integer periodMonth,
            UUID loanId,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal penalty,
            CreditPurpose creditPurpose) {

        static DraftAllocation of(MemberPaymentAllocation a) {
            return new DraftAllocation(
                    a.getAllocationType(),
                    a.getAmount(),
                    a.getPeriodYear(),
                    a.getPeriodMonth(),
                    a.getLoanId(),
                    a.getPrincipalAmount(),
                    a.getInterestAmount(),
                    a.getPenaltyAmount(),
                    a.getCreditPurpose());
        }

        MemberPaymentAllocation toEntity(MemberPayment payment) {
            return MemberPaymentAllocation.builder()
                    .paymentId(payment.getId())
                    .cooperativeId(payment.getCooperativeId())
                    .memberUserId(payment.getMemberUserId())
                    .allocationType(type)
                    .amount(amount)
                    .periodYear(periodYear)
                    .periodMonth(periodMonth)
                    .loanId(loanId)
                    .creditPurpose(creditPurpose)
                    .principalAmount(principal)
                    .interestAmount(interest)
                    .penaltyAmount(penalty)
                    .build();
        }
    }

    private List<DraftAllocation> draftAllocations(List<MemberPaymentCreateCommand.Allocation> input) {
        if (input == null || input.isEmpty()) {
            throw new BusinessException("INVALID_ALLOCATION", "A payment needs at least one allocation");
        }
        Set<String> seen = new HashSet<>();
        List<DraftAllocation> drafts = new java.util.ArrayList<>();
        for (MemberPaymentCreateCommand.Allocation a : input) {
            if (a == null || a.type() == null) {
                throw new BusinessException("INVALID_ALLOCATION", "Every allocation needs a type");
            }
            BigDecimal amount = requirePositiveAmount(a.amount(), "Allocation amount");
            BigDecimal principal = null;
            BigDecimal interest = null;
            BigDecimal penalty = null;
            boolean anyPart = a.principal() != null || a.interest() != null || a.penalty() != null;
            switch (a.type()) {
                case CONTRIBUTION -> {
                    if (a.periodYear() == null || a.periodMonth() == null) {
                        throw new BusinessException("INVALID_ALLOCATION", "A contribution allocation needs a year and month");
                    }
                    if (a.periodYear() < 2000 || a.periodYear() > 2100 || a.periodMonth() < 1 || a.periodMonth() > 12) {
                        throw new BusinessException("INVALID_ALLOCATION", "Contribution period is out of range");
                    }
                    if (a.loanId() != null || anyPart) {
                        throw new BusinessException("INVALID_ALLOCATION", "A contribution allocation cannot reference a loan");
                    }
                    if (!seen.add("P:" + a.periodYear() + "-" + a.periodMonth())) {
                        throw new BusinessException("INVALID_ALLOCATION", "A contribution period can only appear once");
                    }
                }
                case LOAN_REPAYMENT -> {
                    if (a.loanId() == null) {
                        throw new BusinessException("INVALID_ALLOCATION", "A loan-repayment allocation needs a loan");
                    }
                    if (a.periodYear() != null || a.periodMonth() != null) {
                        throw new BusinessException("INVALID_ALLOCATION", "A loan-repayment allocation has no contribution period");
                    }
                    if (!seen.add("L:" + a.loanId())) {
                        throw new BusinessException("INVALID_ALLOCATION", "A loan can only appear once in a payment");
                    }
                    if (anyPart) {
                        if (a.principal() == null || a.interest() == null || a.penalty() == null) {
                            throw new BusinessException(
                                    "INVALID_BREAKDOWN", "Principal, interest and penalty must all be given or all omitted");
                        }
                        principal = requireNonNegativeAmount(a.principal(), "Principal");
                        interest = requireNonNegativeAmount(a.interest(), "Interest");
                        penalty = requireNonNegativeAmount(a.penalty(), "Penalty");
                        if (principal.add(interest).add(penalty).compareTo(amount) != 0) {
                            throw new BusinessException(
                                    "INVALID_BREAKDOWN", "Principal + interest + penalty must equal the allocation amount");
                        }
                    }
                }
                case MEMBER_CREDIT -> {
                    // excess money is classified explicitly: never income, never silently a contribution or a repayment
                    if (a.creditPurpose() == null) {
                        throw new BusinessException(
                                "INVALID_ALLOCATION",
                                "A member-credit allocation needs a purpose: advance contribution, loan prepayment or unapplied credit");
                    }
                    if (a.periodYear() != null || a.periodMonth() != null || anyPart) {
                        throw new BusinessException("INVALID_ALLOCATION", "A member-credit allocation has no period or breakdown");
                    }
                    if (a.creditPurpose() == CreditPurpose.LOAN_PREPAYMENT) {
                        if (a.loanId() == null) {
                            throw new BusinessException("INVALID_ALLOCATION", "A loan prepayment needs its loan");
                        }
                    } else if (a.loanId() != null) {
                        throw new BusinessException("INVALID_ALLOCATION", "Only a loan prepayment names a loan");
                    }
                    if (!seen.add("C:" + a.creditPurpose() + ":" + a.loanId())) {
                        throw new BusinessException("INVALID_ALLOCATION", "Each kind of credit can only appear once in a payment");
                    }
                    if (a.creditPurpose() == CreditPurpose.LOAN_PREPAYMENT && !seen.add("L:" + a.loanId())) {
                        throw new BusinessException("INVALID_ALLOCATION", "A loan can only appear once in a payment");
                    }
                }
            }
            drafts.add(new DraftAllocation(
                    a.type(), amount, a.periodYear(), a.periodMonth(), a.loanId(), principal, interest, penalty,
                    a.creditPurpose()));
        }
        return drafts;
    }

    private void applyBreakdown(
            MemberPaymentAllocation allocation, BigDecimal principal, BigDecimal interest, BigDecimal penalty) {
        BigDecimal p = requireNonNegativeAmount(principal, "Principal");
        BigDecimal i = requireNonNegativeAmount(interest, "Interest");
        BigDecimal pen = requireNonNegativeAmount(penalty, "Penalty");
        if (p.add(i).add(pen).compareTo(allocation.getAmount()) != 0) {
            throw new BusinessException(
                    "INVALID_BREAKDOWN", "Principal + interest + penalty must equal the allocation amount");
        }
        allocation.setPrincipalAmount(p);
        allocation.setInterestAmount(i);
        allocation.setPenaltyAmount(pen);
    }

    private void assertBreakdownReconciles(MemberPaymentAllocation allocation) {
        BigDecimal sum = allocation.getPrincipalAmount()
                .add(allocation.getInterestAmount())
                .add(allocation.getPenaltyAmount());
        if (sum.compareTo(allocation.getAmount()) != 0) {
            throw new BusinessException(
                    "INVALID_BREAKDOWN", "Principal + interest + penalty must equal the allocation amount");
        }
    }

    /** Every referenced loan/contribution period must be this member's own record in this scheme. */
    private void validateRelationships(UUID cooperativeId, UUID memberId, List<DraftAllocation> drafts) {
        for (DraftAllocation d : drafts) {
            if (d.type() == PaymentAllocationType.LOAN_REPAYMENT
                    || (d.type() == PaymentAllocationType.MEMBER_CREDIT && d.creditPurpose() == CreditPurpose.LOAN_PREPAYMENT)) {
                Loan loan = loanRepository
                        .findByIdAndCooperativeId(d.loanId(), cooperativeId)
                        .filter(l -> l.getMemberUserId().equals(memberId))
                        .orElseThrow(() -> new ResourceNotFoundException("Loan", d.loanId()));
                if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.OVERDUE) {
                    throw new BusinessException(
                            "LOAN_NOT_REPAYABLE", "Repayments are only possible on ACTIVE or OVERDUE loans");
                }
            } else if (d.type() == PaymentAllocationType.CONTRIBUTION) {
                contributionRepository
                        .findByCooperativeIdAndMemberUserIdAndYearAndMonth(
                                cooperativeId, memberId, d.periodYear(), d.periodMonth())
                        .filter(c -> c.getStatus() == ContributionStatus.WAIVED
                                || c.getStatus() == ContributionStatus.CANCELLED)
                        .ifPresent(c -> {
                            throw new BusinessException(
                                    "CONTRIBUTION_PERIOD_CLOSED",
                                    "That contribution period was waived or cancelled and cannot receive a payment");
                        });
            }
        }
    }

    /**
     * Unsupported excess is refused at submission: a contribution allocation cannot exceed what the period still
     * needs, nor a loan allocation what the loan still owes. Posting re-checks against the then-current balances.
     */
    private void validateWithinOutstanding(UUID cooperativeId, UUID memberId, List<DraftAllocation> drafts) {
        for (DraftAllocation d : drafts) {
            if (d.type() == PaymentAllocationType.LOAN_REPAYMENT) {
                Loan loan = loanRepository.findByIdAndCooperativeId(d.loanId(), cooperativeId).orElseThrow();
                BigDecimal owed = nz(loan.getOutstandingPrincipal())
                        .add(nz(loan.getOutstandingInterest()))
                        .add(nz(loan.getOutstandingPenalty()));
                if (d.amount().compareTo(owed) > 0) {
                    throw new BusinessException(
                            "ALLOCATION_EXCEEDS_OUTSTANDING",
                            "A loan allocation exceeds what the loan still owes (" + owed.toPlainString() + ")");
                }
            } else if (d.type() == PaymentAllocationType.CONTRIBUTION) {
                contributionRepository
                        .findByCooperativeIdAndMemberUserIdAndYearAndMonth(
                                cooperativeId, memberId, d.periodYear(), d.periodMonth())
                        .ifPresent(c -> {
                            BigDecimal remaining = nz(c.getExpectedAmount()).subtract(nz(c.getPaidAmount()));
                            if (d.amount().compareTo(remaining.max(BigDecimal.ZERO)) > 0) {
                                throw new BusinessException(
                                        "ALLOCATION_EXCEEDS_OUTSTANDING",
                                        "A contribution allocation exceeds what the period still needs ("
                                                + remaining.max(BigDecimal.ZERO).toPlainString() + ")");
                            }
                        });
            }
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** The evidence must be an existing file of this scheme uploaded by the person recording the payment. */
    private String validateEvidence(UUID cooperativeId, UserPrincipal principal, String evidenceFileKey) {
        String key = trimToNull(evidenceFileKey);
        if (key == null) {
            return null;
        }
        StoredFile file = fileManagementService.requireOwnedFile(key, cooperativeId);
        if (file.getUploadedBy() == null || !file.getUploadedBy().equals(principal.getId())) {
            throw new ForbiddenException("The evidence file was not uploaded by you");
        }
        return file.getStorageKey();
    }

    private static Set<PaymentAllocationType> allocationTypes(List<DraftAllocation> drafts) {
        Set<PaymentAllocationType> types = new HashSet<>();
        drafts.forEach(d -> types.add(d.type()));
        return types;
    }

    /** Reviewing/recording for someone else needs the write authority each allocation type already requires. */
    private static void requireStaffFor(UserPrincipal principal, Set<PaymentAllocationType> types) {
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return;
        }
        for (PaymentAllocationType type : types) {
            String needed = type == PaymentAllocationType.LOAN_REPAYMENT ? LOAN_WRITE : CONTRIBUTION_WRITE;
            if (!principal.hasAuthority(needed)) {
                throw new ForbiddenException("You do not have permission to handle " + type + " payments");
            }
        }
    }

    private static boolean isAnyStaff(UserPrincipal principal) {
        return principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || principal.hasAuthority(CONTRIBUTION_WRITE)
                || principal.hasAuthority(LOAN_WRITE);
    }

    private static BigDecimal requirePositiveAmount(BigDecimal value, String label) {
        BigDecimal amount = requireAmount(value, label);
        if (amount.signum() <= 0) {
            throw new BusinessException("INVALID_AMOUNT", label + " must be greater than zero");
        }
        return amount;
    }

    private static BigDecimal requireNonNegativeAmount(BigDecimal value, String label) {
        BigDecimal amount = requireAmount(value, label);
        if (amount.signum() < 0) {
            throw new BusinessException("INVALID_AMOUNT", label + " cannot be negative");
        }
        return amount;
    }

    /** Exact decimal with at most 4 decimal places; never silently rounds the caller's number. */
    private static BigDecimal requireAmount(BigDecimal value, String label) {
        if (value == null) {
            throw new BusinessException("INVALID_AMOUNT", label + " is required");
        }
        if (value.stripTrailingZeros().scale() > MAX_AMOUNT_SCALE) {
            throw new BusinessException("INVALID_AMOUNT", label + " has too many decimal places");
        }
        return value.setScale(MAX_AMOUNT_SCALE);
    }

    private static LocalDate requirePaymentDate(LocalDate date) {
        if (date == null) {
            throw new BusinessException("INVALID_PAYMENT", "Payment date is required");
        }
        if (date.isAfter(LocalDate.now(KIGALI))) {
            throw new BusinessException("PAYMENT_DATE_IN_FUTURE", "Payment date cannot be in the future");
        }
        return date;
    }

    private static String requireReason(String reason, String message) {
        String clean = trimToNull(reason);
        if (clean == null) {
            throw new BusinessException("REASON_REQUIRED", message);
        }
        if (clean.length() > MAX_REASON_LENGTH) {
            throw new BusinessException("REASON_TOO_LONG", "Reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return clean;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
