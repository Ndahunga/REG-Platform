package rw.terimbere.csams.modules.memberpayment.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentReversalDtos.ReversalResponse;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanService;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.membercredit.CreditEntryType;
import rw.terimbere.csams.modules.membercredit.MemberCreditEntry;
import rw.terimbere.csams.modules.membercredit.MemberCreditEntryRepository;
import rw.terimbere.csams.modules.membercredit.MemberCreditService;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentLoanReversal;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentAllocation;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentEvent;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPosting;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPostingReversal;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentReversal;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentReversalStatus;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentAllocationRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentEventRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentLoanReversalRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentPostingRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentPostingReversalRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentReversalRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Controlled reversal of a POSTED payment: its contribution allocations, its loan repayments and the member credit it
 * created.
 *
 * <p>An officer requests it with a reason; a different authorized officer approves it, and approval executes the
 * compensating accounting atomically: each contribution's paid amount is reduced by exactly what the payment
 * contributed (the ledger follows through the existing REVERSAL entry), the rotating and retained split recorded at
 * posting is taken back in a new immutable row, and the payment becomes REVERSED. Nothing original is updated or
 * deleted. A loan repayment is given back to its loan through the loan engine (the loan owes again what the repayment
 * had taken, its instalments reopen, its ledger credits are taken back); credit the payment created is taken back only
 * while it is still unspent.
 *
 * <p>A reversal is refused, never forced, when it would leave the books inconsistent: when a later payment or a
 * correction changed the same period (the period no longer holds exactly what this payment left), or when a rotation
 * payout already disbursed or approved the funds it would take back.
 *
 * <p>Lock order matches posting: scheme fund lock, reversal row, payment row, contribution rows by period.
 */
