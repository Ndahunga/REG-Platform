package rw.terimbere.csams.modules.rotation.payout;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriodStatus;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService.PeriodEntitlement;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.ConfirmDisbursement;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.EventResponse;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.PayoutResponse;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutDtos.RequestPayout;
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
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Controlled payout of a rotation recipient's entitlement.
 *
 * <p>Funds are only ever the rotating money that was really collected for the period (never expected contributions) and
 * only up to what the scheme actually has available. A payout is requested by one officer, approved by a different
 * one, and becomes financially complete only when an officer confirms the actual disbursement with a method and a
 * reference, which writes the ledger debit.
 *
 * <p>Every mutation takes the scheme fund lock first and then the payout row, so payouts, share-outs, loans and
 * investments of one scheme serialize in one fixed order. The entitlement and the available funds are checked again at
 * approval and at confirmation, so what was true at request time is never trusted later.
 */
@Service
@RequiredArgsConstructor
public class RotationPayoutService {

    private static final Logger log = LoggerFactory.getLogger(RotationPayoutService.class);
    private static final String PAYOUT_WRITE = "PAYOUT_WRITE";
    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private final RotationPayoutRepository payoutRepository;
    private final RotationPayoutEventRepository eventRepository;
    private final RotationPeriodRepository periodRepository;
    private final RotationEntitlementService entitlementService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final SchemeFundLockService fundLockService;
    private final LedgerFinancialCalculationService financialCalculationService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;
    private final UserRepository userRepository;
    private final rw.terimbere.csams.modules.rotation.pool.RotationPoolService poolService;
    private final rw.terimbere.csams.modules.rotation.repository.RotationTurnRepository turnRepository;
    private final rw.terimbere.csams.modules.rotation.repository.RotationCycleRepository cycleRepository;
    private final RotationPayoutOffsetRepository offsetRepository;
    private final rw.terimbere.csams.modules.contribution.repository.ContributionRepository contributionRepository;
    private final rw.terimbere.csams.modules.contribution.service.ContributionService contributionService;
    private final java.time.Clock clock;

    // ------------------------------------------------------------------ request

