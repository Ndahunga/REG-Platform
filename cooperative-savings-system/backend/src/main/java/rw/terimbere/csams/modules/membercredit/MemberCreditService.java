package rw.terimbere.csams.modules.membercredit;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.obligation.ContributionDueDates;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanService;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentAllocation;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Money a member paid that is not (yet) a contribution or a loan repayment, held for them.
 *
 * <p><b>It is never income and never group money.</b> Receiving a credit writes a MEMBER_CREDIT_RECEIVED ledger entry and a
 * journal line; the ledger type is excluded from the group's available funds, so the money is visible in the books
 * without being lent, paid out or counted as the group's. Applying a credit to a contribution or a loan is what turns it
 * into group money, through exactly the same engines a payment uses (the contribution ledger credit, the loan engine's
 * principal, interest and penalty split), together with a MEMBER_CREDIT_APPLIED debit that takes it off the member's
 * balance. The balance is never stored: it is the sum of the journal.
 *
 * <p>Receiving runs inside the posting transaction of a verified payment (so a failed allocation leaves no credit).
 * Applying takes the scheme fund lock first, so two applications of the same credit can never both succeed, and is
 * idempotent per officer request key.
 */
@Service
@RequiredArgsConstructor
public class MemberCreditService {

    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String LOAN_WRITE = "LOAN_WRITE";

    private final MemberCreditEntryRepository repository;
    private final LedgerService ledgerService;
    private final ContributionRepository contributionRepository;
    private final ContributionService contributionService;
    private final LoanRepository loanRepository;
    private final LoanService loanService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final SchemeFundLockService fundLockService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    public record ApplyRequest(
            UUID memberUserId,
            CreditPurpose purpose,
            UUID bucketLoanId,
            PaymentAllocationType targetType,
            Integer periodYear,
            Integer periodMonth,
            UUID loanId,
            BigDecimal amount,
            String note) {}

    public record BucketBalance(UUID memberUserId, String memberName, CreditPurpose purpose, UUID loanId, BigDecimal balance) {}

    public record Overview(List<BucketBalance> balances, BigDecimal totalHeld) {}

    public record EntryView(
            UUID id,
            UUID memberUserId,
            CreditEntryType entryType,
            CreditPurpose purpose,
            UUID loanId,
            BigDecimal amount,
            UUID sourcePaymentId,
            UUID appliedContributionId,
            UUID appliedLoanRepaymentId,
            String note,
            Instant createdAt) {}

    // ------------------------------------------------------------------ receiving (inside the posting transaction)