@Service
@RequiredArgsConstructor
public class PaymentReversalService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReversalService.class);
    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String FUND_AUTHORIZE = "FUND_AUTHORIZE";

    private final MemberPaymentRepository paymentRepository;
    private final MemberPaymentAllocationRepository allocationRepository;
    private final MemberPaymentEventRepository eventRepository;
    private final MemberPaymentPostingRepository postingRepository;
    private final MemberPaymentPostingReversalRepository postingReversalRepository;
    private final MemberPaymentReversalRepository reversalRepository;
    private final ContributionRepository contributionRepository;
    private final LoanRepository loanRepository;
    private final LoanService loanService;
    private final LoanRepaymentRepository loanRepaymentRepository;
    private final MemberPaymentLoanReversalRepository loanReversalRepository;
    private final MemberCreditService memberCreditService;
    private final MemberCreditEntryRepository memberCreditRepository;
    private final CooperativeRepository cooperativeRepository;
    private final ContributionService contributionService;
    private final RotationEntitlementService entitlementService;
    private final SchemeFundLockService fundLockService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;

    // ------------------------------------------------------------------ request

    @Transactional
    public ReversalResponse request(UUID cooperativeId, UUID paymentId, String reason, HttpServletRequest http) {
        UserPrincipal actor = requireStaff(cooperativeId);
        String clean = requireReason(reason);
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeIdForUpdate(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        if (actor.getId().equals(payment.getMemberUserId())) {
            throw new ForbiddenException("You cannot request the reversal of your own payment");
        }
        requireReversible(payment);
        List<MemberPaymentReversal> history =
                reversalRepository.findByCooperativeIdAndPaymentIdOrderByRequestedAtDesc(cooperativeId, paymentId);
        if (history.stream().anyMatch(r -> r.getStatus() == PaymentReversalStatus.REQUESTED)) {
            throw new ConflictException("REVERSAL_ALREADY_REQUESTED", "A reversal of this payment is already awaiting approval");
        }
        MemberPaymentReversal saved = reversalRepository.saveAndFlush(MemberPaymentReversal.builder()
                .paymentId(paymentId)
                .cooperativeId(cooperativeId)
                .memberUserId(payment.getMemberUserId())
                .status(PaymentReversalStatus.REQUESTED)
                .reason(clean)
                .requestedBy(actor.getId())
                .requestedAt(Instant.now())
                .openKey(paymentId)
                .build());
        paymentEvent(payment, actor, PaymentStatus.POSTED, PaymentStatus.POSTED, "REVERSAL_REQUESTED");
        audit(actor, saved, payment, AuditableAction.PAYMENT_REVERSAL_REQUEST, http);
        return toResponse(saved, payment);
    }

    // ------------------------------------------------------------------ approve = execute

    /**
     * Approval executes the compensating accounting in this one transaction. Approving an already executed request
     * returns it unchanged, so a repeated click or retry cannot take the amounts back twice.
     */
    @Transactional
    public ReversalResponse approve(UUID cooperativeId, UUID reversalId, String note, HttpServletRequest http) {
        UserPrincipal actor = requireApprover(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);
        MemberPaymentReversal reversal = reversalRepository
                .findByIdAndCooperativeIdForUpdate(reversalId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Reversal", reversalId));
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeIdForUpdate(reversal.getPaymentId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", reversal.getPaymentId()));
        if (reversal.getStatus() == PaymentReversalStatus.EXECUTED) {
            return toResponse(reversal, payment);
        }
        if (reversal.getStatus() != PaymentReversalStatus.REQUESTED) {
            throw new BusinessException("REVERSAL_NOT_OPEN", "This reversal request was already decided");
        }
        requireIndependent(actor, reversal, payment);
        requireReversible(payment);

        List<MemberPaymentPosting> postings =
                new ArrayList<>(postingRepository.findByCooperativeIdAndPaymentIdOrderByPostedAtAscIdAsc(cooperativeId, payment.getId()));
        if (postings.isEmpty()) {
            throw new BusinessException("REVERSAL_NOTHING_POSTED", "This payment has no posted allocations to reverse");
        }
        // Deterministic lock order (contributions by period, then loans by id), then validate before changing anything.
        List<MemberPaymentPosting> contributionPostings = new ArrayList<>(postings.stream()
                .filter(p -> p.getAllocationType() == PaymentAllocationType.CONTRIBUTION)
                .sorted(Comparator.comparing(MemberPaymentPosting::getPeriodYear).thenComparing(MemberPaymentPosting::getPeriodMonth))
                .toList());
        List<MemberPaymentPosting> loanPostings = new ArrayList<>(postings.stream()
                .filter(p -> p.getAllocationType() == PaymentAllocationType.LOAN_REPAYMENT)
                .sorted(Comparator.comparing(MemberPaymentPosting::getLoanId))
                .toList());
        List<MemberPaymentPosting> creditPostings = postings.stream()
                .filter(p -> p.getAllocationType() == PaymentAllocationType.MEMBER_CREDIT)
                .toList();
        List<Contribution> rows = new ArrayList<>();
        for (MemberPaymentPosting posting : contributionPostings) {
            Contribution row = contributionRepository
                    .findByIdAndCooperativeIdForUpdate(posting.getContributionId(), cooperativeId)
                    .orElseThrow(() -> new BusinessException("REVERSAL_BLOCKED_LATER_CHANGES", "A contribution of this payment no longer exists"));
            if (row.getPaidAmount().compareTo(posting.getPaidAfter()) != 0) {
                throw new BusinessException(
                        "REVERSAL_BLOCKED_LATER_CHANGES",
                        "The " + posting.getPeriodYear() + "-" + String.format("%02d", posting.getPeriodMonth())
                                + " contribution was changed after this payment (a later payment or a correction), so it cannot be reversed on its own");
            }
            if (posting.getRotationPeriodId() != null
                    && entitlementService.wouldUndercutDistributions(
                            row, posting.getPaidBefore(), posting.getAmount())) {
                throw new BusinessException(
                        "REVERSAL_BLOCKED_DISTRIBUTED",
                        "A rotation payout already disbursed or approved funds collected through this payment; recovery needs a separately approved process");
            }
            rows.add(row);
        }

        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        Instant now = Instant.now();
        List<MemberPaymentPostingReversal> taken = new ArrayList<>();
        for (int i = 0; i < contributionPostings.size(); i++) {
            MemberPaymentPosting posting = contributionPostings.get(i);
            ContributionService.AppliedPayment undone = contributionService.reversePostedPayment(
                    rows.get(i), posting.getAmount(), actor.getId(), cooperative.getCurrency());
            taken.add(MemberPaymentPostingReversal.builder()
                    .reversalId(reversal.getId())
                    .postingId(posting.getId())
                    .paymentId(payment.getId())
                    .cooperativeId(cooperativeId)
                    .memberUserId(payment.getMemberUserId())
                    .contributionId(posting.getContributionId())
                    .periodYear(posting.getPeriodYear())
                    .periodMonth(posting.getPeriodMonth())
                    .amount(posting.getAmount())
                    .paidBefore(undone.paidBefore())
                    .paidAfter(undone.paidAfter())
                    .rotatingAmount(posting.getRotatingAmount())
                    .retainedAmount(posting.getRetainedAmount())
                    .rotationPeriodId(posting.getRotationPeriodId())
                    .originalRecipientUserId(posting.getOriginalRecipientUserId())
                    .ledgerEntryId(undone.ledgerEntryId())
                    .reversedBy(actor.getId())
                    .reversedAt(now)
                    .build());
        }
        List<MemberPaymentPostingReversal> savedTaken = postingReversalRepository.saveAllAndFlush(taken);

        // loans: the loan engine gives each repayment back, then an immutable record says what was restored
        List<MemberPaymentLoanReversal> loansGivenBack = new ArrayList<>();
        for (MemberPaymentPosting posting : loanPostings) {
            Loan loan = loanRepository
                    .findByIdAndCooperativeIdForUpdate(posting.getLoanId(), cooperativeId)
                    .filter(l -> l.getMemberUserId().equals(payment.getMemberUserId()))
                    .orElseThrow(() -> new BusinessException("REVERSAL_BLOCKED_LATER_CHANGES", "A loan of this payment no longer exists"));
            LoanRepayment repayment = loanRepaymentRepository
                    .findById(posting.getLoanRepaymentId())
                    .filter(r -> r.getCooperativeId().equals(cooperativeId) && r.getLoanId().equals(loan.getId()))
                    .orElseThrow(() -> new BusinessException("REVERSAL_BLOCKED_LATER_CHANGES", "A repayment of this payment no longer exists"));
            LoanService.RepaymentReversal back = loanService.reverseRepayment(loan, repayment, actor.getId());
            loansGivenBack.add(MemberPaymentLoanReversal.builder()
                    .reversalId(reversal.getId())
                    .postingId(posting.getId())
                    .paymentId(payment.getId())
                    .cooperativeId(cooperativeId)
                    .memberUserId(payment.getMemberUserId())
                    .loanId(loan.getId())
                    .loanRepaymentId(repayment.getId())
                    .amount(posting.getAmount())
                    .principalAmount(back.principal())
                    .interestAmount(back.interest())
                    .penaltyAmount(back.penalty())
                    .outstandingPrincipalBefore(back.principalBefore())
                    .outstandingPrincipalAfter(back.principalAfter())
                    .outstandingInterestBefore(back.interestBefore())
                    .outstandingInterestAfter(back.interestAfter())
                    .outstandingPenaltyBefore(back.penaltyBefore())
                    .outstandingPenaltyAfter(back.penaltyAfter())
                    .loanStatusBefore(back.statusBefore().name())
                    .loanStatusAfter(back.statusAfter().name())
                    .principalLedgerEntryId(back.principalLedgerEntryId())
                    .interestLedgerEntryId(back.interestLedgerEntryId())
                    .reversedBy(actor.getId())
                    .reversedAt(now)
                    .build());
        }
        List<MemberPaymentLoanReversal> savedLoans = loanReversalRepository.saveAllAndFlush(loansGivenBack);

        // credit the payment created is taken back only while it is still unspent
        List<MemberCreditEntry> savedCredits = new ArrayList<>();
        for (MemberPaymentPosting posting : creditPostings) {
            savedCredits.add(memberCreditService.reverseReceipt(cooperativeId, payment.getId(), posting.getAllocationId(), actor.getId()));
        }

        payment.setStatus(PaymentStatus.REVERSED);
        payment.setReversedBy(actor.getId());
        payment.setReversedAt(now);
        payment.setReversalReason(reversal.getReason());
        paymentRepository.saveAndFlush(payment);

        reversal.setStatus(PaymentReversalStatus.EXECUTED);
        reversal.setDecidedBy(actor.getId());
        reversal.setDecidedAt(now);
        reversal.setDecisionNote(trim(note));
        reversal.setOpenKey(null);
        reversal.setExecutedKey(payment.getId());
        MemberPaymentReversal executed = reversalRepository.saveAndFlush(reversal);

        paymentEvent(payment, actor, PaymentStatus.POSTED, PaymentStatus.REVERSED, null);
        audit(actor, executed, payment, AuditableAction.PAYMENT_REVERSAL_EXECUTE, http);
        notifyAfterCommit(payment);
        return ReversalResponse.from(executed, payment.getAmount(), savedTaken, savedLoans, savedCredits);
    }

    @Transactional
    public ReversalResponse decline(UUID cooperativeId, UUID reversalId, String note, HttpServletRequest http) {
        UserPrincipal actor = requireApprover(cooperativeId);
        String clean = requireReason(note);
        MemberPaymentReversal reversal = reversalRepository
                .findByIdAndCooperativeIdForUpdate(reversalId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Reversal", reversalId));
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(reversal.getPaymentId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", reversal.getPaymentId()));
        if (reversal.getStatus() != PaymentReversalStatus.REQUESTED) {
            throw new BusinessException("REVERSAL_NOT_OPEN", "This reversal request was already decided");
        }
        requireIndependent(actor, reversal, payment);
        reversal.setStatus(PaymentReversalStatus.DECLINED);
        reversal.setDecidedBy(actor.getId());
        reversal.setDecidedAt(Instant.now());
        reversal.setDecisionNote(clean);
        reversal.setOpenKey(null);
        MemberPaymentReversal saved = reversalRepository.saveAndFlush(reversal);
        paymentEvent(payment, actor, PaymentStatus.POSTED, PaymentStatus.POSTED, "REVERSAL_DECLINED");
        audit(actor, saved, payment, AuditableAction.PAYMENT_REVERSAL_DECLINE, http);
        return toResponse(saved, payment);
    }

    // ------------------------------------------------------------------ withdraw

    /**
     * The original requester withdraws a request that is still pending. No financial movement and no fund lock: it only
     * closes the open request, and is recorded as an immutable payment event and audit entry. Approval and withdrawal
     * serialize on the reversal row, so an executed reversal can never be withdrawn and a withdrawn one never executes.
     */
    @Transactional
    public ReversalResponse withdraw(UUID cooperativeId, UUID reversalId, String note, HttpServletRequest http) {
        UserPrincipal actor = requireStaff(cooperativeId);
        MemberPaymentReversal reversal = reversalRepository
                .findByIdAndCooperativeIdForUpdate(reversalId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Reversal", reversalId));
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(reversal.getPaymentId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", reversal.getPaymentId()));
        if (!actor.getId().equals(reversal.getRequestedBy())) {
            throw new ForbiddenException("Only the person who requested a reversal can withdraw it");
        }
        if (reversal.getStatus() == PaymentReversalStatus.WITHDRAWN) {
            return toResponse(reversal, payment);
        }
        if (reversal.getStatus() != PaymentReversalStatus.REQUESTED) {
            throw new BusinessException("REVERSAL_NOT_OPEN", "This reversal request was already decided");
        }
        reversal.setStatus(PaymentReversalStatus.WITHDRAWN);
        reversal.setWithdrawnBy(actor.getId());
        reversal.setWithdrawnAt(Instant.now());
        reversal.setWithdrawalNote(trim(note));
        reversal.setOpenKey(null);
        MemberPaymentReversal saved = reversalRepository.saveAndFlush(reversal);
        paymentEvent(payment, actor, PaymentStatus.POSTED, PaymentStatus.POSTED, "REVERSAL_WITHDRAWN");
        audit(actor, saved, payment, AuditableAction.PAYMENT_REVERSAL_WITHDRAW, http);
        return toResponse(saved, payment);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ReversalResponse> forPayment(UUID cooperativeId, UUID paymentId) {
        requireViewer(cooperativeId);
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        return reversalRepository.findByCooperativeIdAndPaymentIdOrderByRequestedAtDesc(cooperativeId, paymentId).stream()
                .map(r -> toResponse(r, payment))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReversalResponse> list(UUID cooperativeId) {
        requireViewer(cooperativeId);
        List<ReversalResponse> result = new ArrayList<>();
        for (MemberPaymentReversal r : reversalRepository.findByCooperativeIdOrderByRequestedAtDesc(cooperativeId)) {
            paymentRepository.findByIdAndCooperativeId(r.getPaymentId(), cooperativeId).ifPresent(p -> result.add(toResponse(r, p)));
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private UserPrincipal requireStaff(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) && !actor.hasAuthority(CONTRIBUTION_WRITE)) {
            throw new ForbiddenException("You do not have permission to handle payment reversals");
        }
        return actor;
    }

    /** Approving or declining a reversal is a fund movement decision: it needs the independent fund authority. */
    private UserPrincipal requireApprover(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        CooperativeOfficerRoles.requireFundAuthorize(actor);
        return actor;
    }

    /** Officers who request reversals, or the authorized approvers who decide them, may read them. */
    private UserPrincipal requireViewer(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority(CONTRIBUTION_WRITE)
                && !actor.hasAuthority(FUND_AUTHORIZE)) {
            throw new ForbiddenException("You do not have permission to view payment reversals");
        }
        return actor;
    }

    /** The approver is neither the requester, the payer nor the person who submitted the payment (no Super Admin waiver). */
    private static void requireIndependent(UserPrincipal actor, MemberPaymentReversal reversal, MemberPayment payment) {
        UUID id = actor.getId();
        if (id.equals(reversal.getRequestedBy())) {
            throw new ForbiddenException("The person who requested a reversal cannot decide it");
        }
        if (id.equals(payment.getMemberUserId())) {
            throw new ForbiddenException("The payer cannot decide the reversal of their own payment");
        }
        if (id.equals(payment.getSubmittedBy())) {
            throw new ForbiddenException("The person who submitted a payment cannot decide its reversal");
        }
    }

    private void requireReversible(MemberPayment payment) {
        if (payment.getStatus() != PaymentStatus.POSTED) {
            throw new BusinessException("PAYMENT_NOT_POSTED", "Only a POSTED payment can be reversed; this one is " + payment.getStatus());
        }
    }

    private ReversalResponse toResponse(MemberPaymentReversal reversal, MemberPayment payment) {
        List<MemberPaymentPostingReversal> taken = postingReversalRepository
                .findByCooperativeIdAndPaymentId(reversal.getCooperativeId(), reversal.getPaymentId())
                .stream()
                .filter(t -> t.getReversalId().equals(reversal.getId()))
                .toList();
        List<MemberPaymentLoanReversal> loans = reversal.getStatus() == PaymentReversalStatus.EXECUTED
                ? loanReversalRepository.findByCooperativeIdAndPaymentId(reversal.getCooperativeId(), reversal.getPaymentId())
                        .stream().filter(l -> l.getReversalId().equals(reversal.getId())).toList()
                : List.of();
        List<MemberCreditEntry> credits = reversal.getStatus() == PaymentReversalStatus.EXECUTED
                ? memberCreditRepository.findBySourcePaymentIdAndCooperativeId(reversal.getPaymentId(), reversal.getCooperativeId())
                        .stream().filter(e -> e.getEntryType() == CreditEntryType.RECEIPT_REVERSED).toList()
                : List.of();
        return ReversalResponse.from(reversal, payment.getAmount(), taken, loans, credits);
    }

    private void paymentEvent(MemberPayment payment, UserPrincipal actor, PaymentStatus from, PaymentStatus to, String comment) {
        eventRepository.save(MemberPaymentEvent.builder()
                .paymentId(payment.getId())
                .cooperativeId(payment.getCooperativeId())
                .actorUserId(actor.getId())
                .actorRole(CooperativeOfficerRoles.displayRole(actor))
                .fromStatus(from)
                .toStatus(to)
                .comment(comment)
                .build());
    }

    private void audit(UserPrincipal actor, MemberPaymentReversal reversal, MemberPayment payment, AuditableAction action, HttpServletRequest http) {
        // Only enum/uuid/number values go into the JSON, never free text.
        auditService.record(
                actor.getId(),
                reversal.getCooperativeId(),
                action,
                "MemberPaymentReversal",
                reversal.getId(),
                null,
                "{\"status\":\"" + reversal.getStatus() + "\",\"paymentId\":\"" + payment.getId() + "\",\"amount\":\""
                        + payment.getAmount().toPlainString() + "\"}",
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
    }

    private void notifyAfterCommit(MemberPayment payment) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        UUID memberId = payment.getMemberUserId();
        UUID cooperativeId = payment.getCooperativeId();
        UUID paymentId = payment.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notificationFacade.notifyUser(
                            memberId,
                            cooperativeId,
                            NotificationType.CONTRIBUTION,
                            "Payment reversed",
                            "A posted payment was reversed. Your balances were corrected.",
                            "MemberPayment",
                            paymentId);
                } catch (RuntimeException e) {
                    log.warn("Reversal notification failed for {}: {}", paymentId, e.getClass().getSimpleName());
                }
            }
        });
    }

    private static String requireReason(String reason) {
        String clean = trim(reason);
        if (clean == null) {
            throw new BusinessException("REASON_REQUIRED", "A reason is required");
        }
        return clean;
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        return StringUtils.hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
