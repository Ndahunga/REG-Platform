package rw.terimbere.csams.modules.rotation.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;
import rw.terimbere.csams.modules.memberpayment.service.PaymentReferenceNormalizer;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriodStatus;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService;
import rw.terimbere.csams.modules.rotation.repository.RotationPeriodRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
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
 * Controlled reconciliation of rotating contributions that were recorded without verification evidence.
 *
 * <p>An officer asks, with credible evidence (method, written basis, an external reference unless it was cash), for an
 * amount of one obligation to be treated as really received. A different authorized officer (fund authority, who is
 * neither the requester nor the payer) approves or declines it. Approval creates no ledger entry and changes no balance:
 * the money is already in the ledger exactly once. It only adds a verified amount to the obligation, which is what makes
 * the money payout-eligible and moves it from "unreconciled" to the verified rotating liability.
 *
 * <p>Only obligations that already carry their rotation snapshot, period and original recipient can be reconciled, so
 * nothing is invented; the period and recipient are copied from the obligation. The amount can never exceed what is paid
 * and not yet verified, the same external reference cannot back two reconciliations, and an obligation has at most one
 * open request. Records are never edited or deleted; every step is an immutable event.
 */
@Service
@RequiredArgsConstructor
public class LegacyReconciliationService {

    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String FUND_AUTHORIZE = "FUND_AUTHORIZE";

    private final LegacyReconciliationRepository repository;
    private final LegacyReconciliationEventRepository eventRepository;
    private final ContributionRepository contributionRepository;
    private final RotationPeriodRepository periodRepository;
    private final RotationEntitlementService entitlementService;
    private final SchemeFundLockService fundLockService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final UserRepository userRepository;

    // ------------------------------------------------------------------ candidates

