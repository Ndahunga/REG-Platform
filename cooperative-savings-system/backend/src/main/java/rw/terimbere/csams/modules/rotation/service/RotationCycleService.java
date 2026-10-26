package rw.terimbere.csams.modules.rotation.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.CancelRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.CycleCreateRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.CycleResponse;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.PeriodResponse;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.RecipientChangeRequest;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.RecipientChangeResponse;
import rw.terimbere.csams.modules.rotation.entity.RotationCycle;
import rw.terimbere.csams.modules.rotation.entity.RotationCycleStatus;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriodStatus;
import rw.terimbere.csams.modules.rotation.entity.RotationRecipientChange;
import rw.terimbere.csams.modules.rotation.repository.RotationCycleRepository;
import rw.terimbere.csams.modules.rotation.repository.RotationPeriodRepository;
import rw.terimbere.csams.modules.rotation.repository.RotationRecipientChangeRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Rotation cycles, the monthly schedule and recipient assignments. Storage only: no obligation, arrear, distribution or
 * ledger entry is created or read here.
 *
 * <p>Lifecycle: DRAFT (holds no months) to ACTIVE (holds its months) or CANCELLED (releases them, rows kept). A cycle
 * and its recipients can only be created, activated, cancelled or changed while the affected months have not begun.
 * Every mutation takes the scheme fund lock so it serializes with future distribution work, and the schema's unique
 * keys back every check, so a race can never leave two live periods in one month or two turns for one member.
 */