    @Transactional
    public PayoutResponse request(UUID cooperativeId, RequestPayout request, String idempotencyKey, HttpServletRequest http) {
        UserPrincipal actor = requireStaff(cooperativeId);
        requireRotationModel(cooperativeId);
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
        if (request.getRotationTurnId() != null) {
            return requestTurn(cooperativeId, request, key, actor, http);
        }
        if (request.getRotationPeriodId() == null) {
            throw new BusinessException("PAYOUT_TARGET_REQUIRED", "Name the rotation period or the turn to pay");
        }
        if (key != null) {
            RotationPayout existing = payoutRepository
                    .findByCooperativeIdAndRequestedByAndIdempotencyKey(cooperativeId, actor.getId(), key)
                    .orElse(null);
            if (existing != null) {
                if (!existing.getRotationPeriodId().equals(request.getRotationPeriodId())) {
                    throw new ConflictException("IDEMPOTENCY_KEY_REUSED", "This key was already used for a different payout");
                }
                return toResponse(existing);
            }
        }
        fundLockService.lockSchemeFund(cooperativeId);

        RotationPeriod period = periodRepository
                .findByIdAndCooperativeId(request.getRotationPeriodId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", request.getRotationPeriodId()));
        if (entitlementService.sharePeriodIds(cooperativeId).contains(period.getId())) {
            throw new BusinessException("ROTATION_PAY_BY_TURN", "This cycle pays by share: request the payout of a turn");
        }
        if (period.getStatus() != RotationPeriodStatus.SCHEDULED) {
            throw new BusinessException(
                    "ROTATION_PERIOD_NOT_PAYABLE",
                    "This rotation period was cancelled; collected funds on it need a separately approved resolution");
        }
        UUID recipient = period.getScheduledRecipientUserId();
        if (actor.getId().equals(recipient)) {
            throw new ForbiddenException("A recipient cannot request their own payout");
        }
        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, recipient)
                .orElseThrow(() -> new BusinessException("RECIPIENT_NOT_ACTIVE", "The scheduled recipient is not a member of this scheme"));
        if (!"ACTIVE".equals(membership.getMembershipStatus())) {
            throw new BusinessException("RECIPIENT_NOT_ACTIVE", "The scheduled recipient is not an active member");
        }
        if (payoutRepository.sumAmount(cooperativeId, period.getId(), java.util.EnumSet.of(RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED))
                        .signum()
                > 0) {
            throw new ConflictException("PAYOUT_ALREADY_OPEN", "This period already has a payout awaiting approval or disbursement");
        }

        PeriodEntitlement entitlement = entitlementService.forPeriod(cooperativeId, period.getId());
        // The period's verified collections plus whatever reserve covers the shortfall of what was scheduled, less what it
        // already paid out. Never another period's collections.
        BigDecimal outstanding = MoneyUtils.scaleForStorage(
                poolService.payoutCeiling(cooperativeId, period.getId()).subtract(entitlement.distributed()));
        if (outstanding.signum() <= 0) {
            throw new BusinessException("NO_ENTITLEMENT", "No rotating funds have been collected for this period, or all of them were paid out");
        }
        BigDecimal available = availableFunds(cooperativeId);
        if (available.signum() <= 0) {
            throw new BusinessException("INSUFFICIENT_AVAILABLE_FUNDS", "The scheme has no available funds to pay out");
        }
        BigDecimal ceiling = outstanding.min(available);
        BigDecimal amount = request.getAmount() == null ? ceiling : MoneyUtils.scaleForStorage(request.getAmount());
        if (amount.signum() <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "A payout must be greater than zero");
        }
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException("PAYOUT_EXCEEDS_ENTITLEMENT", "A payout cannot exceed the collected, undistributed entitlement of " + outstanding.toPlainString());
        }
        if (amount.compareTo(available) > 0) {
            throw new BusinessException("PAYOUT_EXCEEDS_AVAILABLE", "A payout cannot exceed the scheme's available funds of " + available.toPlainString());
        }

        RotationPayout saved = payoutRepository.saveAndFlush(RotationPayout.builder()
                .cooperativeId(cooperativeId)
                .rotationPeriodId(period.getId())
                .recipientUserId(recipient)
                .amount(amount)
                .entitlementAtRequest(outstanding)
                .availableAtRequest(available)
                .status(RotationPayoutStatus.REQUESTED)
                .idempotencyKey(key)
                .notes(trim(request.getNotes()))
                .requestedBy(actor.getId())
                .requestedAt(Instant.now())
                .openKey(period.getId())
                .build());
        recordEvent(saved, actor, null, RotationPayoutStatus.REQUESTED, null);
        audit(actor, saved, AuditableAction.ROTATION_PAYOUT_REQUEST, null, http);
        return toResponse(saved);
    }

    // ------------------------------------------------------------------ share-based turns

    private PayoutResponse requestTurn(
            UUID cooperativeId, RequestPayout request, String key, UserPrincipal actor, HttpServletRequest http) {
        if (key != null) {
            RotationPayout existing = payoutRepository
                    .findByCooperativeIdAndRequestedByAndIdempotencyKey(cooperativeId, actor.getId(), key)
                    .orElse(null);
            if (existing != null) {
                if (!request.getRotationTurnId().equals(existing.getRotationTurnId())) {
                    throw new ConflictException("IDEMPOTENCY_KEY_REUSED", "This key was already used for a different payout");
                }
                return toResponse(existing);
            }
        }
        fundLockService.lockSchemeFund(cooperativeId);
        var turn = turnRepository.findByIdAndCooperativeId(request.getRotationTurnId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation turn", request.getRotationTurnId()));
        String blocked = turnBlockedReason(cooperativeId, turn, actor);
        if (blocked != null) {
            throw new BusinessException(blocked, turnBlockedMessage(blocked));
        }
        BigDecimal outstanding = turnOutstanding(cooperativeId, turn, BigDecimal.ZERO);
        BigDecimal pool = poolService.sharePool(cooperativeId).available();
        BigDecimal arrears = memberArrears(cooperativeId, turn.getMemberUserId());
        BigDecimal available = availableFunds(cooperativeId);
        BigDecimal ceiling = outstanding.min(pool);
        BigDecimal amount = request.getAmount() == null ? ceiling : MoneyUtils.scaleForStorage(request.getAmount());
        if (amount.signum() <= 0) {
            throw new BusinessException("NO_ENTITLEMENT", "The rotation pool has nothing to pay this turn yet");
        }
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException("PAYOUT_EXCEEDS_ENTITLEMENT", "This turn has only " + outstanding.toPlainString() + " left to pay");
        }
        if (amount.compareTo(pool) > 0) {
            throw new BusinessException("PAYOUT_EXCEEDS_POOL", "The rotation pool holds only " + pool.toPlainString() + " right now");
        }
        BigDecimal netted = arrears.min(amount);
        if (amount.subtract(netted).compareTo(available) > 0) {
            throw new BusinessException("PAYOUT_EXCEEDS_AVAILABLE", "A payout cannot exceed the scheme's available funds of " + available.toPlainString());
        }
        RotationPayout saved = payoutRepository.saveAndFlush(RotationPayout.builder()
                .cooperativeId(cooperativeId)
                .rotationPeriodId(turn.getRotationPeriodId())
                .rotationTurnId(turn.getId())
                .recipientUserId(turn.getMemberUserId())
                .amount(amount)
                .arrearsNetted(MoneyUtils.scaleForStorage(netted))
                .entitlementAtRequest(outstanding)
                .availableAtRequest(available)
                .status(RotationPayoutStatus.REQUESTED)
                .idempotencyKey(key)
                .notes(trim(request.getNotes()))
                .requestedBy(actor.getId())
                .requestedAt(Instant.now())
                .openKey(turn.getId())
                .build());
        recordEvent(saved, actor, null, RotationPayoutStatus.REQUESTED, null);
        audit(actor, saved, AuditableAction.ROTATION_PAYOUT_REQUEST, null, http);
        return toResponse(saved);
    }

    /** Null when a payout may be requested for the turn now, else a stable reason code. */
    private String turnBlockedReason(UUID cooperativeId, rw.terimbere.csams.modules.rotation.entity.RotationTurn turn, UserPrincipal actor) {
        var cycle = cycleRepository.findByIdAndCooperativeId(turn.getCycleId(), cooperativeId).orElse(null);
        RotationPeriod period = periodRepository.findByIdAndCooperativeId(turn.getRotationPeriodId(), cooperativeId).orElse(null);
        if (cycle == null || period == null
                || cycle.getStatus() != rw.terimbere.csams.modules.rotation.entity.RotationCycleStatus.ACTIVE
                || period.getStatus() != RotationPeriodStatus.SCHEDULED) {
            return "PERIOD_NOT_PAYABLE";
        }
        if (java.time.YearMonth.of(period.getPeriodYear(), period.getPeriodMonth())
                .isAfter(java.time.YearMonth.now(clock.withZone(KIGALI)))) {
            return "TURN_NOT_DUE";
        }
        if (actor.getId().equals(turn.getMemberUserId())) {
            return "RECIPIENT_CANNOT_REQUEST_OWN";
        }
        if (!isActiveMember(cooperativeId, turn.getMemberUserId())) {
            return "RECIPIENT_NOT_ACTIVE";
        }
        if (payoutRepository.sumAmountForTurn(cooperativeId, turn.getId(), EnumSet.of(RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED)).signum() > 0) {
            return "PAYOUT_ALREADY_OPEN";
        }
        if (turnOutstanding(cooperativeId, turn, BigDecimal.ZERO).signum() <= 0) {
            return "PAYOUT_ALREADY_COMPLETED";
        }
        return null;
    }

    private static String turnBlockedMessage(String code) {
        return switch (code) {
            case "PERIOD_NOT_PAYABLE" -> "This turn belongs to a cycle that is not active";
            case "TURN_NOT_DUE" -> "This turn's month has not begun yet";
            case "RECIPIENT_CANNOT_REQUEST_OWN" -> "A recipient cannot request their own payout";
            case "RECIPIENT_NOT_ACTIVE" -> "The member of this turn is not an active member";
            case "PAYOUT_ALREADY_OPEN" -> "This turn already has a payout awaiting approval or disbursement";
            case "PAYOUT_ALREADY_COMPLETED" -> "This turn was already paid in full";
            default -> "A payout cannot be requested for this turn now";
        };
    }

    /** What the turn still pays: payout less received before migration, disbursed, and committed (except {@code ownCommit}). */
    private BigDecimal turnOutstanding(UUID cooperativeId, rw.terimbere.csams.modules.rotation.entity.RotationTurn turn, BigDecimal ownCommit) {
        BigDecimal disbursed = payoutRepository.sumAmountForTurn(cooperativeId, turn.getId(), EnumSet.of(RotationPayoutStatus.DISBURSED));
        BigDecimal open = payoutRepository.sumAmountForTurn(cooperativeId, turn.getId(), EnumSet.of(RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED))
                .subtract(ownCommit);
        return MoneyUtils.scaleForStorage(turn.getPayoutAmount()
                .subtract(turn.getOpeningReceivedAmount())
                .subtract(disbursed)
                .subtract(open)
                .max(BigDecimal.ZERO));
    }

    /** The member's unpaid contributions whose due date has passed (never loans or fines). */
    private BigDecimal memberArrears(UUID cooperativeId, UUID memberUserId) {
        BigDecimal total = BigDecimal.ZERO;
        for (var row : overdueRows(cooperativeId, memberUserId)) {
            total = total.add(row.getOutstandingAmount());
        }
        return MoneyUtils.scaleForStorage(total);
    }

    private List<rw.terimbere.csams.modules.contribution.entity.Contribution> overdueRows(UUID cooperativeId, UUID memberUserId) {
        LocalDate today = LocalDate.now(clock.withZone(KIGALI));
        List<rw.terimbere.csams.modules.contribution.entity.Contribution> rows = new ArrayList<>();
        for (var row : contributionRepository.findByCooperativeIdAndMemberUserIdOrderByYearDescMonthDesc(cooperativeId, memberUserId)) {
            if (row.getStatus() == rw.terimbere.csams.modules.contribution.entity.ContributionStatus.WAIVED
                    || row.getStatus() == rw.terimbere.csams.modules.contribution.entity.ContributionStatus.CANCELLED) {
                continue;
            }
            if (row.getOutstandingAmount() == null || row.getOutstandingAmount().signum() <= 0) {
                continue;
            }
            LocalDate due = row.getDueDate() != null ? row.getDueDate()
                    : java.time.YearMonth.of(row.getYear(), row.getMonth()).plusMonths(1).atDay(10);
            if (due.isBefore(today)) {
                rows.add(row);
            }
        }
        rows.sort(java.util.Comparator.comparingInt(rw.terimbere.csams.modules.contribution.entity.Contribution::getYear)
                .thenComparingInt(rw.terimbere.csams.modules.contribution.entity.Contribution::getMonth));
        return rows;
    }

    /** Applies the netted part of a disbursed turn payout to the recipient's overdue contributions, oldest first. */
    private BigDecimal applyNetting(RotationPayout payout, UserPrincipal actor, String reference, String currency) {
        BigDecimal left = payout.getArrearsNetted();
        BigDecimal applied = BigDecimal.ZERO;
        LocalDate today = LocalDate.now(clock.withZone(KIGALI));
        for (var row : overdueRows(payout.getCooperativeId(), payout.getRecipientUserId())) {
            if (left.signum() <= 0) {
                break;
            }
            var locked = contributionRepository.findByIdAndCooperativeIdForUpdate(row.getId(), payout.getCooperativeId()).orElse(null);
            if (locked == null || locked.getOutstandingAmount() == null || locked.getOutstandingAmount().signum() <= 0) {
                continue;
            }
            BigDecimal x = MoneyUtils.scaleForStorage(left.min(locked.getOutstandingAmount()));
            contributionService.applyVerifiedPayment(locked, x, today, reference, actor.getId(), currency);
            offsetRepository.save(RotationPayoutOffset.builder()
                    .cooperativeId(payout.getCooperativeId())
                    .payoutId(payout.getId())
                    .contributionId(locked.getId())
                    .rotationPeriodId(locked.getRotationPeriodId())
                    .amount(x)
                    .build());
            left = left.subtract(x);
            applied = applied.add(x);
        }
        return MoneyUtils.scaleForStorage(applied);
    }

    /** Turns of share-based cycles and whether each can be paid now (for the officer's payout screen). */
    @Transactional(readOnly = true)
    public List<RotationPayoutDtos.TurnPayoutEligibility> turnEligibility(UUID cooperativeId) {
        UserPrincipal actor = requireStaffAny(cooperativeId);
        requireRotationModel(cooperativeId);
        boolean authorized = actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) || actor.hasAuthority(PAYOUT_WRITE);
        BigDecimal pool = poolService.sharePool(cooperativeId).available();
        Map<UUID, RotationPeriod> periods = new HashMap<>();
        periodRepository.findByCooperativeIdOrderByPeriodYearAscPeriodMonthAsc(cooperativeId).forEach(p -> periods.put(p.getId(), p));
        Map<UUID, BigDecimal> arrearsCache = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        List<RotationPayoutDtos.TurnPayoutEligibility> result = new ArrayList<>();
        for (var turn : turnRepository.findByCooperativeId(cooperativeId)) {
            RotationPeriod period = periods.get(turn.getRotationPeriodId());
            if (period == null) {
                continue;
            }
            String reason = authorized ? turnBlockedReason(cooperativeId, turn, actor) : "NOT_AUTHORIZED";
            BigDecimal outstanding = turnOutstanding(cooperativeId, turn, BigDecimal.ZERO);
            BigDecimal arrears = arrearsCache.computeIfAbsent(turn.getMemberUserId(), m -> memberArrears(cooperativeId, m));
            if (reason == null && pool.signum() <= 0) {
                reason = "NO_COLLECTED_FUNDS";
            }
            String name = names.computeIfAbsent(turn.getMemberUserId(), m -> userRepository.findById(m).map(User::getFullName).orElse(""));
            result.add(new RotationPayoutDtos.TurnPayoutEligibility(
                    turn.getId(), period.getId(), period.getPeriodYear(), period.getPeriodMonth(),
                    turn.getMemberUserId(), name, turn.getShareCount(),
                    MoneyUtils.scaleForStorage(turn.getPayoutAmount()), outstanding, arrears,
                    reason == null, reason,
                    reason == null ? outstanding.min(pool) : MoneyUtils.scaleForStorage(BigDecimal.ZERO)));
        }
        result.sort(java.util.Comparator.comparingInt(RotationPayoutDtos.TurnPayoutEligibility::periodYear)
                .thenComparingInt(RotationPayoutDtos.TurnPayoutEligibility::periodMonth)
                .thenComparing(RotationPayoutDtos.TurnPayoutEligibility::memberName));
        return result;
    }

    // ------------------------------------------------------------------ approve / reject / cancel

    @Transactional
    public PayoutResponse approve(UUID cooperativeId, UUID payoutId, HttpServletRequest http) {
        UserPrincipal actor = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(actor);
        fundLockService.lockSchemeFund(cooperativeId);
        RotationPayout payout = lockPayout(cooperativeId, payoutId);
        if (payout.getStatus() == RotationPayoutStatus.APPROVED && actor.getId().equals(payout.getApprovedBy())) {
            return toResponse(payout);
        }
        payout.getStatus().requireTransitionTo(RotationPayoutStatus.APPROVED);
        if (actor.getId().equals(payout.getRequestedBy())) {
            throw new ForbiddenException("The person who requested a payout cannot approve it");
        }
        if (actor.getId().equals(payout.getRecipientUserId())) {
            throw new ForbiddenException("A recipient cannot approve their own payout");
        }
        requireStillCovered(payout);
        RotationPayoutStatus previous = payout.getStatus();
        payout.setStatus(RotationPayoutStatus.APPROVED);
        payout.setApprovedBy(actor.getId());
        payout.setApprovedAt(Instant.now());
        RotationPayout saved = payoutRepository.saveAndFlush(payout);
        recordEvent(saved, actor, previous, RotationPayoutStatus.APPROVED, null);
        audit(actor, saved, AuditableAction.ROTATION_PAYOUT_APPROVE, null, http);
        return toResponse(saved);
    }

    @Transactional
    public PayoutResponse reject(UUID cooperativeId, UUID payoutId, String reason, HttpServletRequest http) {
        UserPrincipal actor = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(actor);
        String clean = requireReason(reason);
        fundLockService.lockSchemeFund(cooperativeId);
        RotationPayout payout = lockPayout(cooperativeId, payoutId);
        payout.getStatus().requireTransitionTo(RotationPayoutStatus.REJECTED);
        if (actor.getId().equals(payout.getRecipientUserId())) {
            throw new ForbiddenException("A recipient cannot decide on their own payout");
        }
        RotationPayoutStatus previous = payout.getStatus();
        payout.setStatus(RotationPayoutStatus.REJECTED);
        payout.setRejectedBy(actor.getId());
        payout.setRejectedAt(Instant.now());
        payout.setRejectionReason(clean);
        payout.setOpenKey(null);
        RotationPayout saved = payoutRepository.saveAndFlush(payout);
        recordEvent(saved, actor, previous, RotationPayoutStatus.REJECTED, clean);
        audit(actor, saved, AuditableAction.ROTATION_PAYOUT_REJECT, clean, http);
        return toResponse(saved);
    }

    @Transactional
    public PayoutResponse cancel(UUID cooperativeId, UUID payoutId, String reason, HttpServletRequest http) {
        UserPrincipal actor = requireStaff(cooperativeId);
        String clean = requireReason(reason);
        fundLockService.lockSchemeFund(cooperativeId);
        RotationPayout payout = lockPayout(cooperativeId, payoutId);
        payout.getStatus().requireTransitionTo(RotationPayoutStatus.CANCELLED);
        if (actor.getId().equals(payout.getRecipientUserId())) {
            throw new ForbiddenException("A recipient cannot decide on their own payout");
        }
        RotationPayoutStatus previous = payout.getStatus();
        payout.setStatus(RotationPayoutStatus.CANCELLED);
        payout.setCancelledBy(actor.getId());
        payout.setCancelledAt(Instant.now());
        payout.setCancelReason(clean);
        payout.setOpenKey(null);
        RotationPayout saved = payoutRepository.saveAndFlush(payout);
        recordEvent(saved, actor, previous, RotationPayoutStatus.CANCELLED, clean);
        audit(actor, saved, AuditableAction.ROTATION_PAYOUT_CANCEL, clean, http);
        return toResponse(saved);
    }

    // ------------------------------------------------------------------ confirm the actual disbursement

    /**
     * APPROVED -> DISBURSED. The officer states that the money was actually handed over (method and reference). Only
     * here does the ledger get its debit; an approval alone never moves money. Repeating the same confirmation returns
     * the payout unchanged; a different reference for an already disbursed payout is refused.
     */
    @Transactional
    public PayoutResponse confirmDisbursement(
            UUID cooperativeId, UUID payoutId, ConfirmDisbursement request, HttpServletRequest http) {
        UserPrincipal actor = requireStaff(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);
        RotationPayout payout = lockPayout(cooperativeId, payoutId);
        String reference = trim(request.getReference());
        if (reference == null) {
            throw new BusinessException("DISBURSEMENT_REFERENCE_REQUIRED", "The reference of the actual disbursement is required");
        }
        if (payout.getStatus() == RotationPayoutStatus.DISBURSED) {
            if (reference.equals(payout.getDisbursementReference()) && request.getMethod() == payout.getDisbursementMethod()) {
                return toResponse(payout);
            }
            throw new ConflictException("PAYOUT_ALREADY_DISBURSED", "This payout was already disbursed with a different reference");
        }
        payout.getStatus().requireTransitionTo(RotationPayoutStatus.DISBURSED);
        if (actor.getId().equals(payout.getRecipientUserId())) {
            throw new ForbiddenException("A recipient cannot confirm their own disbursement");
        }
        requireStillCovered(payout);

        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        LedgerEntry entry = ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(payout.getRecipientUserId())
                .transactionType(LedgerTransactionType.MEMBER_PAYOUT)
                .debitAmount(payout.getAmount())
                .creditAmount(BigDecimal.ZERO)
                .currency(cooperative.getCurrency())
                .transactionDate(LocalDate.now(KIGALI))
                .reference(reference)
                .sourceEntityType(LedgerService.SOURCE_ROTATION_PAYOUT)
                .sourceEntityId(payout.getId())
                .description("Rotation payout")
                .recordedBy(actor.getId())
                .approvedBy(payout.getApprovedBy())
                .idempotencyKey(LedgerService.rotationPayoutKey(payout.getId()))
                .build());

        if (payout.getRotationTurnId() != null && payout.getArrearsNetted().signum() > 0) {
            payout.setArrearsNetted(applyNetting(payout, actor, reference, cooperative.getCurrency()));
        }
        RotationPayoutStatus previous = payout.getStatus();
        payout.setStatus(RotationPayoutStatus.DISBURSED);
        payout.setDisbursedBy(actor.getId());
        payout.setDisbursedAt(Instant.now());
        payout.setDisbursementMethod(request.getMethod());
        payout.setDisbursementReference(reference);
        payout.setDisbursementNote(trim(request.getNote()));
        payout.setLedgerEntryId(entry.getId());
        payout.setOpenKey(null);
        RotationPayout saved = payoutRepository.saveAndFlush(payout);
        recordEvent(saved, actor, previous, RotationPayoutStatus.DISBURSED, null);
        audit(actor, saved, AuditableAction.ROTATION_PAYOUT_DISBURSE, null, http);
        notifyAfterCommit(saved);
        return toResponse(saved);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PayoutResponse> list(UUID cooperativeId) {
        requireStaff(cooperativeId);
        return payoutRepository.findByCooperativeIdOrderByRequestedAtDesc(cooperativeId).stream().map(this::toResponse).toList();
    }

    /** The recipient's own payouts. */
    @Transactional(readOnly = true)
    public List<PayoutResponse> mine(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        return payoutRepository.findByCooperativeIdAndRecipientUserIdOrderByRequestedAtDesc(cooperativeId, actor.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PayoutResponse get(UUID cooperativeId, UUID payoutId) {
        RotationPayout payout = visible(cooperativeId, payoutId);
        return toResponse(payout);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> events(UUID cooperativeId, UUID payoutId) {
        visible(cooperativeId, payoutId);
        return eventRepository.findByCooperativeIdAndPayoutIdOrderByCreatedAtAsc(cooperativeId, payoutId).stream()
                .map(e -> new EventResponse(e.getId(), e.getFromStatus(), e.getToStatus(), e.getActorRole(), e.getActorUserId(), e.getComment(), e.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    /** Staff, or the recipient themself. */
    private RotationPayout visible(UUID cooperativeId, UUID payoutId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        RotationPayout payout = payoutRepository
                .findByIdAndCooperativeId(payoutId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation payout", payoutId));
        boolean staff = actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) || actor.hasAuthority(PAYOUT_WRITE);
        if (!staff && !actor.getId().equals(payout.getRecipientUserId())) {
            throw new ResourceNotFoundException("Rotation payout", payoutId);
        }
        return payout;
    }

    private UserPrincipal requireStaff(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) && !actor.hasAuthority(PAYOUT_WRITE)) {
            throw new ForbiddenException("You do not have permission to handle rotation payouts");
        }
        return actor;
    }

    private void requireRotationModel(UUID cooperativeId) {
        SchemeOperatingModel model = cooperativeRepository.findOperatingModelById(cooperativeId);
        if (model != SchemeOperatingModel.ROTATING && model != SchemeOperatingModel.HYBRID) {
            throw new BusinessException(
                    "ROTATION_PAYOUT_NOT_APPLICABLE", "Rotation payouts exist only for Rotating and Hybrid saving schemes");
        }
    }

    private RotationPayout lockPayout(UUID cooperativeId, UUID payoutId) {
        return payoutRepository
                .findByIdAndCooperativeIdForUpdate(payoutId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation payout", payoutId));
    }

    /** The collected entitlement and the available funds must still cover the payout; never trust request-time numbers. */
    private void requireStillCovered(RotationPayout payout) {
        UUID cooperativeId = payout.getCooperativeId();
        if (payout.getRotationTurnId() != null) {
            var turn = turnRepository.findByIdAndCooperativeId(payout.getRotationTurnId(), cooperativeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Rotation turn", payout.getRotationTurnId()));
            BigDecimal outstanding = turnOutstanding(cooperativeId, turn, payout.getAmount());
            if (payout.getAmount().compareTo(outstanding) > 0) {
                throw new BusinessException("PAYOUT_EXCEEDS_ENTITLEMENT", "This turn now has only " + outstanding.toPlainString() + " left to pay");
            }
            // The pool already counts this payout as committed; add it back to see whether the pool still covers it.
            BigDecimal pool = poolService.sharePool(cooperativeId).available().add(payout.getAmount());
            if (payout.getAmount().compareTo(pool) > 0) {
                throw new BusinessException("PAYOUT_EXCEEDS_POOL", "The rotation pool now holds only " + pool.toPlainString());
            }
            BigDecimal netted = memberArrears(cooperativeId, payout.getRecipientUserId()).min(payout.getAmount());
            payout.setArrearsNetted(MoneyUtils.scaleForStorage(netted));
            BigDecimal available = availableFunds(cooperativeId);
            if (payout.getAmount().subtract(netted).compareTo(available) > 0) {
                throw new BusinessException(
                        "PAYOUT_EXCEEDS_AVAILABLE",
                        "The scheme's available funds are now " + available.max(BigDecimal.ZERO).toPlainString() + ", less than this payout");
            }
            return;
        }
        // Only rotating money whose receipt was verified can be paid out (never expected or unreconciled amounts).
        // plus the reserve cover of the period's shortfall (opening pool and top-ups), recomputed from the data as it is now
        BigDecimal ceiling = poolService.payoutCeiling(cooperativeId, payout.getRotationPeriodId());
        BigDecimal distributed = MoneyUtils.scaleForStorage(payoutRepository.sumAmount(
                cooperativeId, payout.getRotationPeriodId(), java.util.EnumSet.of(RotationPayoutStatus.DISBURSED)));
        BigDecimal outstanding = ceiling.subtract(distributed);
        if (payout.getAmount().compareTo(outstanding) > 0) {
            throw new BusinessException(
                    "PAYOUT_EXCEEDS_ENTITLEMENT",
                    "The collected, undistributed entitlement is now " + outstanding.max(BigDecimal.ZERO).toPlainString()
                            + ", less than this payout");
        }
        BigDecimal available = availableFunds(cooperativeId);
        if (payout.getAmount().compareTo(available) > 0) {
            throw new BusinessException(
                    "PAYOUT_EXCEEDS_AVAILABLE",
                    "The scheme's available funds are now " + available.max(BigDecimal.ZERO).toPlainString() + ", less than this payout");
        }
    }

    private BigDecimal availableFunds(UUID cooperativeId) {
        return MoneyUtils.scaleForStorage(financialCalculationService.calculateAvailableGroupFund(cooperativeId));
    }

    private void recordEvent(RotationPayout payout, UserPrincipal actor, RotationPayoutStatus from, RotationPayoutStatus to, String comment) {
        eventRepository.save(RotationPayoutEvent.builder()
                .payoutId(payout.getId())
                .cooperativeId(payout.getCooperativeId())
                .actorUserId(actor.getId())
                .actorRole(CooperativeOfficerRoles.displayRole(actor))
                .fromStatus(from)
                .toStatus(to)
                .comment(trim(comment))
                .build());
    }

    private void audit(UserPrincipal actor, RotationPayout payout, AuditableAction action, String comment, HttpServletRequest http) {
        // Only enum/uuid/number values go into the JSON, never free text.
        auditService.record(
                actor.getId(),
                payout.getCooperativeId(),
                action,
                "RotationPayout",
                payout.getId(),
                null,
                "{\"status\":\"" + payout.getStatus() + "\",\"amount\":\"" + payout.getAmount().toPlainString()
                        + "\",\"periodId\":\"" + payout.getRotationPeriodId() + "\"}",
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
    }

    private void notifyAfterCommit(RotationPayout payout) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        UUID recipient = payout.getRecipientUserId();
        UUID cooperativeId = payout.getCooperativeId();
        UUID id = payout.getId();
        String amount = payout.getAmount().toPlainString();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notificationFacade.notifyUser(
                            recipient,
                            cooperativeId,
                            NotificationType.PAYOUT,
                            "Rotation payout disbursed",
                            "A rotation payout of " + amount + " was confirmed as disbursed to you.",
                            "RotationPayout",
                            id);
                } catch (RuntimeException e) {
                    log.warn("Rotation payout notification failed for {}: {}", id, e.getClass().getSimpleName());
                }
            }
        });
    }

    /** Which periods can have a payout requested by the signed-in user right now, each with a stable reason when not. */
    @Transactional(readOnly = true)
    public List<RotationPayoutDtos.PeriodPayoutEligibility> eligibility(UUID cooperativeId) {
        UserPrincipal actor = requireStaffAny(cooperativeId);
        requireRotationModel(cooperativeId);
        boolean authorized = actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) || actor.hasAuthority(PAYOUT_WRITE);
        Map<UUID, BigDecimal> ceilings = poolService.payoutCeilings(cooperativeId);
        Map<UUID, PeriodEntitlement> entitlements = new HashMap<>();
        entitlementService.overview(cooperativeId).forEach(e -> entitlements.put(e.periodId(), e));
        BigDecimal available = availableFunds(cooperativeId);
        java.util.Set<UUID> share = entitlementService.sharePeriodIds(cooperativeId);
        List<RotationPayoutDtos.PeriodPayoutEligibility> result = new ArrayList<>();
        for (RotationPeriod period : periodRepository.findByCooperativeIdOrderByPeriodYearAscPeriodMonthAsc(cooperativeId)) {
            if (share.contains(period.getId())) {
                continue; // paid by turn: see turnEligibility
            }
            PeriodEntitlement e = entitlements.get(period.getId());
            BigDecimal distributed = e == null ? BigDecimal.ZERO : e.distributed();
            BigDecimal headroom = MoneyUtils.scaleForStorage(ceilings.getOrDefault(period.getId(), BigDecimal.ZERO).subtract(distributed));
            String reason = null;
            if (!authorized) {
                reason = "NOT_AUTHORIZED";
            } else if (period.getStatus() != RotationPeriodStatus.SCHEDULED) {
                reason = "PERIOD_NOT_PAYABLE";
            } else if (actor.getId().equals(period.getScheduledRecipientUserId())) {
                reason = "RECIPIENT_CANNOT_REQUEST_OWN";
            } else if (!isActiveMember(cooperativeId, period.getScheduledRecipientUserId())) {
                reason = "RECIPIENT_NOT_ACTIVE";
            } else if (payoutRepository.sumAmount(cooperativeId, period.getId(), EnumSet.of(RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED)).signum() > 0) {
                reason = "PAYOUT_ALREADY_OPEN";
            } else if (headroom.signum() <= 0) {
                reason = distributed.signum() > 0 ? "PAYOUT_ALREADY_COMPLETED" : "NO_COLLECTED_FUNDS";
            } else if (available.signum() <= 0) {
                reason = "INSUFFICIENT_AVAILABLE_FUNDS";
            }
            result.add(new RotationPayoutDtos.PeriodPayoutEligibility(
                    period.getId(),
                    period.getPeriodYear(),
                    period.getPeriodMonth(),
                    period.getScheduledRecipientUserId(),
                    reason == null,
                    reason,
                    reason == null ? headroom.min(available) : MoneyUtils.scaleForStorage(BigDecimal.ZERO)));
        }
        return result;
    }

    private boolean isActiveMember(UUID cooperativeId, UUID userId) {
        return membershipRepository.findByCooperativeIdAndUserId(cooperativeId, userId)
                .map(m -> "ACTIVE".equals(m.getMembershipStatus()))
                .orElse(false);
    }

    private UserPrincipal requireStaffAny(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        return authorizationService.currentPrincipal();
    }

    private RotationPayoutDtos.PayoutActions actionsFor(RotationPayout p) {
        UserPrincipal actor = authorizationService.currentPrincipal();
        boolean superAdmin = actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);
        boolean writer = superAdmin || actor.hasAuthority(PAYOUT_WRITE);
        boolean authorizer = superAdmin || actor.hasAuthority("FUND_AUTHORIZE");
        boolean recipient = actor.getId().equals(p.getRecipientUserId());
        String approve = null;
        if (p.getStatus() != RotationPayoutStatus.REQUESTED) {
            approve = "NOT_AWAITING_APPROVAL";
        } else if (!authorizer) {
            approve = "NOT_AUTHORIZED";
        } else if (actor.getId().equals(p.getRequestedBy())) {
            approve = "REQUESTER_CANNOT_APPROVE";
        } else if (recipient) {
            approve = "RECIPIENT_CANNOT_APPROVE";
        }
        String confirm = null;
        if (p.getStatus() != RotationPayoutStatus.APPROVED) {
            confirm = "NOT_APPROVED";
        } else if (!writer) {
            confirm = "NOT_AUTHORIZED";
        } else if (recipient) {
            confirm = "RECIPIENT_CANNOT_CONFIRM";
        }
        String cancel = null;
        if (p.getStatus() != RotationPayoutStatus.REQUESTED && p.getStatus() != RotationPayoutStatus.APPROVED) {
            cancel = p.getStatus() == RotationPayoutStatus.DISBURSED ? "ALREADY_DISBURSED" : "NOT_OPEN";
        } else if (!writer) {
            cancel = "NOT_AUTHORIZED";
        } else if (recipient) {
            cancel = "RECIPIENT_CANNOT_CANCEL";
        }
        return new RotationPayoutDtos.PayoutActions(approve == null, approve, confirm == null, confirm, cancel == null, cancel);
    }

    private PayoutResponse toResponse(RotationPayout p) {
        RotationPeriod period = periodRepository.findByIdAndCooperativeId(p.getRotationPeriodId(), p.getCooperativeId()).orElse(null);
        String name = userRepository.findById(p.getRecipientUserId()).map(User::getFullName).orElse("");
        BigDecimal remaining = MoneyUtils.scaleForStorage(p.getEntitlementAtRequest().subtract(p.getAmount()));
        return new PayoutResponse(
                p.getId(),
                p.getRotationPeriodId(),
                period == null ? 0 : period.getPeriodYear(),
                period == null ? 0 : period.getPeriodMonth(),
                p.getRecipientUserId(),
                name,
                p.getAmount(),
                p.getEntitlementAtRequest(),
                p.getAvailableAtRequest(),
                p.getAmount().compareTo(p.getEntitlementAtRequest()) < 0,
                remaining,
                p.getStatus(),
                p.getNotes(),
                p.getRequestedBy(),
                p.getRequestedAt(),
                p.getApprovedBy(),
                p.getApprovedAt(),
                p.getRejectionReason(),
                p.getCancelReason(),
                p.getDisbursedBy(),
                p.getDisbursedAt(),
                p.getDisbursementMethod(),
                p.getDisbursementReference(),
                p.getDisbursementNote(),
                actionsFor(p),
                p.getRotationTurnId(),
                MoneyUtils.scaleForStorage(p.getArrearsNetted()),
                MoneyUtils.scaleForStorage(p.getAmount().subtract(p.getArrearsNetted())));
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
