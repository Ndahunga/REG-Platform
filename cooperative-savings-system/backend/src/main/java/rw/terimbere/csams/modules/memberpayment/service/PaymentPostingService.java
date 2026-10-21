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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.obligation.ContributionAllocationCalculator;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanService;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentPostingResponse;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentAllocation;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentEvent;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPosting;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.entity.PostingAttribution;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentAllocationRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentEventRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentPostingRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;
import rw.terimbere.csams.modules.rotation.repository.RotationPeriodRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.membercredit.MemberCreditEntry;
import rw.terimbere.csams.modules.membercredit.MemberCreditService;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Verified-only, atomic posting of a combined member payment to the books.
 *
 * <p>One transaction applies every allocation (contribution periods and loan repayments) or none of them. Only a
 * VERIFIED payment with recorded confirmation evidence can post, and the poster must be an authorized officer who is
 * neither the verifier, the evidence recorder, the payer nor the submitter (Super Admin status does not waive this).
 *
 * <p>Lock order, always: scheme fund lock, payment row, contribution rows by (year, month), loan rows by id. The
 * direct contribution and loan routes take the same row locks, so no path can overpay an obligation or lose an update
 * against this one.
 *
 * <p>A failed attempt rolls back completely. Its metadata (attempt count, failure code, an event and an audit record)
 * is then written in an independent transaction, so the payment stays VERIFIED and can be retried safely.
 */
