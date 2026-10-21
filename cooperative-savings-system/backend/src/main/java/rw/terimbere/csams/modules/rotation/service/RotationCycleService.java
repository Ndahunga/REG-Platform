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
                periods.stream().map(p -> toPeriodResponse(p, names)).toList());
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