    /** Records the credit created by one posted MEMBER_CREDIT allocation of a verified payment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public MemberCreditEntry receive(Cooperative cooperative, MemberPayment payment, MemberPaymentAllocation allocation, UUID actorId) {
        if (allocation.getCreditPurpose() == null) {
            throw new BusinessException("INVALID_ALLOCATION", "A member-credit allocation needs a purpose");
        }
        CreditPurpose purpose = allocation.getCreditPurpose();
        if (purpose == CreditPurpose.LOAN_PREPAYMENT) {
            Loan loan = loanRepository
                    .findByIdAndCooperativeId(allocation.getLoanId(), cooperative.getId())
                    .filter(l -> l.getMemberUserId().equals(payment.getMemberUserId()))
                    .orElseThrow(() -> new BusinessException("LOAN_NOT_FOUND", "A loan prepayment targets a loan that cannot be found"));
            if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.OVERDUE) {
                throw new BusinessException("LOAN_NOT_REPAYABLE", "A loan can only be prepaid while it is ACTIVE or OVERDUE");
            }
        }
        BigDecimal amount = MoneyUtils.scaleForStorage(allocation.getAmount());
        LedgerEntry ledger = ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperative.getId())
                .memberUserId(payment.getMemberUserId())
                .transactionType(LedgerTransactionType.MEMBER_CREDIT_RECEIVED)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(amount)
                .currency(cooperative.getCurrency())
                .transactionDate(payment.getPaymentDate())
                .reference(payment.getTransactionReference())
                .sourceEntityType(LedgerService.SOURCE_MEMBER_CREDIT)
                .sourceEntityId(allocation.getId())
                .description("Member credit held (" + purpose + ")")
                .recordedBy(actorId)
                .approvedBy(actorId)
                .idempotencyKey(LedgerService.memberCreditReceivedKey(allocation.getId()))
                .build());
        return repository.saveAndFlush(MemberCreditEntry.builder()
                .cooperativeId(cooperative.getId())
                .memberUserId(payment.getMemberUserId())
                .entryType(CreditEntryType.RECEIVED)
                .purpose(purpose)
                .loanId(purpose == CreditPurpose.LOAN_PREPAYMENT ? allocation.getLoanId() : null)
                .amount(amount)
                .sourcePaymentId(payment.getId())
                .sourceAllocationId(allocation.getId())
                .ledgerEntryId(ledger.getId())
                .createdBy(actorId)
                .build());
    }

    // ------------------------------------------------------------------ taking a receipt back (a payment reversal)

    /**
     * Takes back the credit a reversed payment created, while it is still unspent. The credit leaves the member's balance
     * with a MEMBER_CREDIT_REVERSED ledger entry and a RECEIPT_REVERSED journal line; nothing original is changed. When the
     * credit (or part of it) has already been applied elsewhere there is nothing left to take back, and the reversal is
     * refused: the application has to be undone first.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MemberCreditEntry reverseReceipt(UUID cooperativeId, UUID paymentId, UUID allocationId, UUID actorId) {
        MemberCreditEntry receipt = repository
                .findBySourceAllocationId(allocationId)
                .filter(e -> e.getCooperativeId().equals(cooperativeId) && paymentId.equals(e.getSourcePaymentId()))
                .orElseThrow(() -> new BusinessException("REVERSAL_BLOCKED_LATER_CHANGES", "The credit this payment created cannot be found"));
        if (repository.existsByReversesEntryId(receipt.getId())) {
            throw new BusinessException("CREDIT_ALREADY_REVERSED", "The credit of this payment was already taken back");
        }
        BigDecimal held = balance(cooperativeId, receipt.getMemberUserId(), receipt.getPurpose(), receipt.getLoanId());
        if (held.compareTo(receipt.getAmount()) < 0) {
            throw new BusinessException(
                    "REVERSAL_BLOCKED_CREDIT_APPLIED",
                    "Part of the credit this payment created was already applied to a contribution or a loan; undo that first");
        }
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        LedgerEntry ledger = ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(receipt.getMemberUserId())
                .transactionType(LedgerTransactionType.MEMBER_CREDIT_REVERSED)
                .debitAmount(receipt.getAmount())
                .creditAmount(BigDecimal.ZERO)
                .currency(cooperative.getCurrency())
                .transactionDate(ContributionDueDates.today(clock))
                .sourceEntityType(LedgerService.SOURCE_MEMBER_CREDIT)
                .sourceEntityId(receipt.getSourceAllocationId())
                .description("Member credit taken back: the payment that created it was reversed")
                .recordedBy(actorId)
                .approvedBy(actorId)
                .reversesEntryId(receipt.getLedgerEntryId())
                .idempotencyKey(LedgerService.memberCreditReversedKey(receipt.getId()))
                .build());
        MemberCreditEntry reversed = repository.saveAndFlush(MemberCreditEntry.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(receipt.getMemberUserId())
                .entryType(CreditEntryType.RECEIPT_REVERSED)
                .purpose(receipt.getPurpose())
                .loanId(receipt.getLoanId())
                .amount(receipt.getAmount())
                .sourcePaymentId(paymentId)
                .reversesEntryId(receipt.getId())
                .ledgerEntryId(ledger.getId())
                .createdBy(actorId)
                .build());
        auditService.record(
                actorId, cooperativeId, AuditableAction.MEMBER_CREDIT_REVERSED, "MemberCreditEntry", reversed.getId(), null,
                "{\"paymentId\":\"" + paymentId + "\",\"amount\":\"" + receipt.getAmount().toPlainString() + "\"}", null, null);
        return reversed;
    }

    // ------------------------------------------------------------------ applying (an officer's decision)

    @Transactional
    public EntryView apply(UUID cooperativeId, ApplyRequest request, String idempotencyKey) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        validate(request, idempotencyKey);
        requireStaffFor(actor, request.targetType());
        if (actor.getId().equals(request.memberUserId())) {
            throw new ForbiddenException("You cannot apply your own credit");
        }
        String key = idempotencyKey.trim();

        // the fund lock serialises every application and posting of the scheme: two requests can never spend one credit
        fundLockService.lockSchemeFund(cooperativeId);
        MemberCreditEntry replay = repository.findByCooperativeIdAndIdempotencyKey(cooperativeId, key).orElse(null);
        if (replay != null) {
            return sameRequest(replay, request)
                    ? view(replay)
                    : failConflict("IDEMPOTENCY_KEY_REUSED", "This key was already used for a different credit application");
        }

        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        CooperativeMembership member = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, request.memberUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Member", request.memberUserId()));
        if (!"ACTIVE".equals(member.getMembershipStatus())) {
            throw new BusinessException("MEMBER_NOT_ACTIVE", "The member is not an active member of this Saving Scheme");
        }

        BigDecimal amount = MoneyUtils.scaleForStorage(request.amount());
        UUID bucketLoan = request.purpose() == CreditPurpose.LOAN_PREPAYMENT ? request.bucketLoanId() : null;
        BigDecimal available = balance(cooperativeId, request.memberUserId(), request.purpose(), bucketLoan);
        if (amount.compareTo(available) > 0) {
            throw new BusinessException(
                    "CREDIT_INSUFFICIENT", "The member holds only " + available.toPlainString() + " in this credit");
        }
        checkPurposeFitsTarget(request);

        LocalDate today = ContributionDueDates.today(clock);
        UUID contributionId = null;
        UUID repaymentId = null;
        String reference = "CREDIT-" + key.substring(0, Math.min(24, key.length()));
        if (request.targetType() == PaymentAllocationType.CONTRIBUTION) {
            Contribution row = contributionRepository
                    .findForUpdate(cooperativeId, request.memberUserId(), request.periodYear(), request.periodMonth())
                    .orElseThrow(() -> new BusinessException(
                            "OBLIGATION_NOT_FOUND",
                            "There is no recorded contribution obligation for " + request.periodYear() + "-"
                                    + String.format("%02d", request.periodMonth())));
            requireAttributionResolved(row);
            Contribution saved = contributionService
                    .applyVerifiedPayment(row, amount, today, reference, actor.getId(), cooperative.getCurrency())
                    .contribution();
            contributionId = saved.getId();
        } else {
            Loan loan = loanRepository
                    .findByIdAndCooperativeIdForUpdate(request.loanId(), cooperativeId)
                    .filter(l -> l.getMemberUserId().equals(request.memberUserId()))
                    .orElseThrow(() -> new BusinessException("LOAN_NOT_FOUND", "The loan cannot be found for this member"));
            LoanRepayment repayment =
                    loanService.applyRepayment(cooperative, loan, amount, today, reference, request.note(), null, actor.getId());
            if (repayment.getAmountTotal().compareTo(amount) != 0) {
                throw new BusinessException("LOAN_ALLOCATION_INCOMPLETE", "The loan could not absorb the full amount");
            }
            repaymentId = repayment.getId();
        }

        LedgerEntry ledger = ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(request.memberUserId())
                .transactionType(LedgerTransactionType.MEMBER_CREDIT_APPLIED)
                .debitAmount(amount)
                .creditAmount(BigDecimal.ZERO)
                .currency(cooperative.getCurrency())
                .transactionDate(today)
                .reference(reference)
                .sourceEntityType(LedgerService.SOURCE_MEMBER_CREDIT)
                .sourceEntityId(contributionId != null ? contributionId : repaymentId)
                .description("Member credit applied to a " + (contributionId != null ? "contribution" : "loan repayment"))
                .recordedBy(actor.getId())
                .approvedBy(actor.getId())
                .idempotencyKey(LedgerService.memberCreditAppliedKey(cooperativeId, key))
                .build());
        MemberCreditEntry entry = repository.saveAndFlush(MemberCreditEntry.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(request.memberUserId())
                .entryType(CreditEntryType.APPLIED)
                .purpose(request.purpose())
                .loanId(bucketLoan)
                .amount(amount)
                .appliedContributionId(contributionId)
                .appliedLoanRepaymentId(repaymentId)
                .ledgerEntryId(ledger.getId())
                .idempotencyKey(key)
                .note(StringUtils.hasText(request.note()) ? request.note().trim() : null)
                .createdBy(actor.getId())
                .build());
        auditService.record(
                actor.getId(), cooperativeId, AuditableAction.MEMBER_CREDIT_APPLIED, "MemberCreditEntry", entry.getId(),
                null,
                "{\"member\":\"" + request.memberUserId() + "\",\"purpose\":\"" + request.purpose() + "\",\"amount\":\""
                        + amount.toPlainString() + "\",\"target\":\"" + request.targetType() + "\"}",
                null, null);
        return view(entry);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Overview overview(UUID cooperativeId, UUID memberFilter) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        UUID member = visibleMember(actor, memberFilter);
        List<MemberCreditEntry> entries = member == null
                ? repository.findByCooperativeIdOrderByCreatedAtAscIdAsc(cooperativeId)
                : repository.findByCooperativeIdAndMemberUserIdOrderByCreatedAtAscIdAsc(cooperativeId, member);
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<String, MemberCreditEntry> samples = new LinkedHashMap<>();
        for (MemberCreditEntry e : entries) {
            String key = e.getMemberUserId() + "|" + e.getPurpose() + "|" + e.getLoanId();
            sums.merge(key, signed(e), BigDecimal::add);
            samples.putIfAbsent(key, e);
        }
        Map<UUID, String> names = names(samples.values().stream().map(MemberCreditEntry::getMemberUserId).distinct().toList());
        List<BucketBalance> balances = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> sum : sums.entrySet()) {
            MemberCreditEntry sample = samples.get(sum.getKey());
            BigDecimal value = MoneyUtils.scaleForStorage(sum.getValue());
            if (value.signum() == 0) {
                continue;
            }
            balances.add(new BucketBalance(
                    sample.getMemberUserId(), names.get(sample.getMemberUserId()), sample.getPurpose(), sample.getLoanId(), value));
            total = total.add(value);
        }
        return new Overview(List.copyOf(balances), MoneyUtils.scaleForStorage(total));
    }

    @Transactional(readOnly = true)
    public List<EntryView> history(UUID cooperativeId, UUID memberFilter) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        UUID member = visibleMember(actor, memberFilter);
        List<MemberCreditEntry> entries = member == null
                ? repository.findByCooperativeIdOrderByCreatedAtAscIdAsc(cooperativeId)
                : repository.findByCooperativeIdAndMemberUserIdOrderByCreatedAtAscIdAsc(cooperativeId, member);
        return entries.stream().map(MemberCreditService::view).toList();
    }

    /** What the scheme holds for its members in total (receipts less applications and reversals). */
    @Transactional(readOnly = true)
    public BigDecimal totalHeld(UUID cooperativeId) {
        BigDecimal received = nz(repository.sumByType(cooperativeId, CreditEntryType.RECEIVED));
        BigDecimal applied = nz(repository.sumByType(cooperativeId, CreditEntryType.APPLIED));
        BigDecimal reversed = nz(repository.sumByType(cooperativeId, CreditEntryType.RECEIPT_REVERSED));
        return MoneyUtils.scaleForStorage(received.subtract(applied).subtract(reversed));
    }