    /** Rotating obligations with money paid that no verified payment or approved reconciliation accounts for. */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID cooperativeId) {
        requireViewer(cooperativeId);
        Map<UUID, BigDecimal> verified = entitlementService.verifiedAmounts(cooperativeId);
        Map<UUID, UUID> openByContribution = new HashMap<>();
        for (LegacyRotatingReconciliation r :
                repository.findByCooperativeIdAndStatus(cooperativeId, LegacyReconciliationStatus.REQUESTED)) {
            openByContribution.put(r.getContributionId(), r.getId());
        }
        Map<UUID, RotationPeriod> periods = new HashMap<>();
        periodRepository.findByCooperativeIdOrderByPeriodYearAscPeriodMonthAsc(cooperativeId)
                .forEach(p -> periods.put(p.getId(), p));
        List<Contribution> rows = contributionRepository.findByCooperativeIdAndRotationPeriodIdIsNotNull(cooperativeId);
        List<Candidate> result = new ArrayList<>();
        List<UUID> userIds = new ArrayList<>();
        for (Contribution row : rows) {
            RotationPeriod period = periods.get(row.getRotationPeriodId());
            if (period == null || !isReconcilable(row, period)) {
                continue;
            }
            BigDecimal verifiedAmount = verified.getOrDefault(row.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
            BigDecimal unverified = MoneyUtils.scaleForStorage(row.getPaidAmount().subtract(verifiedAmount).max(BigDecimal.ZERO));
            if (unverified.signum() <= 0) {
                continue;
            }
            userIds.add(row.getMemberUserId());
            userIds.add(period.getScheduledRecipientUserId());
            result.add(new Candidate(
                    row.getId(),
                    row.getMemberUserId(),
                    "",
                    row.getYear(),
                    row.getMonth(),
                    period.getId(),
                    period.getScheduledRecipientUserId(),
                    "",
                    row.getPaidAmount(),
                    MoneyUtils.scaleForStorage(verifiedAmount.min(row.getPaidAmount())),
                    unverified,
                    RotationEntitlementService.rotatingOf(row, unverified),
                    openByContribution.get(row.getId())));
        }
        Map<UUID, String> names = new HashMap<>();
        for (User u : userRepository.findAllById(userIds)) {
            names.put(u.getId(), u.getFullName());
        }
        List<Candidate> named = new ArrayList<>();
        for (Candidate c : result) {
            named.add(new Candidate(
                    c.contributionId(), c.memberUserId(), names.getOrDefault(c.memberUserId(), ""), c.year(), c.month(),
                    c.rotationPeriodId(), c.originalRecipientUserId(), names.getOrDefault(c.originalRecipientUserId(), ""),
                    c.paidAmount(), c.verifiedAmount(), c.unreconciledAmount(), c.unreconciledRotating(), c.openRequestId()));
        }
        named.sort(java.util.Comparator.comparingInt(Candidate::year).thenComparingInt(Candidate::month));
        return named;
    }

    // ------------------------------------------------------------------ request

    @Transactional
    public ReconciliationResponse request(UUID cooperativeId, RequestCommand command) {
        UserPrincipal actor = requireRequester(cooperativeId);
        if (command == null || command.contributionId() == null) {
            throw new BusinessException("RECONCILIATION_CONTRIBUTION_REQUIRED", "Choose the contribution to reconcile");
        }
        Contribution row = contributionRepository
                .findByIdAndCooperativeIdForUpdate(command.contributionId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Contribution", command.contributionId()));
        if (actor.getId().equals(row.getMemberUserId())) {
            throw new ForbiddenException("You cannot reconcile your own contribution");
        }
        RotationPeriod period = requireReconcilable(row, cooperativeId);
        VerificationMethod method = command.method();
        if (method == null) {
            throw new BusinessException("RECONCILIATION_METHOD_REQUIRED", "Say how the funds were confirmed");
        }
        String basis = trim(command.basis());
        if (basis == null) {
            throw new BusinessException("RECONCILIATION_BASIS_REQUIRED", "State what confirmation you relied on");
        }
        String reference = trim(command.reference());
        if (method.requiresReference() && reference == null) {
            throw new BusinessException(
                    "RECONCILIATION_REFERENCE_REQUIRED", "A transaction or statement reference is required for " + method);
        }
        BigDecimal amount = command.amount() == null ? null : MoneyUtils.scaleForStorage(command.amount());
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("RECONCILIATION_AMOUNT_INVALID", "The amount must be positive");
        }
        BigDecimal unverified = unverifiedOf(row);
        if (amount.compareTo(unverified) > 0) {
            throw new BusinessException(
                    "RECONCILIATION_AMOUNT_EXCEEDS_UNVERIFIED",
                    "Only " + unverified.toPlainString() + " of this contribution is paid and not yet verified");
        }
        if (repository.findByCooperativeIdAndStatus(cooperativeId, LegacyReconciliationStatus.REQUESTED).stream()
                .anyMatch(r -> r.getContributionId().equals(row.getId()))) {
            throw new ConflictException(
                    "RECONCILIATION_ALREADY_OPEN", "This contribution already has an open reconciliation request");
        }
        String referenceKey = null;
        if (reference != null) {
            String normalized = PaymentReferenceNormalizer.normalize(reference);
            referenceKey = method.name() + "|" + normalized;
            if (repository.findByCooperativeIdAndReferenceKey(cooperativeId, referenceKey).isPresent()) {
                throw new ConflictException(
                        "RECONCILIATION_REFERENCE_REUSED", "This reference already backs another reconciliation");
            }
        }
        Instant now = Instant.now();
        LegacyRotatingReconciliation saved;
        try {
            saved = repository.saveAndFlush(LegacyRotatingReconciliation.builder()
                    .cooperativeId(cooperativeId)
                    .contributionId(row.getId())
                    .memberUserId(row.getMemberUserId())
                    .periodYear(row.getYear())
                    .periodMonth(row.getMonth())
                    .rotationPeriodId(period.getId())
                    .originalRecipientUserId(period.getScheduledRecipientUserId())
                    .amount(amount)
                    .status(LegacyReconciliationStatus.REQUESTED)
                    .method(method)
                    .basis(basis)
                    .reference(reference)
                    .referenceKey(referenceKey)
                    .requestedBy(actor.getId())
                    .requestedAt(now)
                    .openKey(row.getId())
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(
                    "RECONCILIATION_DUPLICATE", "This contribution or reference already has a reconciliation");
        }
        event(saved, actor, null, LegacyReconciliationStatus.REQUESTED, null);
        audit(actor, saved, AuditableAction.LEGACY_RECONCILIATION_REQUEST);
        return respond(saved);
    }

    // ------------------------------------------------------------------ decide

    /**
     * Confirms the amount as received. Approving an already approved request returns it unchanged, so a repeated click
     * can never add the verified amount twice.
     */
    @Transactional
    public ReconciliationResponse approve(UUID cooperativeId, UUID id, String note) {
        UserPrincipal actor = requireApprover(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);
        LegacyRotatingReconciliation r = lock(cooperativeId, id);
        if (r.getStatus() == LegacyReconciliationStatus.APPROVED) {
            return respond(r);
        }
        requireOpen(r);
        requireIndependent(actor, r);
        Contribution row = contributionRepository
                .findByIdAndCooperativeIdForUpdate(r.getContributionId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Contribution", r.getContributionId()));
        requireReconcilable(row, cooperativeId);
        if (r.getAmount().compareTo(unverifiedOf(row)) > 0) {
            throw new BusinessException(
                    "RECONCILIATION_AMOUNT_EXCEEDS_UNVERIFIED",
                    "The contribution changed: less of it is now paid and unverified than this request confirms");
        }
        LegacyReconciliationStatus previous = r.getStatus();
        r.setStatus(LegacyReconciliationStatus.APPROVED);
        r.setDecidedBy(actor.getId());
        r.setDecidedAt(Instant.now());
        r.setDecisionNote(trim(note));
        r.setOpenKey(null);
        LegacyRotatingReconciliation saved = repository.saveAndFlush(r);
        event(saved, actor, previous, LegacyReconciliationStatus.APPROVED, trim(note));
        audit(actor, saved, AuditableAction.LEGACY_RECONCILIATION_APPROVE);
        return respond(saved);
    }

    @Transactional
    public ReconciliationResponse decline(UUID cooperativeId, UUID id, String reason) {
        UserPrincipal actor = requireApprover(cooperativeId);
        String why = trim(reason);
        if (why == null) {
            throw new BusinessException("RECONCILIATION_DECLINE_REASON_REQUIRED", "A reason is required to decline");
        }
        LegacyRotatingReconciliation r = lock(cooperativeId, id);
        if (r.getStatus() == LegacyReconciliationStatus.DECLINED) {
            return respond(r);
        }
        requireOpen(r);
        requireIndependent(actor, r);
        LegacyReconciliationStatus previous = r.getStatus();
        r.setStatus(LegacyReconciliationStatus.DECLINED);
        r.setDecidedBy(actor.getId());
        r.setDecidedAt(Instant.now());
        r.setDecisionNote(why);
        r.setOpenKey(null);
        r.setReferenceKey(null); // the reference is released so a corrected request can use it
        LegacyRotatingReconciliation saved = repository.saveAndFlush(r);
        event(saved, actor, previous, LegacyReconciliationStatus.DECLINED, why);
        audit(actor, saved, AuditableAction.LEGACY_RECONCILIATION_DECLINE);
        return respond(saved);
    }

    /** Only the requester can withdraw, and only while the request is still open. */
    @Transactional
    public ReconciliationResponse withdraw(UUID cooperativeId, UUID id, String reason) {
        UserPrincipal actor = requireRequester(cooperativeId);
        LegacyRotatingReconciliation r = lock(cooperativeId, id);
        if (r.getStatus() == LegacyReconciliationStatus.WITHDRAWN) {
            return respond(r);
        }
        requireOpen(r);
        if (!actor.getId().equals(r.getRequestedBy())) {
            throw new ForbiddenException("Only the officer who requested a reconciliation can withdraw it");
        }
        LegacyReconciliationStatus previous = r.getStatus();
        r.setStatus(LegacyReconciliationStatus.WITHDRAWN);
        r.setWithdrawnBy(actor.getId());
        r.setWithdrawnAt(Instant.now());
        r.setOpenKey(null);
        r.setReferenceKey(null);
        LegacyRotatingReconciliation saved = repository.saveAndFlush(r);
        event(saved, actor, previous, LegacyReconciliationStatus.WITHDRAWN, trim(reason));
        audit(actor, saved, AuditableAction.LEGACY_RECONCILIATION_WITHDRAW);
        return respond(saved);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ReconciliationResponse> list(UUID cooperativeId) {
        requireViewer(cooperativeId);
        return repository.findByCooperativeIdOrderByRequestedAtDesc(cooperativeId).stream()
                .map(this::respond)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventResponse> events(UUID cooperativeId, UUID id) {
        requireViewer(cooperativeId);
        repository.findByIdAndCooperativeId(id, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Reconciliation", id));
        return eventRepository.findByCooperativeIdAndReconciliationIdOrderByCreatedAtAsc(cooperativeId, id).stream()
                .map(e -> new EventResponse(
                        e.getId(), e.getActorUserId(), e.getActorRole(), e.getFromStatus(), e.getToStatus(), e.getComment(), e.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isReconcilable(Contribution row, RotationPeriod period) {
        String model = row.getOperatingModelSnapshot();
        return row.getRotatingBpsSnapshot() != null
                && row.getRotationPeriodId() != null
                && period.getScheduledRecipientUserId() != null
                && ("ROTATING".equals(model) || "HYBRID".equals(model))
                && period.getStatus() != RotationPeriodStatus.CANCELLED
                && row.getStatus() != ContributionStatus.CANCELLED
                && row.getStatus() != ContributionStatus.WAIVED;
    }

    /** The obligation must already carry its snapshot, period and recipient: they are never supplied or invented. */
    private RotationPeriod requireReconcilable(Contribution row, UUID cooperativeId) {
        if (row.getRotationPeriodId() == null || row.getRotatingBpsSnapshot() == null) {
            throw new BusinessException(
                    "RECONCILIATION_NO_ROTATION_BINDING",
                    "This contribution has no recorded rotation period or snapshot, so it cannot be reconciled");
        }
        RotationPeriod period = periodRepository
                .findByIdAndCooperativeId(row.getRotationPeriodId(), cooperativeId)
                .orElseThrow(() -> new BusinessException(
                        "RECONCILIATION_NO_ROTATION_BINDING", "The rotation period of this contribution was not found"));
        if (!isReconcilable(row, period)) {
            throw new BusinessException(
                    "RECONCILIATION_NOT_ELIGIBLE",
                    "This contribution is not a rotating obligation of an active period with a known recipient");
        }
        return period;
    }

    private BigDecimal unverifiedOf(Contribution row) {
        BigDecimal verified = entitlementService
                .verifiedAmounts(row.getCooperativeId())
                .getOrDefault(row.getId(), BigDecimal.ZERO)
                .max(BigDecimal.ZERO);
        return MoneyUtils.scaleForStorage(row.getPaidAmount().subtract(verified).max(BigDecimal.ZERO));
    }

    private LegacyRotatingReconciliation lock(UUID cooperativeId, UUID id) {
        return repository.findByIdForUpdate(id, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Reconciliation", id));
    }

    private static void requireOpen(LegacyRotatingReconciliation r) {
        if (r.getStatus() != LegacyReconciliationStatus.REQUESTED) {
            throw new ConflictException("RECONCILIATION_NOT_OPEN", "This request is already " + r.getStatus());
        }
    }

    /** Neither the requester nor the payer decides; no Super Admin waiver. */
    private static void requireIndependent(UserPrincipal actor, LegacyRotatingReconciliation r) {
        if (actor.getId().equals(r.getRequestedBy()) || actor.getId().equals(r.getMemberUserId())) {
            throw new ForbiddenException("The requester and the payer cannot decide this reconciliation");
        }
    }

    private UserPrincipal requireRequester(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) && !actor.hasAuthority(CONTRIBUTION_WRITE)) {
            throw new ForbiddenException("You do not have permission to request reconciliations");
        }
        return actor;
    }

    private UserPrincipal requireApprover(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        CooperativeOfficerRoles.requireFundAuthorize(actor);
        return actor;
    }

    private UserPrincipal requireViewer(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority(CONTRIBUTION_WRITE)
                && !actor.hasAuthority(FUND_AUTHORIZE)) {
            throw new ForbiddenException("You do not have permission to view reconciliations");
        }
        return actor;
    }

    private void event(
            LegacyRotatingReconciliation r,
            UserPrincipal actor,
            LegacyReconciliationStatus from,
            LegacyReconciliationStatus to,
            String comment) {
        eventRepository.save(LegacyReconciliationEvent.builder()
                .reconciliationId(r.getId())
                .cooperativeId(r.getCooperativeId())
                .actorUserId(actor.getId())
                .actorRole(CooperativeOfficerRoles.displayRole(actor))
                .fromStatus(from)
                .toStatus(to)
                .comment(comment)
                .createdAt(Instant.now())
                .build());
    }

    private void audit(UserPrincipal actor, LegacyRotatingReconciliation r, AuditableAction action) {
        auditService.record(
                actor.getId(),
                r.getCooperativeId(),
                action,
                "LegacyRotatingReconciliation",
                r.getId(),
                null,
                "{\"contributionId\":\"" + r.getContributionId() + "\",\"amount\":\"" + r.getAmount().toPlainString()
                        + "\",\"status\":\"" + r.getStatus() + "\"}",
                null,
                null);
    }

    private ReconciliationResponse respond(LegacyRotatingReconciliation r) {
        return new ReconciliationResponse(
                r.getId(),
                r.getContributionId(),
                r.getMemberUserId(),
                r.getPeriodYear(),
                r.getPeriodMonth(),
                r.getRotationPeriodId(),
                r.getOriginalRecipientUserId(),
                r.getAmount(),
                r.getStatus(),
                r.getMethod(),
                r.getBasis(),
                r.getReference(),
                r.getRequestedBy(),
                r.getRequestedAt(),
                r.getDecidedBy(),
                r.getDecidedAt(),
                r.getDecisionNote(),
                r.getWithdrawnAt());
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    // ------------------------------------------------------------------ DTOs

    public record Candidate(
            UUID contributionId,
            UUID memberUserId,
            String memberName,
            int year,
            int month,
            UUID rotationPeriodId,
            UUID originalRecipientUserId,
            String originalRecipientName,
            BigDecimal paidAmount,
            BigDecimal verifiedAmount,
            BigDecimal unreconciledAmount,
            BigDecimal unreconciledRotating,
            UUID openRequestId) {}

    public record RequestCommand(
            UUID contributionId, BigDecimal amount, VerificationMethod method, String basis, String reference) {}

    public record ReconciliationResponse(
            UUID id,
            UUID contributionId,
            UUID memberUserId,
            int periodYear,
            int periodMonth,
            UUID rotationPeriodId,
            UUID originalRecipientUserId,
            BigDecimal amount,
            LegacyReconciliationStatus status,
            VerificationMethod method,
            String basis,
            String reference,
            UUID requestedBy,
            Instant requestedAt,
            UUID decidedBy,
            Instant decidedAt,
            String decisionNote,
            Instant withdrawnAt) {}

    public record EventResponse(
            UUID id,
            UUID actorUserId,
            String actorRole,
            LegacyReconciliationStatus fromStatus,
            LegacyReconciliationStatus toStatus,
            String comment,
            Instant createdAt) {}
}