@Service
@RequiredArgsConstructor
public class RotationCycleService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final RotationAccess access;
    private final RotationConfigurationService configurationService;
    private final RotationCycleRepository cycleRepository;
    private final RotationPeriodRepository periodRepository;
    private final RotationRecipientChangeRepository changeRepository;
    private final UserRepository userRepository;
    private final SchemeFundLockService fundLockService;
    private final AuditService auditService;
    private final Clock clock;
    private final rw.terimbere.csams.modules.rotation.repository.RotationTurnRepository turnRepository;
    private final rw.terimbere.csams.modules.contribution.obligation.ContributionObligationService obligationService;
    private final rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository membershipRepository;
    private final rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository cooperativeRepository;
    private final rw.terimbere.csams.modules.rotation.payout.RotationPayoutRepository payoutRepository;

    // ------------------------------------------------------------------ commands

    @Transactional
    public CycleResponse create(
            UUID cooperativeId, CycleCreateRequest request, String idempotencyKey, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        access.requireRotationModel(cooperativeId);
        String key = cleanKey(idempotencyKey);

        List<UUID> recipients = request.recipientUserIds();
        if (new HashSet<>(recipients).size() != recipients.size()) {
            throw new ValidationException(
                    "Each member can take only one turn in a cycle",
                    Map.of("recipientUserIds", "A member appears more than once in the recipient order"));
        }
        YearMonth start = YearMonth.of(request.startYear(), request.startMonth());
        if (access.hasBegun(start)) {
            throw new BusinessException(
                    "ROTATION_PERIOD_STARTED", "A cycle can only start in a month that has not begun yet");
        }
        access.requireActiveMembers(cooperativeId, recipients, "recipientUserIds");

        fundLockService.lockSchemeFund(cooperativeId);
        if (key != null) {
            var replay = cycleRepository.findByCooperativeIdAndIdempotencyKey(cooperativeId, key);
            if (replay.isPresent()) {
                return replayOf(replay.get(), start, recipients);
            }
        }

        RotationCycle cycle = cycleRepository.saveAndFlush(RotationCycle.builder()
                .cooperativeId(cooperativeId)
                .cycleNumber(cycleRepository.maxCycleNumber(cooperativeId) + 1)
                .startYear(start.getYear())
                .startMonth(start.getMonthValue())
                .periodCount(recipients.size())
                .status(RotationCycleStatus.DRAFT)
                .idempotencyKey(key)
                .createdBy(principal.getId())
                .build());
        List<RotationPeriod> periods = new ArrayList<>();
        for (int i = 0; i < recipients.size(); i++) {
            YearMonth month = start.plusMonths(i);
            periods.add(RotationPeriod.builder()
                    .cooperativeId(cooperativeId)
                    .cycleId(cycle.getId())
                    .sequenceNumber(i + 1)
                    .periodYear(month.getYear())
                    .periodMonth(month.getMonthValue())
                    .scheduledRecipientUserId(recipients.get(i))
                    .status(RotationPeriodStatus.SCHEDULED)
                    .slotKey(null) // a draft holds no month
                    .build());
        }
        periodRepository.saveAllAndFlush(periods);
        audit(principal, cooperativeId, AuditableAction.ROTATION_CYCLE_CREATE, cycle, null,
                "{\"status\":\"DRAFT\",\"cycleNumber\":" + cycle.getCycleNumber() + ",\"start\":\"" + start
                        + "\",\"periods\":" + periods.size() + "}", http);
        return toResponse(cycle, periods, true);
    }

    @Transactional
    public CycleResponse activate(UUID cooperativeId, UUID cycleId, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        access.requireRotationModel(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);

        RotationCycle cycle = requireCycle(cooperativeId, cycleId);
        if (cycle.getStatus() != RotationCycleStatus.DRAFT) {
            throw new ConflictException("ROTATION_CYCLE_STATE", "Only a draft cycle can be activated");
        }
        List<RotationPeriod> periods =
                periodRepository.findByCycleIdAndCooperativeIdOrderBySequenceNumberAsc(cycleId, cooperativeId);
        YearMonth start = YearMonth.of(cycle.getStartYear(), cycle.getStartMonth());
        if (access.hasBegun(start)) {
            throw new BusinessException(
                    "ROTATION_PERIOD_STARTED", "A cycle can only be activated before its first month begins");
        }
        if (configurationService.resolve(cooperativeId, cycle.getStartYear(), cycle.getStartMonth()).isEmpty()) {
            throw new BusinessException(
                    "ROTATION_CONFIG_MISSING",
                    "No rotation configuration is effective for the first month of this cycle");
        }
        access.requireActiveMembers(
                cooperativeId,
                periods.stream().map(RotationPeriod::getScheduledRecipientUserId).toList(),
                "recipientUserIds");

        Set<String> keys = periods.stream().map(RotationCycleService::slotKey).collect(Collectors.toSet());
        if (!periodRepository.findByCooperativeIdAndSlotKeyIn(cooperativeId, keys).isEmpty()) {
            throw new ConflictException(
                    "ROTATION_SCHEDULE_OVERLAP",
                    "Another active cycle already holds one of these months; cancel it first");
        }
        try {
            for (RotationPeriod period : periods) {
                period.setSlotKey(slotKey(period));
            }
            periodRepository.saveAllAndFlush(periods);
        } catch (DataIntegrityViolationException e) {
            // Backstop: the unique live-slot key refused an overlap that the check above could not see.
            throw new ConflictException(
                    "ROTATION_SCHEDULE_OVERLAP", "Another active cycle already holds one of these months");
        }
        cycle.setStatus(RotationCycleStatus.ACTIVE);
        cycle.setActivatedBy(principal.getId());
        cycle.setActivatedAt(Instant.now(clock));
        cycleRepository.saveAndFlush(cycle);
        // A share-based cycle is owed in full from the start: every month's obligation exists now, so a member paid
        // early sees each month still to contribute. (Period-collection cycles keep their month-by-month obligations.)
        if (cycle.isShareBased()) {
            obligationService.materializeMonths(
                    cooperativeId,
                    periods.stream().map(p -> YearMonth.of(p.getPeriodYear(), p.getPeriodMonth())).toList(),
                    principal.getId());
        }
        audit(principal, cooperativeId, AuditableAction.ROTATION_CYCLE_ACTIVATE, cycle,
                "{\"status\":\"DRAFT\"}", "{\"status\":\"ACTIVE\"}", http);
        return toResponse(cycle, periods, true);
    }

    @Transactional
    public CycleResponse cancel(UUID cooperativeId, UUID cycleId, CancelRequest request, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        access.requireRotationModel(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);

        RotationCycle cycle = requireCycle(cooperativeId, cycleId);
        if (cycle.getStatus() != RotationCycleStatus.DRAFT && cycle.getStatus() != RotationCycleStatus.ACTIVE) {
            throw new ConflictException("ROTATION_CYCLE_STATE", "Only a draft or active cycle can be cancelled");
        }
        List<RotationPeriod> periods =
                periodRepository.findByCycleIdAndCooperativeIdOrderBySequenceNumberAsc(cycleId, cooperativeId);
        if (access.hasBegun(YearMonth.of(cycle.getStartYear(), cycle.getStartMonth()))) {
            throw new BusinessException(
                    "ROTATION_PERIOD_STARTED",
                    "A cycle whose first month has begun cannot be cancelled here; it needs a separately approved correction");
        }
        String previous = cycle.getStatus().name();
        for (RotationPeriod period : periods) {
            period.setStatus(RotationPeriodStatus.CANCELLED);
            period.setSlotKey(null); // releases the month; the row stays as history
        }
        periodRepository.saveAllAndFlush(periods);
        cycle.setStatus(RotationCycleStatus.CANCELLED);
        cycle.setCancelledBy(principal.getId());
        cycle.setCancelledAt(Instant.now(clock));
        cycle.setCancelReason(request.reason().trim());
        cycleRepository.saveAndFlush(cycle);
        audit(principal, cooperativeId, AuditableAction.ROTATION_CYCLE_CANCEL, cycle,
                "{\"status\":\"" + previous + "\"}", "{\"status\":\"CANCELLED\"}", http);
        return toResponse(cycle, periods, true);
    }

    @Transactional
    public PeriodResponse changeRecipient(
            UUID cooperativeId, UUID periodId, RecipientChangeRequest request, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        access.requireRotationModel(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);

        RotationPeriod period = periodRepository
                .findByIdAndCooperativeId(periodId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", periodId));
        RotationCycle cycle = requireCycle(cooperativeId, period.getCycleId());
        if (cycle.isShareBased()) {
            throw new BusinessException(
                    "ROTATION_USE_TURNS", "This cycle pays by share: reassign the member's turn instead of the month");
        }
        if (period.getStatus() != RotationPeriodStatus.SCHEDULED
                || (cycle.getStatus() != RotationCycleStatus.DRAFT && cycle.getStatus() != RotationCycleStatus.ACTIVE)) {
            throw new BusinessException(
                    "ROTATION_PERIOD_NOT_EDITABLE", "This period belongs to a cancelled or completed cycle");
        }
        if (access.hasBegun(YearMonth.of(period.getPeriodYear(), period.getPeriodMonth()))) {
            throw new BusinessException(
                    "ROTATION_PERIOD_STARTED", "A period that has begun cannot be reassigned");
        }
        UUID previous = period.getScheduledRecipientUserId();
        UUID next = request.recipientUserId();
        if (previous.equals(next)) {
            throw new ValidationException(
                    "The new recipient is already the scheduled recipient",
                    Map.of("recipientUserId", "Choose a different member"));
        }
        access.requireActiveMembers(cooperativeId, List.of(next), "recipientUserId");
        if (periodRepository.existsByCycleIdAndScheduledRecipientUserId(cycle.getId(), next)) {
            throw new ConflictException(
                    "ROTATION_RECIPIENT_DUPLICATE", "That member already has a turn in this cycle");
        }

        changeRepository.save(RotationRecipientChange.builder()
                .cooperativeId(cooperativeId)
                .rotationPeriodId(period.getId())
                .previousRecipientUserId(previous)
                .newRecipientUserId(next)
                .reason(request.reason().trim())
                .changedBy(principal.getId())
                .changedAt(Instant.now(clock))
                .build());
        period.setScheduledRecipientUserId(next);
        periodRepository.saveAndFlush(period);
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.ROTATION_RECIPIENT_CHANGE,
                "RotationPeriod",
                period.getId(),
                "{\"recipient\":\"" + previous + "\"}",
                "{\"recipient\":\"" + next + "\",\"reason\":\"" + escape(request.reason().trim()) + "\"}",
                RotationAccess.clientIp(http),
                RotationAccess.userAgent(http));
        return toPeriodResponse(period, names(List.of(next)));
    }


    // ------------------------------------------------------------------ share-based cycles

    /**
     * Creates a draft share-based cycle: {@code months} monthly slots, each holding one or more turns. Each member
     * should have as many share-turns as shares held; the funding check in the response says when the turns and the
     * rotated contributions do not balance.
     */
    @Transactional
    public CycleResponse createShareCycle(
            UUID cooperativeId, rw.terimbere.csams.modules.rotation.dto.RotationDtos.ShareCycleCreateRequest request,
            String idempotencyKey, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        access.requireRotationModel(cooperativeId);
        YearMonth start = YearMonth.of(request.startYear(), request.startMonth());
        if (access.hasBegun(start)) {
            throw new BusinessException(
                    "ROTATION_PERIOD_STARTED", "A cycle can only start in a month that has not begun yet");
        }
        for (var t : request.turns()) {
            if (t.openingReceivedAmount() != null && t.openingReceivedAmount().signum() > 0) {
                throw new ValidationException(
                        "Amounts already received belong to a migration import",
                        Map.of("turns", "openingReceivedAmount is only accepted when importing a running cycle"));
            }
        }
        String key = cleanKey(idempotencyKey);
        fundLockService.lockSchemeFund(cooperativeId);
        if (key != null) {
            var replay = cycleRepository.findByCooperativeIdAndIdempotencyKey(cooperativeId, key);
            if (replay.isPresent()) {
                return get(cooperativeId, replay.get().getId());
            }
        }
        RotationCycle cycle = buildShareCycle(cooperativeId, request, key, principal.getId(), false);
        audit(principal, cooperativeId, AuditableAction.ROTATION_CYCLE_CREATE, cycle, null,
                "{\"status\":\"DRAFT\",\"payoutMode\":\"FIXED_PER_SHARE\",\"start\":\"" + start
                        + "\",\"months\":" + request.months() + "}", http);
        return get(cooperativeId, cycle.getId());
    }

    /**
     * Imports a share-based cycle that was already running before the scheme moved to the platform (migration). Its
     * start may be in the past; turns may carry what was already received. The cycle is activated at once and the
     * obligations of the remaining months (from the current month) are created. Caller authorizes and locks.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RotationCycle importShareCycle(
            UUID cooperativeId, rw.terimbere.csams.modules.rotation.dto.RotationDtos.ShareCycleCreateRequest request,
            UUID actorId) {
        RotationCycle cycle = buildShareCycle(cooperativeId, request, null, actorId, true);
        List<RotationPeriod> periods =
                periodRepository.findByCycleIdAndCooperativeIdOrderBySequenceNumberAsc(cycle.getId(), cooperativeId);
        Set<String> keys = periods.stream().map(RotationCycleService::slotKey).collect(Collectors.toSet());
        if (!periodRepository.findByCooperativeIdAndSlotKeyIn(cooperativeId, keys).isEmpty()) {
            throw new ConflictException(
                    "ROTATION_SCHEDULE_OVERLAP", "Another active cycle already holds one of these months; cancel it first");
        }
        for (RotationPeriod period : periods) {
            period.setSlotKey(slotKey(period));
        }
        periodRepository.saveAllAndFlush(periods);
        cycle.setStatus(RotationCycleStatus.ACTIVE);
        cycle.setActivatedBy(actorId);
        cycle.setActivatedAt(Instant.now(clock));
        cycleRepository.saveAndFlush(cycle);
        obligationService.materializeMonths(
                cooperativeId,
                periods.stream().map(p -> YearMonth.of(p.getPeriodYear(), p.getPeriodMonth())).toList(),
                actorId);
        return cycle;
    }

    private RotationCycle buildShareCycle(
            UUID cooperativeId, rw.terimbere.csams.modules.rotation.dto.RotationDtos.ShareCycleCreateRequest request,
            String key, UUID actorId, boolean imported) {
        int months = request.months();
        var mode = request.turnMode() == null
                ? rw.terimbere.csams.modules.rotation.entity.RotationTurnMode.ONE_SHARE_PER_TURN
                : request.turnMode();
        java.math.BigDecimal perShare = rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(request.payoutPerShare());
        YearMonth start = YearMonth.of(request.startYear(), request.startMonth());
        YearMonth current = access.currentMonth();

        Map<Integer, List<rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnInput>> byMonth = new java.util.TreeMap<>();
        Map<UUID, Integer> sharesPerMember = new HashMap<>();
        Map<UUID, Integer> turnsPerMember = new HashMap<>();
        for (var t : request.turns()) {
            if (t.monthIndex() > months) {
                throw new ValidationException(
                        "A turn is placed after the last month of the cycle",
                        Map.of("turns", "Month " + t.monthIndex() + " is after month " + months));
            }
            byMonth.computeIfAbsent(t.monthIndex(), k -> new ArrayList<>()).add(t);
            sharesPerMember.merge(t.memberUserId(), t.shares(), Integer::sum);
            turnsPerMember.merge(t.memberUserId(), 1, Integer::sum);
            if (mode == rw.terimbere.csams.modules.rotation.entity.RotationTurnMode.ONE_SHARE_PER_TURN && t.shares() != 1) {
                throw new ValidationException(
                        "In one-share-per-turn mode each turn is one share",
                        Map.of("turns", "Split the member's shares into separate turns, or choose another turn mode"));
            }
            java.math.BigDecimal received = t.openingReceivedAmount() == null ? java.math.BigDecimal.ZERO : t.openingReceivedAmount();
            if (received.signum() > 0) {
                YearMonth turnMonth = start.plusMonths(t.monthIndex() - 1L);
                if (turnMonth.isAfter(current)) {
                    throw new ValidationException(
                            "A turn in a future month cannot have been received already",
                            Map.of("turns", "Month " + turnMonth + " has not begun"));
                }
                if (received.compareTo(perShare.multiply(java.math.BigDecimal.valueOf(t.shares()))) > 0) {
                    throw new ValidationException(
                            "More was received than the turn pays",
                            Map.of("turns", "At most " + perShare.multiply(java.math.BigDecimal.valueOf(t.shares())).toPlainString()));
                }
            }
        }
        for (int m = 1; m <= months; m++) {
            List<rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnInput> inMonth = byMonth.get(m);
            if (inMonth == null || inMonth.isEmpty()) {
                throw new ValidationException(
                        "Every month of the cycle needs at least one turn",
                        Map.of("turns", "Month " + m + " (" + start.plusMonths(m - 1L) + ") has no recipient"));
            }
            if (inMonth.stream().map(rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnInput::memberUserId).distinct().count() != inMonth.size()) {
                throw new ValidationException(
                        "A member can appear once per month",
                        Map.of("turns", "Month " + m + " lists a member twice: merge the shares into one turn"));
            }
        }
        if (mode == rw.terimbere.csams.modules.rotation.entity.RotationTurnMode.ALL_SHARES_TOGETHER) {
            for (var e : turnsPerMember.entrySet()) {
                if (e.getValue() > 1) {
                    throw new ValidationException(
                            "In all-shares-together mode a member has one turn",
                            Map.of("turns", "A member has " + e.getValue() + " turns"));
                }
            }
        }
        access.requireActiveMembers(cooperativeId, sharesPerMember.keySet(), "turns");
        Map<UUID, Integer> held = heldShares(cooperativeId);
        for (var e : sharesPerMember.entrySet()) {
            int h = held.getOrDefault(e.getKey(), 0);
            if (e.getValue() > h) {
                throw new ValidationException(
                        "A member has more share-turns than shares",
                        Map.of("turns", nameOf(e.getKey()) + " holds " + h + " share(s) but has " + e.getValue() + " in the cycle"));
            }
        }

        RotationCycle cycle = cycleRepository.saveAndFlush(RotationCycle.builder()
                .cooperativeId(cooperativeId)
                .cycleNumber(cycleRepository.maxCycleNumber(cooperativeId) + 1)
                .startYear(start.getYear())
                .startMonth(start.getMonthValue())
                .periodCount(months)
                .status(RotationCycleStatus.DRAFT)
                .idempotencyKey(key)
                .createdBy(actorId)
                .payoutMode(rw.terimbere.csams.modules.rotation.entity.RotationPayoutMode.FIXED_PER_SHARE)
                .payoutPerShare(perShare)
                .turnMode(mode)
                .build());
        List<RotationPeriod> periods = new ArrayList<>();
        for (int m = 1; m <= months; m++) {
            YearMonth month = start.plusMonths(m - 1L);
            periods.add(RotationPeriod.builder()
                    .cooperativeId(cooperativeId)
                    .cycleId(cycle.getId())
                    .sequenceNumber(m)
                    .periodYear(month.getYear())
                    .periodMonth(month.getMonthValue())
                    .scheduledRecipientUserId(byMonth.get(m).get(0).memberUserId())
                    .status(RotationPeriodStatus.SCHEDULED)
                    .slotKey(null)
                    .build());
        }
        periodRepository.saveAllAndFlush(periods);
        List<rw.terimbere.csams.modules.rotation.entity.RotationTurn> turns = new ArrayList<>();
        for (int m = 1; m <= months; m++) {
            RotationPeriod period = periods.get(m - 1);
            for (var t : byMonth.get(m)) {
                java.math.BigDecimal received = t.openingReceivedAmount() == null ? java.math.BigDecimal.ZERO
                        : rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(t.openingReceivedAmount());
                turns.add(rw.terimbere.csams.modules.rotation.entity.RotationTurn.builder()
                        .cooperativeId(cooperativeId)
                        .cycleId(cycle.getId())
                        .rotationPeriodId(period.getId())
                        .memberUserId(t.memberUserId())
                        .shareCount(t.shares())
                        .payoutAmount(perShare.multiply(java.math.BigDecimal.valueOf(t.shares())))
                        .openingReceivedAmount(imported ? received : java.math.BigDecimal.ZERO)
                        .openingReceivedDate(imported && received.signum() > 0
                                ? (t.openingReceivedDate() == null
                                        ? YearMonth.of(period.getPeriodYear(), period.getPeriodMonth()).atEndOfMonth()
                                        : t.openingReceivedDate())
                                : null)
                        .build());
            }
        }
        turnRepository.saveAllAndFlush(turns);
        return cycle;
    }

    /** Moves a turn to another member before its month begins (e.g. two members swap). Reason required, audited. */
    @Transactional
    public CycleResponse reassignTurn(
            UUID cooperativeId, UUID turnId, rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnReassignRequest request,
            HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        access.requireRotationModel(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);
        var turn = turnRepository.findByIdAndCooperativeId(turnId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation turn", turnId));
        RotationCycle cycle = requireCycle(cooperativeId, turn.getCycleId());
        RotationPeriod period = periodRepository.findByIdAndCooperativeId(turn.getRotationPeriodId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", turn.getRotationPeriodId()));
        if (cycle.getStatus() != RotationCycleStatus.DRAFT && cycle.getStatus() != RotationCycleStatus.ACTIVE) {
            throw new BusinessException("ROTATION_PERIOD_NOT_EDITABLE", "This turn belongs to a cancelled or completed cycle");
        }
        if (cycle.getStatus() == RotationCycleStatus.ACTIVE
                && access.hasBegun(YearMonth.of(period.getPeriodYear(), period.getPeriodMonth()))) {
            throw new BusinessException("ROTATION_PERIOD_STARTED", "A turn whose month has begun cannot be reassigned");
        }
        if (payoutRepository.findByCooperativeIdAndRotationTurnId(cooperativeId, turnId).stream()
                .anyMatch(p -> p.getStatus() != rw.terimbere.csams.modules.rotation.payout.RotationPayoutStatus.REJECTED
                        && p.getStatus() != rw.terimbere.csams.modules.rotation.payout.RotationPayoutStatus.CANCELLED)) {
            throw new BusinessException("ROTATION_TURN_PAID", "This turn already has a payout");
        }
        UUID previous = turn.getMemberUserId();
        UUID next = request.memberUserId();
        if (previous.equals(next)) {
            throw new ValidationException("Choose a different member", Map.of("memberUserId", "Already this member's turn"));
        }
        access.requireActiveMembers(cooperativeId, List.of(next), "memberUserId");
        if (turnRepository.findByCooperativeIdAndRotationPeriodId(cooperativeId, period.getId()).stream()
                .anyMatch(t -> t.getMemberUserId().equals(next))) {
            throw new ConflictException("ROTATION_RECIPIENT_DUPLICATE", "That member already has a turn in this month");
        }
        int already = turnRepository.findByCooperativeIdAndCycleId(cooperativeId, cycle.getId()).stream()
                .filter(t -> t.getMemberUserId().equals(next))
                .mapToInt(rw.terimbere.csams.modules.rotation.entity.RotationTurn::getShareCount)
                .sum();
        if (already + turn.getShareCount() > heldShares(cooperativeId).getOrDefault(next, 0)) {
            throw new ValidationException(
                    "The member would have more share-turns than shares",
                    Map.of("memberUserId", "Not enough shares for another turn"));
        }
        turn.setMemberUserId(next);
        turnRepository.saveAndFlush(turn);
        if (period.getScheduledRecipientUserId().equals(previous)) {
            changeRepository.save(RotationRecipientChange.builder()
                    .cooperativeId(cooperativeId)
                    .rotationPeriodId(period.getId())
                    .previousRecipientUserId(previous)
                    .newRecipientUserId(next)
                    .reason(request.reason().trim())
                    .changedBy(principal.getId())
                    .changedAt(Instant.now(clock))
                    .build());
            period.setScheduledRecipientUserId(next);
            periodRepository.saveAndFlush(period);
        }
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.ROTATION_RECIPIENT_CHANGE,
                "RotationTurn",
                turn.getId(),
                "{\"member\":\"" + previous + "\"}",
                "{\"member\":\"" + next + "\",\"reason\":\"" + escape(request.reason().trim()) + "\"}",
                RotationAccess.clientIp(http),
                RotationAccess.userAgent(http));
        return get(cooperativeId, cycle.getId());
    }

    private Map<UUID, Integer> heldShares(UUID cooperativeId) {
        Map<UUID, Integer> held = new HashMap<>();
        for (var m : membershipRepository.findByCooperativeIdAndMembershipStatus(cooperativeId, "ACTIVE")) {
            held.put(m.getUserId(), m.getShareCount() == null ? 0 : m.getShareCount());
        }
        return held;
    }

    private String nameOf(UUID userId) {
        return names(List.of(userId)).getOrDefault(userId, userId.toString());
    }

    // ------------------------------------------------------------------ queries (no lock)

    /** Officers see every cycle; other members see only active and completed ones (drafts and cancelled are internal). */
    @Transactional(readOnly = true)
    public List<CycleResponse> list(UUID cooperativeId) {
        UserPrincipal principal = access.requireRead(cooperativeId);
        boolean manager = access.canManage(principal, cooperativeId);
        List<RotationCycle> cycles = cycleRepository.findByCooperativeIdOrderByCycleNumberDesc(cooperativeId).stream()
                .filter(c -> manager || isVisibleToMembers(c))
                .toList();
        if (cycles.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<RotationPeriod>> byCycle = periodRepository
                .findByCooperativeIdAndCycleIdInOrderByCycleIdAscSequenceNumberAsc(
                        cooperativeId, cycles.stream().map(RotationCycle::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(RotationPeriod::getCycleId));
        Map<UUID, String> names = names(byCycle.values().stream()
                .flatMap(List::stream)
                .map(RotationPeriod::getScheduledRecipientUserId)
                .toList());
        return cycles.stream()
                .map(c -> toResponse(c, byCycle.getOrDefault(c.getId(), List.of()), names))
                .toList();
    }

    @Transactional(readOnly = true)
    public CycleResponse get(UUID cooperativeId, UUID cycleId) {
        UserPrincipal principal = access.requireRead(cooperativeId);
        RotationCycle cycle = requireCycle(cooperativeId, cycleId);
        if (!access.canManage(principal, cooperativeId) && !isVisibleToMembers(cycle)) {
            throw new ResourceNotFoundException("Rotation cycle", cycleId);
        }
        return toResponse(
                cycle,
                periodRepository.findByCycleIdAndCooperativeIdOrderBySequenceNumberAsc(cycleId, cooperativeId),
                true);
    }

    /** The live period (and scheduled recipient) holding a month, if any. */
    @Transactional(readOnly = true)
    public List<PeriodResponse> periodsForMonth(UUID cooperativeId, int year, int month) {
        access.requireRead(cooperativeId);
        List<RotationPeriod> live = periodRepository.findByCooperativeIdAndSlotKeyIn(
                cooperativeId, List.of(slotKey(year, month)));
        Map<UUID, String> names = names(live.stream().map(RotationPeriod::getScheduledRecipientUserId).toList());
        return live.stream().map(p -> toPeriodResponse(p, names)).toList();
    }

    /** Full reassignment history of a period; visible to members of the scheme. */
    @Transactional(readOnly = true)
    public List<RecipientChangeResponse> recipientChanges(UUID cooperativeId, UUID periodId) {
        UserPrincipal principal = access.requireRead(cooperativeId);
        RotationPeriod period = periodRepository
                .findByIdAndCooperativeId(periodId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", periodId));
        if (!access.canManage(principal, cooperativeId)
                && requireCycle(cooperativeId, period.getCycleId()).getStatus() != RotationCycleStatus.ACTIVE
                && requireCycle(cooperativeId, period.getCycleId()).getStatus() != RotationCycleStatus.COMPLETED) {
            throw new ResourceNotFoundException("Rotation period", periodId);
        }
        List<RotationRecipientChange> changes =
                changeRepository.findByRotationPeriodIdAndCooperativeIdOrderByChangedAtAsc(periodId, cooperativeId);
        List<UUID> ids = new ArrayList<>();
        changes.forEach(c -> {
            ids.add(c.getPreviousRecipientUserId());
            ids.add(c.getNewRecipientUserId());
        });
        Map<UUID, String> names = names(ids);
        return changes.stream()
                .map(c -> new RecipientChangeResponse(
                        c.getId(),
                        c.getRotationPeriodId(),
                        c.getPreviousRecipientUserId(),
                        names.get(c.getPreviousRecipientUserId()),
                        c.getNewRecipientUserId(),
                        names.get(c.getNewRecipientUserId()),
                        c.getReason(),
                        c.getChangedBy(),
                        c.getChangedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private RotationCycle requireCycle(UUID cooperativeId, UUID cycleId) {
        return cycleRepository
                .findByIdAndCooperativeId(cycleId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation cycle", cycleId));
    }

    private static boolean isVisibleToMembers(RotationCycle cycle) {
        return cycle.getStatus() == RotationCycleStatus.ACTIVE || cycle.getStatus() == RotationCycleStatus.COMPLETED;
    }

    /** A retry with the same key returns the original cycle; the same key with a different request is refused. */
    private CycleResponse replayOf(RotationCycle existing, YearMonth start, List<UUID> recipients) {
        List<RotationPeriod> periods = periodRepository.findByCycleIdAndCooperativeIdOrderBySequenceNumberAsc(
                existing.getId(), existing.getCooperativeId());
        boolean same = existing.getStartYear() == start.getYear()
                && existing.getStartMonth() == start.getMonthValue()
                && periods.stream().map(RotationPeriod::getScheduledRecipientUserId).toList().equals(recipients);
        if (!same) {
            throw new ConflictException(
                    "ROTATION_IDEMPOTENCY_MISMATCH", "That Idempotency-Key was already used for a different cycle");
        }
        return toResponse(existing, periods, true);
    }

    private static String cleanKey(String header) {
        if (header == null || header.isBlank()) {
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

    private static String slotKey(RotationPeriod p) {
        return slotKey(p.getPeriodYear(), p.getPeriodMonth());
    }

    private static String slotKey(int year, int month) {
        return "%04d-%02d".formatted(year, month);
    }

    private void audit(
            UserPrincipal principal,
            UUID cooperativeId,
            AuditableAction action,
            RotationCycle cycle,
            String previous,
            String next,
            HttpServletRequest http) {
        auditService.record(
                principal.getId(),
                cooperativeId,
                action,
                "RotationCycle",
                cycle.getId(),
                previous,
                next,
                RotationAccess.clientIp(http),
                RotationAccess.userAgent(http));
    }

    private Map<UUID, String> names(List<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(userIds.stream().distinct().toList())) {
            names.put(user.getId(), (user.getFirstName() + " " + user.getLastName()).trim());
        }
        return names;
    }

    private CycleResponse toResponse(RotationCycle cycle, List<RotationPeriod> periods, boolean loadNames) {
        return toResponse(
                cycle,
                periods,
                names(periods.stream().map(RotationPeriod::getScheduledRecipientUserId).toList()));
    }

    private CycleResponse toResponse(RotationCycle cycle, List<RotationPeriod> periods, Map<UUID, String> names) {
        List<rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse> turns = List.of();
        rw.terimbere.csams.modules.rotation.dto.RotationDtos.FundingCheck check = null;
        if (cycle.isShareBased()) {
            turns = turnsOf(cycle, periods);
            check = fundingCheck(cycle, turns);
        }
        return new CycleResponse(
                cycle.getId(),
                cycle.getCycleNumber(),
                cycle.getStartYear(),
                cycle.getStartMonth(),
                cycle.getPeriodCount(),
                cycle.getStatus(),
                cycle.getCreatedAt(),
                cycle.getActivatedAt(),
                cycle.getCancelledAt(),
                cycle.getCancelReason(),
                periods.stream().map(p -> toPeriodResponse(p, names)).toList(),
                cycle.getPayoutMode(),
                cycle.getPayoutPerShare(),
                cycle.getTurnMode(),
                turns,
                check);
    }

    private List<rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse> turnsOf(
            RotationCycle cycle, List<RotationPeriod> periods) {
        Map<UUID, RotationPeriod> byId = new HashMap<>();
        periods.forEach(p -> byId.put(p.getId(), p));
        var turns = turnRepository.findByCooperativeIdAndCycleId(cycle.getCooperativeId(), cycle.getId());
        Map<UUID, String> names = names(turns.stream().map(rw.terimbere.csams.modules.rotation.entity.RotationTurn::getMemberUserId).toList());
        Map<UUID, java.math.BigDecimal> paid = new HashMap<>();
        Map<UUID, java.math.BigDecimal> open = new HashMap<>();
        for (var p : payoutRepository.findByCooperativeIdAndRotationTurnIdIn(
                cycle.getCooperativeId(), turns.stream().map(rw.terimbere.csams.modules.rotation.entity.RotationTurn::getId).toList())) {
            switch (p.getStatus()) {
                case DISBURSED -> paid.merge(p.getRotationTurnId(), p.getAmount(), java.math.BigDecimal::add);
                case REQUESTED, APPROVED -> open.merge(p.getRotationTurnId(), p.getAmount(), java.math.BigDecimal::add);
                default -> { }
            }
        }
        List<rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse> result = new ArrayList<>();
        for (var t : turns) {
            RotationPeriod p = byId.get(t.getRotationPeriodId());
            if (p == null) {
                continue;
            }
            java.math.BigDecimal paidOut = paid.getOrDefault(t.getId(), java.math.BigDecimal.ZERO);
            java.math.BigDecimal committed = open.getOrDefault(t.getId(), java.math.BigDecimal.ZERO);
            java.math.BigDecimal settled = paidOut.add(t.getOpeningReceivedAmount());
            java.math.BigDecimal outstanding = t.getPayoutAmount().subtract(settled).subtract(committed).max(java.math.BigDecimal.ZERO);
            String state;
            if (settled.compareTo(t.getPayoutAmount()) >= 0) {
                state = "PAID";
            } else if (settled.signum() > 0) {
                state = "PARTLY_PAID";
            } else if (access.hasBegun(YearMonth.of(p.getPeriodYear(), p.getPeriodMonth()))) {
                state = "DUE";
            } else {
                state = "UPCOMING";
            }
            result.add(new rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse(
                    t.getId(), p.getId(), p.getPeriodYear(), p.getPeriodMonth(), t.getMemberUserId(),
                    names.getOrDefault(t.getMemberUserId(), ""), t.getShareCount(),
                    rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(t.getPayoutAmount()),
                    rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(t.getOpeningReceivedAmount()),
                    rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(paidOut),
                    rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(committed),
                    rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(outstanding),
                    state));
        }
        result.sort(java.util.Comparator.comparingInt(rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse::year)
                .thenComparingInt(rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse::month)
                .thenComparing(rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse::memberName));
        return result;
    }

    private rw.terimbere.csams.modules.rotation.dto.RotationDtos.FundingCheck fundingCheck(
            RotationCycle cycle, List<rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse> turns) {
        UUID cooperativeId = cycle.getCooperativeId();
        int withTurns = turns.stream().mapToInt(rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse::shares).sum();
        Map<UUID, Integer> held = heldShares(cooperativeId);
        int heldTotal = held.values().stream().mapToInt(Integer::intValue).sum();
        java.math.BigDecimal unit = cooperativeRepository.findByIdAndDeletedFalse(cooperativeId)
                .map(rw.terimbere.csams.modules.cooperative.entity.Cooperative::getMonthlyContributionAmount)
                .orElse(java.math.BigDecimal.ZERO);
        java.math.BigDecimal rotated = configurationService
                .resolve(cooperativeId, cycle.getStartYear(), cycle.getStartMonth())
                .map(c -> c.getRotatingUnitAmount() != null ? c.getRotatingUnitAmount()
                        : unit.multiply(java.math.BigDecimal.valueOf(c.getRotatingBps()))
                                .divide(java.math.BigDecimal.valueOf(10000), 0, java.math.RoundingMode.HALF_UP))
                .orElse(null);
        java.math.BigDecimal expected = rotated == null ? null
                : rotated.multiply(java.math.BigDecimal.valueOf((long) heldTotal * cycle.getPeriodCount()));
        java.math.BigDecimal payouts = turns.stream()
                .map(rw.terimbere.csams.modules.rotation.dto.RotationDtos.TurnResponse::payoutAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        List<String> warnings = new ArrayList<>();
        Map<UUID, Integer> perMember = new HashMap<>();
        turns.forEach(t -> perMember.merge(t.memberUserId(), t.shares(), Integer::sum));
        for (var e : held.entrySet()) {
            int has = perMember.getOrDefault(e.getKey(), 0);
            if (e.getValue() > 0 && has < e.getValue()) {
                warnings.add("MEMBER_SHARES_WITHOUT_TURN:" + nameOf(e.getKey()) + ":" + (e.getValue() - has));
            }
        }
        if (rotated == null) {
            warnings.add("NO_SPLIT_CONFIGURED");
        } else if (expected.compareTo(payouts) != 0) {
            warnings.add("COLLECTIONS_DIFFER_FROM_PAYOUTS");
        }
        return new rw.terimbere.csams.modules.rotation.dto.RotationDtos.FundingCheck(
                withTurns, heldTotal,
                rotated == null ? null : rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(rotated),
                expected == null ? null : rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(expected),
                rw.terimbere.csams.shared.utilities.MoneyUtils.scaleForStorage(payouts),
                warnings);
    }

    private PeriodResponse toPeriodResponse(RotationPeriod p, Map<UUID, String> names) {
        return new PeriodResponse(
                p.getId(),
                p.getCycleId(),
                p.getSequenceNumber(),
                p.getPeriodYear(),
                p.getPeriodMonth(),
                p.getScheduledRecipientUserId(),
                names.get(p.getScheduledRecipientUserId()),
                p.getStatus(),
                access.hasBegun(YearMonth.of(p.getPeriodYear(), p.getPeriodMonth())));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