    /** The unspent balance of one member's credit bucket. */
    @Transactional(readOnly = true)
    public BigDecimal balance(UUID cooperativeId, UUID memberUserId, CreditPurpose purpose, UUID loanId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (MemberCreditEntry e : repository.findByCooperativeIdAndMemberUserIdOrderByCreatedAtAscIdAsc(cooperativeId, memberUserId)) {
            if (e.getPurpose() == purpose && Objects.equals(e.getLoanId(), loanId)) {
                sum = sum.add(signed(e));
            }
        }
        return MoneyUtils.scaleForStorage(sum);
    }

    // ------------------------------------------------------------------ rules

    private static void validate(ApplyRequest r, String idempotencyKey) {
        if (r == null || r.memberUserId() == null || r.purpose() == null || r.targetType() == null) {
            throw new BusinessException("INVALID_CREDIT_APPLICATION", "The member, the credit and the target are required");
        }
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > 128) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "An Idempotency-Key of at most 128 characters is required");
        }
        MoneyUtils.assertPositive(r.amount());
        if (r.purpose() == CreditPurpose.LOAN_PREPAYMENT && r.bucketLoanId() == null) {
            throw new BusinessException("INVALID_CREDIT_APPLICATION", "A loan-prepayment credit is identified by its loan");
        }
        if (r.targetType() == PaymentAllocationType.CONTRIBUTION) {
            if (r.periodYear() == null || r.periodMonth() == null || r.periodMonth() < 1 || r.periodMonth() > 12) {
                throw new BusinessException("INVALID_CREDIT_APPLICATION", "A contribution target needs a valid year and month");
            }
        } else if (r.targetType() == PaymentAllocationType.LOAN_REPAYMENT) {
            if (r.loanId() == null) {
                throw new BusinessException("INVALID_CREDIT_APPLICATION", "A loan target needs a loan");
            }
        } else {
            throw new BusinessException("INVALID_CREDIT_APPLICATION", "Credit can only be applied to a contribution or a loan");
        }
    }

    private static void checkPurposeFitsTarget(ApplyRequest r) {
        boolean fits = switch (r.purpose()) {
            case ADVANCE_CONTRIBUTION -> r.targetType() == PaymentAllocationType.CONTRIBUTION;
            case LOAN_PREPAYMENT -> r.targetType() == PaymentAllocationType.LOAN_REPAYMENT
                    && r.bucketLoanId().equals(r.loanId());
            case UNAPPLIED_CREDIT -> true;
        };
        if (!fits) {
            throw new BusinessException(
                    "CREDIT_PURPOSE_MISMATCH", "This credit was set aside for something else and cannot be used for that target");
        }
    }

    /** The same refusal posting makes: a rotating obligation without its split or recipient is never guessed. */
    private static void requireAttributionResolved(Contribution row) {
        String model = row.getOperatingModelSnapshot();
        if (row.getObligationSnapshotAt() != null && model != null && !"ACCUMULATING".equals(model)
                && (row.getRotatingBpsSnapshot() == null || row.getRetainedBpsSnapshot() == null || row.getRotationPeriodId() == null)) {
            throw new BusinessException(
                    "ROTATION_ATTRIBUTION_UNRESOLVED",
                    "The " + row.getYear() + "-" + String.format("%02d", row.getMonth())
                            + " obligation has no rotating split or scheduled recipient on record; resolve it first");
        }
    }

    private static void requireStaffFor(UserPrincipal actor, PaymentAllocationType target) {
        if (actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            return;
        }
        String needed = target == PaymentAllocationType.LOAN_REPAYMENT ? LOAN_WRITE : CONTRIBUTION_WRITE;
        if (!actor.hasAuthority(needed)) {
            throw new ForbiddenException("You do not have permission to apply credit to this target");
        }
    }

    /** A member sees only their own credit; an officer may look at any member of the scheme. */
    private static UUID visibleMember(UserPrincipal actor, UUID requested) {
        boolean staff = actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || actor.hasAuthority(CONTRIBUTION_WRITE)
                || actor.hasAuthority(LOAN_WRITE);
        if (staff) {
            return requested;
        }
        if (requested != null && !requested.equals(actor.getId())) {
            throw new ForbiddenException("You can only see your own credit");
        }
        return actor.getId();
    }

    private static boolean sameRequest(MemberCreditEntry e, ApplyRequest r) {
        boolean sameTarget = r.targetType() == PaymentAllocationType.CONTRIBUTION
                ? e.getAppliedContributionId() != null
                : e.getAppliedLoanRepaymentId() != null;
        return e.getMemberUserId().equals(r.memberUserId())
                && e.getPurpose() == r.purpose()
                && e.getAmount().compareTo(MoneyUtils.scaleForStorage(r.amount())) == 0
                && sameTarget;
    }

    private static EntryView failConflict(String code, String message) {
        throw new ConflictException(code, message);
    }

    private static BigDecimal signed(MemberCreditEntry e) {
        return e.getEntryType() == CreditEntryType.RECEIVED ? e.getAmount() : e.getAmount().negate();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static EntryView view(MemberCreditEntry e) {
        return new EntryView(
                e.getId(), e.getMemberUserId(), e.getEntryType(), e.getPurpose(), e.getLoanId(), e.getAmount(),
                e.getSourcePaymentId(), e.getAppliedContributionId(), e.getAppliedLoanRepaymentId(), e.getNote(),
                e.getCreatedAt());
    }

    private Map<UUID, String> names(List<UUID> ids) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }
}