@Service
@RequiredArgsConstructor
public class PaymentPostingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentPostingService.class);
    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String LOAN_WRITE = "LOAN_WRITE";

    private final MemberPaymentRepository paymentRepository;
    private final MemberPaymentAllocationRepository allocationRepository;
    private final MemberPaymentEventRepository eventRepository;
    private final MemberPaymentPostingRepository postingRepository;
    private final ContributionRepository contributionRepository;
    private final LoanRepository loanRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final RotationPeriodRepository rotationPeriodRepository;
    private final ContributionService contributionService;
    private final LoanService loanService;
    private final SchemeFundLockService fundLockService;
    private final MemberCreditService memberCreditService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;
    private final TransactionTemplate transactionTemplate;
    private final PlatformTransactionManager transactionManager;
    private final rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService
            verificationCheckService;

    /** A financial failure of a posting attempt; recorded, then rethrown with the same stable code. */
    static final class PostingFailure extends BusinessException {
        PostingFailure(String code, String message) {
            super(code, message);
        }
    }

    // ------------------------------------------------------------------ post / retry

    /** VERIFIED -> POSTED. Posting an already POSTED payment is a safe no-op that returns the existing result. */
    public PaymentPostingResponse post(UUID cooperativeId, UUID paymentId, HttpServletRequest http) {
        return attempt(cooperativeId, paymentId, http, false);
    }

    /** Re-attempts a payment whose earlier posting failed. Same rules and same transaction as {@link #post}. */
    public PaymentPostingResponse retry(UUID cooperativeId, UUID paymentId, HttpServletRequest http) {
        return attempt(cooperativeId, paymentId, http, true);
    }

    private PaymentPostingResponse attempt(UUID cooperativeId, UUID paymentId, HttpServletRequest http, boolean retry) {
        UserPrincipal actor = authorizationService.currentPrincipal();
        try {
            return transactionTemplate.execute(status -> doPost(cooperativeId, paymentId, actor, http, retry));
        } catch (PostingFailure failure) {
            recordFailure(cooperativeId, paymentId, actor, failure.getErrorCode());
            throw failure;
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException conflict) {
            // Raised at flush/commit: a concurrent legacy edit or a constraint refused the posting. Nothing was applied.
            log.warn("Posting of payment {} conflicted: {}", paymentId, conflict.getClass().getSimpleName());
            recordFailure(cooperativeId, paymentId, actor, "POSTING_CONFLICT");
            throw new ConflictException(
                    "PAYMENT_POSTING_CONFLICT",
                    "The posting conflicted with another change and was not applied; retry it");
        }
    }

    private PaymentPostingResponse doPost(
            UUID cooperativeId, UUID paymentId, UserPrincipal actor, HttpServletRequest http, boolean retry) {
        authorizationService.requireMembership(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeIdForUpdate(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        List<MemberPaymentAllocation> allocations =
                new ArrayList<>(allocationRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId));
        requireStaffFor(actor, allocations);
        if (actor.getId().equals(payment.getMemberUserId())) {
            throw new ForbiddenException("You cannot post your own payment");
        }

        if (payment.getStatus() == PaymentStatus.POSTED) {
            return PaymentPostingResponse.from(
                    payment, postingRepository.findByCooperativeIdAndPaymentIdOrderByPostedAtAscIdAsc(cooperativeId, paymentId));
        }
        if (payment.getStatus() != PaymentStatus.VERIFIED) {
            throw new BusinessException(
                    "PAYMENT_NOT_VERIFIED", "Only a VERIFIED payment can be posted; this one is " + payment.getStatus());
        }
        requireSeparationOfDuties(actor, payment);
        if (!payment.hasVerificationEvidence()) {
            throw new BusinessException(
                    "PAYMENT_EVIDENCE_MISSING",
                    "This payment has no recorded confirmation of received funds; complete its verification evidence first");
        }
        // credible, independent confirmation of the receipt of funds (strict by default; legacy payments are flagged)
        verificationCheckService.enforceBeforePosting(payment, actor);
        if (retry && payment.getPostingAttempts() == 0) {
            throw new BusinessException("NOTHING_TO_RETRY", "This payment has no failed posting attempt to retry");
        }
        if (postingRepository.existsByPaymentId(paymentId)) {
            // Defence in depth: a VERIFIED payment must never already have posting records.
            throw new ConflictException("PAYMENT_ALREADY_APPLIED", "This payment already has posted allocations");
        }

        List<MemberPaymentPosting> postings;
        try {
            postings = applyAll(cooperativeId, payment, allocations, actor, http);
        } catch (PostingFailure failure) {
            throw failure;
        } catch (BusinessException | ConflictException | ResourceNotFoundException failure) {
            throw new PostingFailure(codeOf(failure), failure.getMessage());
        }

        PaymentStatus previous = payment.getStatus();
        payment.setStatus(PaymentStatus.POSTED);
        payment.setPostedBy(actor.getId());
        payment.setPostedAt(Instant.now());
        paymentRepository.saveAndFlush(payment);
        eventRepository.save(MemberPaymentEvent.builder()
                .paymentId(payment.getId())
                .cooperativeId(cooperativeId)
                .actorUserId(actor.getId())
                .actorRole(CooperativeOfficerRoles.displayRole(actor))
                .fromStatus(previous)
                .toStatus(PaymentStatus.POSTED)
                .build());
        auditService.record(
                actor.getId(),
                cooperativeId,
                AuditableAction.PAYMENT_POST,
                "MemberPayment",
                payment.getId(),
                "{\"status\":\"VERIFIED\"}",
                "{\"status\":\"POSTED\",\"amount\":\"" + payment.getAmount().toPlainString()
                        + "\",\"allocations\":" + postings.size() + "}",
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
        notifyAfterCommit(payment);
        return PaymentPostingResponse.from(payment, postings);
    }

    private List<MemberPaymentPosting> applyAll(
            UUID cooperativeId,
            MemberPayment payment,
            List<MemberPaymentAllocation> allocations,
            UserPrincipal actor,
            HttpServletRequest http) {
        if (allocations.isEmpty()) {
            throw new PostingFailure("INVALID_ALLOCATION", "A payment needs at least one allocation");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (MemberPaymentAllocation a : allocations) {
            if (a.getAmount() == null || a.getAmount().signum() <= 0) {
                throw new PostingFailure("INVALID_ALLOCATION", "Every allocation amount must be positive");
            }
            total = total.add(a.getAmount());
        }
        if (total.compareTo(payment.getAmount()) != 0 || payment.getAllocatedAmount().compareTo(payment.getAmount()) != 0) {
            throw new PostingFailure(
                    "ALLOCATION_TOTAL_MISMATCH", "Allocations do not account for the full verified payment amount");
        }
        CooperativeMembership payer = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, payment.getMemberUserId())
                .orElseThrow(() -> new PostingFailure("PAYER_NOT_ACTIVE_MEMBER", "The paying member is no longer in this scheme"));
        if (!"ACTIVE".equals(payer.getMembershipStatus())) {
            throw new PostingFailure("PAYER_NOT_ACTIVE_MEMBER", "The paying member is not an active member");
        }
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));

        // Deterministic order: contributions by period, then loans by id. Lock everything first, then apply.
        List<MemberPaymentAllocation> contributionLines = allocations.stream()
                .filter(a -> a.getAllocationType() == PaymentAllocationType.CONTRIBUTION)
                .sorted(Comparator.comparing(MemberPaymentAllocation::getPeriodYear)
                        .thenComparing(MemberPaymentAllocation::getPeriodMonth))
                .toList();
        List<MemberPaymentAllocation> loanLines = allocations.stream()
                .filter(a -> a.getAllocationType() == PaymentAllocationType.LOAN_REPAYMENT)
                .sorted(Comparator.comparing(MemberPaymentAllocation::getLoanId))
                .toList();

        List<MemberPaymentAllocation> creditLines = allocations.stream()
                .filter(a -> a.getAllocationType() == PaymentAllocationType.MEMBER_CREDIT)
                .toList();

        List<Contribution> contributionRows = new ArrayList<>();
        for (MemberPaymentAllocation line : contributionLines) {
            contributionRows.add(contributionRepository
                    .findForUpdate(cooperativeId, payment.getMemberUserId(), line.getPeriodYear(), line.getPeriodMonth())
                    .orElseThrow(() -> new PostingFailure(
                            "OBLIGATION_NOT_FOUND",
                            "There is no recorded contribution obligation for " + line.getPeriodYear() + "-"
                                    + String.format("%02d", line.getPeriodMonth())
                                    + "; it is not created from today's settings")));
        }
        List<Loan> loans = new ArrayList<>();
        for (MemberPaymentAllocation line : loanLines) {
            loans.add(loanRepository
                    .findByIdAndCooperativeIdForUpdate(line.getLoanId(), cooperativeId)
                    .filter(l -> l.getMemberUserId().equals(payment.getMemberUserId()))
                    .orElseThrow(() -> new PostingFailure("LOAN_NOT_FOUND", "A repayment targets a loan that cannot be found")));
        }

        Instant now = Instant.now();
        List<MemberPaymentPosting> postings = new ArrayList<>();
        for (int i = 0; i < contributionLines.size(); i++) {
            postings.add(postContribution(
                    cooperative, payment, contributionLines.get(i), contributionRows.get(i), actor, now));
        }
        for (int i = 0; i < loanLines.size(); i++) {
            postings.add(postLoan(cooperative, payment, loanLines.get(i), loans.get(i), actor, now, http));
        }
        // excess money is held for the member (never income): a ledger entry and a journal line, in this same transaction
        for (MemberPaymentAllocation line : creditLines) {
            postings.add(postCredit(cooperative, payment, line, actor, now));
        }

        BigDecimal posted = postings.stream().map(MemberPaymentPosting::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (posted.compareTo(payment.getAmount()) != 0) {
            throw new PostingFailure("POSTING_TOTAL_MISMATCH", "The posted amounts do not equal the verified payment");
        }
        return postingRepository.saveAllAndFlush(postings);
    }

    private MemberPaymentPosting postContribution(
            Cooperative cooperative,
            MemberPayment payment,
            MemberPaymentAllocation line,
            Contribution row,
            UserPrincipal actor,
            Instant now) {
        BigDecimal amount = MoneyUtils.scaleForStorage(line.getAmount());
        ContributionService.AppliedPayment applied = contributionService.applyVerifiedPayment(
                row, amount, payment.getPaymentDate(), payment.getTransactionReference(), actor.getId(), cooperative.getCurrency());

        PostingAttribution attribution;
        String model = null;
        Integer rotatingBps = null;
        Integer retainedBps = null;
        BigDecimal rotating = null;
        BigDecimal retained = null;
        UUID periodId = null;
        UUID recipient = null;
        if (row.getObligationSnapshotAt() == null || row.getOperatingModelSnapshot() == null) {
            attribution = PostingAttribution.UNATTRIBUTED;
        } else if ("ACCUMULATING".equals(row.getOperatingModelSnapshot())) {
            attribution = PostingAttribution.ACCUMULATING;
            model = row.getOperatingModelSnapshot();
            rotating = BigDecimal.ZERO.setScale(amount.scale());
            retained = amount;
        } else {
            model = row.getOperatingModelSnapshot();
            if (row.getRotatingBpsSnapshot() == null
                    || row.getRetainedBpsSnapshot() == null
                    || row.getRotationPeriodId() == null) {
                throw new PostingFailure(
                        "ROTATION_ATTRIBUTION_UNRESOLVED",
                        "The " + row.getYear() + "-" + String.format("%02d", row.getMonth())
                                + " obligation has no rotating split or scheduled recipient on record; resolve it before posting");
            }
            RotationPeriod period = rotationPeriodRepository
                    .findByIdAndCooperativeId(row.getRotationPeriodId(), cooperative.getId())
                    .orElseThrow(() -> new PostingFailure(
                            "ROTATION_ATTRIBUTION_UNRESOLVED", "The original rotation period could not be found"));
            attribution = PostingAttribution.ATTRIBUTED;
            rotatingBps = row.getRotatingBpsSnapshot();
            retainedBps = row.getRetainedBpsSnapshot();
            ContributionAllocationCalculator.Split split =
                    rw.terimbere.csams.modules.contribution.obligation.ContributionSplits.delta(row, applied.paidBefore(), amount);
            rotating = split.rotating();
            retained = split.retained();
            periodId = period.getId();
            recipient = period.getScheduledRecipientUserId();
        }
        return MemberPaymentPosting.builder()
                .paymentId(payment.getId())
                .allocationId(line.getId())
                .cooperativeId(cooperative.getId())
                .memberUserId(payment.getMemberUserId())
                .allocationType(PaymentAllocationType.CONTRIBUTION)
                .amount(amount)
                .paymentDate(payment.getPaymentDate())
                .postedBy(actor.getId())
                .postedAt(now)
                .contributionId(row.getId())
                .periodYear(row.getYear())
                .periodMonth(row.getMonth())
                .paidBefore(applied.paidBefore())
                .paidAfter(applied.paidAfter())
                .ledgerEntryId(applied.ledgerEntryId())
                .attribution(attribution)
                .operatingModel(model)
                .rotatingBps(rotatingBps)
                .retainedBps(retainedBps)
                .rotatingAmount(rotating)
                .retainedAmount(retained)
                .rotationPeriodId(periodId)
                .originalRecipientUserId(recipient)
                .build();
    }

    private MemberPaymentPosting postCredit(
            Cooperative cooperative, MemberPayment payment, MemberPaymentAllocation line, UserPrincipal actor, Instant now) {
        MemberCreditEntry credit = memberCreditService.receive(cooperative, payment, line, actor.getId());
        auditService.record(
                actor.getId(),
                cooperative.getId(),
                AuditableAction.MEMBER_CREDIT_RECEIVED,
                "MemberCreditEntry",
                credit.getId(),
                null,
                "{\"paymentId\":\"" + payment.getId() + "\",\"purpose\":\"" + credit.getPurpose() + "\",\"amount\":\""
                        + credit.getAmount().toPlainString() + "\"}",
                null,
                null);
        return MemberPaymentPosting.builder()
                .paymentId(payment.getId())
                .allocationId(line.getId())
                .cooperativeId(cooperative.getId())
                .memberUserId(payment.getMemberUserId())
                .allocationType(PaymentAllocationType.MEMBER_CREDIT)
                .amount(MoneyUtils.scaleForStorage(line.getAmount()))
                .paymentDate(payment.getPaymentDate())
                .postedBy(actor.getId())
                .postedAt(now)
                .attribution(PostingAttribution.NOT_APPLICABLE)
                .loanId(credit.getLoanId())
                .ledgerEntryId(credit.getLedgerEntryId())
                .build();
    }

    private MemberPaymentPosting postLoan(
            Cooperative cooperative,
            MemberPayment payment,
            MemberPaymentAllocation line,
            Loan loan,
            UserPrincipal actor,
            Instant now,
            HttpServletRequest http) {
        BigDecimal amount = MoneyUtils.scaleForStorage(line.getAmount());
        // The loan engine is the only calculator: it validates status and outstanding amount and splits the payment.
        LoanRepayment repayment = loanService.applyRepayment(
                cooperative, loan, amount, payment.getPaymentDate(), payment.getTransactionReference(), null, null, actor.getId());
        if (repayment.getAmountTotal().compareTo(amount) != 0) {
            throw new PostingFailure(
                    "LOAN_ALLOCATION_INCOMPLETE", "The loan could not absorb the full allocated amount");
        }
        if (line.hasBreakdown()
                && (line.getPrincipalAmount().compareTo(repayment.getPrincipalPortion()) != 0
                        || line.getInterestAmount().compareTo(repayment.getInterestPortion()) != 0
                        || line.getPenaltyAmount().compareTo(nz(repayment.getPenaltyPortion())) != 0)) {
            throw new PostingFailure(
                    "LOAN_BREAKDOWN_MISMATCH",
                    "The recorded principal/interest/penalty split differs from the loan engine's calculation");
        }
        auditService.record(
                actor.getId(),
                cooperative.getId(),
                AuditableAction.REPAY,
                "LoanRepayment",
                repayment.getId(),
                null,
                "{\"loanId\":\"" + loan.getId() + "\",\"amount\":\"" + repayment.getAmountTotal()
                        + "\",\"paymentId\":\"" + payment.getId() + "\"}",
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
        return MemberPaymentPosting.builder()
                .paymentId(payment.getId())
                .allocationId(line.getId())
                .cooperativeId(cooperative.getId())
                .memberUserId(payment.getMemberUserId())
                .allocationType(PaymentAllocationType.LOAN_REPAYMENT)
                .amount(amount)
                .paymentDate(payment.getPaymentDate())
                .postedBy(actor.getId())
                .postedAt(now)
                .attribution(PostingAttribution.NOT_APPLICABLE)
                .loanId(loan.getId())
                .loanRepaymentId(repayment.getId())
                .principalAmount(repayment.getPrincipalPortion())
                .interestAmount(repayment.getInterestPortion())
                .penaltyAmount(nz(repayment.getPenaltyPortion()))
                .build();
    }

    // ------------------------------------------------------------------ failure metadata

    /** Independent transaction: runs after the failed attempt rolled back and touches only attempt metadata. */
    private void recordFailure(UUID cooperativeId, UUID paymentId, UserPrincipal actor, String code) {
        try {
            TransactionTemplate independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(status -> {
                int updated = paymentRepository.recordPostingFailure(paymentId, cooperativeId, code, Instant.now());
                if (updated == 0) {
                    return;
                }
                eventRepository.save(MemberPaymentEvent.builder()
                        .paymentId(paymentId)
                        .cooperativeId(cooperativeId)
                        .actorUserId(actor.getId())
                        .actorRole(CooperativeOfficerRoles.displayRole(actor))
                        .fromStatus(PaymentStatus.VERIFIED)
                        .toStatus(PaymentStatus.VERIFIED)
                        .comment("POSTING_FAILED:" + code)
                        .build());
                auditService.record(
                        actor.getId(),
                        cooperativeId,
                        AuditableAction.PAYMENT_POST_FAILED,
                        "MemberPayment",
                        paymentId,
                        null,
                        "{\"failure\":\"" + code + "\"}",
                        null,
                        null);
            });
        } catch (RuntimeException e) {
            log.error("Could not record the posting failure of payment {}: {}", paymentId, e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ reads

    /** Posting status and the immutable per-allocation results. Staff only. */
    @Transactional(readOnly = true)
    public PaymentPostingResponse getPosting(UUID cooperativeId, UUID paymentId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        requireStaffFor(actor, allocationRepository.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId));
        return PaymentPostingResponse.from(
                payment, postingRepository.findByCooperativeIdAndPaymentIdOrderByPostedAtAscIdAsc(cooperativeId, paymentId));
    }

    // ------------------------------------------------------------------ helpers

    private static void requireSeparationOfDuties(UserPrincipal actor, MemberPayment payment) {
        UUID id = actor.getId();
        if (id.equals(payment.getVerifiedBy())) {
            throw new ForbiddenException("The person who verified a payment cannot post it");
        }
        if (id.equals(payment.getEvidenceCompletedBy())) {
            throw new ForbiddenException("The person who recorded a payment's verification evidence cannot post it");
        }
        if (id.equals(payment.getSubmittedBy())) {
            throw new ForbiddenException("The person who submitted a payment cannot post it");
        }
    }

    /** Authority is needed for every allocation type present; Super Admin passes this check only, never separation. */
    private static void requireStaffFor(UserPrincipal principal, List<MemberPaymentAllocation> allocations) {
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return;
        }
        for (MemberPaymentAllocation a : allocations) {
            String needed = a.getAllocationType() == PaymentAllocationType.LOAN_REPAYMENT ? LOAN_WRITE : CONTRIBUTION_WRITE;
            if (!principal.hasAuthority(needed)) {
                throw new ForbiddenException("You do not have permission to handle " + a.getAllocationType() + " payments");
            }
        }
    }

    private void notifyAfterCommit(MemberPayment payment) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        UUID memberId = payment.getMemberUserId();
        UUID cooperativeId = payment.getCooperativeId();
        UUID paymentId = payment.getId();
        String amount = payment.getAmount().toPlainString();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notificationFacade.notifyUser(
                            memberId,
                            cooperativeId,
                            NotificationType.CONTRIBUTION,
                            "Payment posted",
                            "Your payment of " + amount + " was confirmed and posted to your accounts.",
                            "MemberPayment",
                            paymentId);
                } catch (RuntimeException e) {
                    log.warn("Payment posted notification failed for {}: {}", paymentId, e.getClass().getSimpleName());
                }
            }
        });
    }

    private static String codeOf(RuntimeException e) {
        if (e instanceof BusinessException b) {
            return b.getErrorCode();
        }
        if (e instanceof ConflictException c && c.getCode() != null) {
            return c.getCode();
        }
        if (e instanceof ResourceNotFoundException) {
            return "OBLIGATION_NOT_FOUND";
        }
        if (e instanceof ConflictException) {
            return "POSTING_CONFLICT";
        }
        return "POSTING_ERROR";
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(4) : value;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
