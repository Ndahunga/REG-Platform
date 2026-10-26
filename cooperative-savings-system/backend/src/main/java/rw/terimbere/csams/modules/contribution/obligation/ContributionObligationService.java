package rw.terimbere.csams.modules.contribution.obligation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.obligation.ContributionAllocationCalculator.Split;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.AttributionPlan;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.MaterializeResponse;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.rotation.entity.RotationConfiguration;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;
import rw.terimbere.csams.modules.rotation.repository.RotationConfigurationRepository;
import rw.terimbere.csams.modules.rotation.repository.RotationPeriodRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Period-specific contribution obligations: the snapshot written when an obligation row is created, the arrears view,
 * and the contribution-side half of late-payment attribution.
 *
 * <p>Nothing here posts, allocates or distributes money. An obligation is an expectation: it is never evidence of a
 * receipt and never changes a balance or the ledger. Existing rows are never rewritten; a snapshot exists only on rows
 * created for the current or a future month after this feature, so historical rows keep their original meaning.
 *
 * <p>The expected amount is still the existing formula (unit amount x member shares). What is new is that the due date,
 * operating model, effective rotating/retained split and the original rotation period are fixed at creation, so a later
 * due-day change, rotation configuration or share change cannot alter an obligation that already exists.
 */
@Service
@RequiredArgsConstructor
public class ContributionObligationService {

    private static final Object CACHE_KEY = new Object();

    private final CooperativeRepository cooperativeRepository;
    private final ContributionDueDaySettingRepository dueDayRepository;
    private final RotationConfigurationRepository configurationRepository;
    private final RotationPeriodRepository periodRepository;
    private final ContributionRepository contributionRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final SchemeFundLockService fundLockService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    // ------------------------------------------------------------------ view of an obligation

    /** What the arrears and allocation views show for one obligation. Expectations only. */
    public record ObligationView(
            boolean snapshotted,
            LocalDate dueDate,
            boolean overdue,
            BigDecimal overdueAmount,
            SchemeOperatingModel operatingModel,
            Integer rotatingBps,
            Integer retainedBps,
            BigDecimal expectedRotatingAmount,
            BigDecimal expectedRetainedAmount,
            UUID rotationPeriodId) {}

    /**
     * The due date of a period under the effective-dated due-day settings (the latest setting effective on or before
     * that period), or null when no setting covers it. Never reads the scheme's current day for an earlier period.
     */
    @Transactional(readOnly = true)
    public LocalDate dueDateFor(UUID cooperativeId, int year, int month) {
        return projectedDue(context(cooperativeId), year, month);
    }

    /** The obligation view of a stored contribution row. */
    @Transactional(readOnly = true)
    public ObligationView viewOf(Contribution c) {
        Context ctx = context(c.getCooperativeId());
        boolean snapshotted = c.getObligationSnapshotAt() != null;
        LocalDate due;
        SchemeOperatingModel model;
        Integer rotating = null;
        Integer retained = null;
        UUID periodId = null;
        if (snapshotted) {
            due = c.getDueDate();
            model = parseModel(c.getOperatingModelSnapshot());
            rotating = c.getRotatingBpsSnapshot();
            retained = c.getRetainedBpsSnapshot();
            periodId = c.getRotationPeriodId();
        } else {
            // A row that predates the snapshot: project from the rules in force for its period. Nothing is stored.
            due = projectedDue(ctx, c.getYear(), c.getMonth());
            model = ctx.model;
            if (isRotationModel(model)) {
                Optional<RotationConfiguration> cfg = ctx.configFor(c.getYear(), c.getMonth());
                rotating = cfg.map(RotationConfiguration::getRotatingBps).orElse(null);
                retained = cfg.map(RotationConfiguration::getRetainedBps).orElse(null);
                periodId = ctx.livePeriodFor(c.getYear(), c.getMonth()).map(RotationPeriod::getId).orElse(null);
            }
        }
        boolean closed = c.getStatus() == ContributionStatus.WAIVED || c.getStatus() == ContributionStatus.CANCELLED;
        ObligationView view = build(snapshotted, due, model, rotating, retained, periodId, c.getExpectedAmount(), c.getPaidAmount(), closed);
        if (snapshotted && ContributionSplits.usesUnits(c) && c.getExpectedAmount() != null) {
            Split exact = ContributionSplits.split(c, c.getExpectedAmount());
            view = new ObligationView(view.snapshotted(), view.dueDate(), view.overdue(), view.overdueAmount(),
                    view.operatingModel(), view.rotatingBps(), view.retainedBps(), exact.rotating(), exact.retained(),
                    view.rotationPeriodId());
        }
        return view;
    }

    /** The projected obligation of a member who has no stored row yet. Reads only; nothing is created. */
    @Transactional(readOnly = true)
    public ObligationView projectedView(UUID cooperativeId, int year, int month, BigDecimal expected, BigDecimal paid) {
        Context ctx = context(cooperativeId);
        Integer rotating = null;
        Integer retained = null;
        UUID periodId = null;
        if (isRotationModel(ctx.model)) {
            Optional<RotationConfiguration> cfg = ctx.configFor(year, month);
            rotating = cfg.map(RotationConfiguration::getRotatingBps).orElse(null);
            retained = cfg.map(RotationConfiguration::getRetainedBps).orElse(null);
            periodId = ctx.livePeriodFor(year, month).map(RotationPeriod::getId).orElse(null);
        }
        return build(false, projectedDue(ctx, year, month), ctx.model, rotating, retained, periodId, expected, paid, false);
    }

    private ObligationView build(
            boolean snapshotted,
            LocalDate due,
            SchemeOperatingModel model,
            Integer rotatingBps,
            Integer retainedBps,
            UUID periodId,
            BigDecimal expected,
            BigDecimal paid,
            boolean closed) {
        ObligationArrears.Result arrears =
                ObligationArrears.calculate(expected, paid, closed, due, ContributionDueDates.today(clock));
        BigDecimal rotatingAmount = null;
        BigDecimal retainedAmount = null;
        if (rotatingBps != null && expected != null) {
            Split split = ContributionAllocationCalculator.split(expected, rotatingBps);
            rotatingAmount = split.rotating();
            retainedAmount = split.retained();
        }
        return new ObligationView(
                snapshotted,
                due,
                arrears.overdue(),
                arrears.overdueAmount(),
                model,
                rotatingBps,
                retainedBps,
                rotatingAmount,
                retainedAmount,
                periodId);
    }

    // ------------------------------------------------------------------ snapshot on creation

    /**
     * Writes the obligation snapshot onto a row that is being created for the current or a future month. A row created
     * for a month that has already begun (late recording of history) gets none: today's shares, due day and rules are
     * not the ones that applied then, and no history is invented. Best effort: a rotating scheme without a
     * configuration or schedule yet simply has no split recorded, and recording a contribution is never blocked.
     */
    public void applyToNewRow(Contribution row, Cooperative cooperative, CooperativeMembership membership) {
        if (YearMonth.of(row.getYear(), row.getMonth()).isBefore(ContributionDueDates.currentMonth(clock))) {
            return;
        }
        Snapshot snapshot = snapshotFor(cooperative, membership, row.getYear(), row.getMonth(), false);
        snapshot.writeTo(row, Instant.now(clock));
    }

    private record Snapshot(
            BigDecimal unitAmount,
            LocalDate dueDate,
            SchemeOperatingModel model,
            UUID configurationId,
            Integer rotatingBps,
            Integer retainedBps,
            UUID rotationPeriodId,
            BigDecimal rotatingUnit) {

        void writeTo(Contribution row, Instant at) {
            row.setObligationSnapshotAt(at);
            row.setUnitAmountSnapshot(unitAmount);
            row.setDueDate(dueDate);
            row.setOperatingModelSnapshot(model == null ? null : model.name());
            row.setRotationConfigurationId(configurationId);
            row.setRotatingBpsSnapshot(rotatingBps);
            row.setRetainedBpsSnapshot(retainedBps);
            row.setRotationPeriodId(rotationPeriodId);
            row.setRotatingUnitSnapshot(rotatingUnit);
        }
    }

    private Snapshot snapshotFor(
            Cooperative cooperative, CooperativeMembership membership, int year, int month, boolean strict) {
        Context ctx = context(cooperative.getId());
        Integer dueDay = ctx.dueDayFor(year, month);
        LocalDate due = dueDay == null ? null : ContributionDueDates.dueDateFor(year, month, dueDay);
        UUID configId = null;
        Integer rotating = null;
        Integer retained = null;
        UUID periodId = null;
        BigDecimal rotatingUnit = null;
        if (isRotationModel(ctx.model)) {
            Optional<RotationConfiguration> cfg = ctx.configFor(year, month);
            Optional<RotationPeriod> period = ctx.livePeriodFor(year, month);
            if (strict && cfg.isEmpty()) {
                throw new BusinessException(
                        "ROTATION_CONFIG_MISSING",
                        "No rotation configuration is effective for " + YearMonth.of(year, month)
                                + "; configure the rotating share first");
            }
            if (strict && period.isEmpty()) {
                throw new BusinessException(
                        "ROTATION_SCHEDULE_MISSING",
                        "No active rotation schedule covers " + YearMonth.of(year, month)
                                + "; its scheduled recipient is unknown");
            }
            if (cfg.isPresent()) {
                configId = cfg.get().getId();
                rotating = cfg.get().getRotatingBps();
                retained = cfg.get().getRetainedBps();
                BigDecimal unitAmount = cooperative.getMonthlyContributionAmount();
                if (cfg.get().getRotatingUnitAmount() != null && unitAmount != null && unitAmount.signum() > 0
                        && cfg.get().getRotatingUnitAmount().compareTo(unitAmount) <= 0) {
                    rotatingUnit = cfg.get().getRotatingUnitAmount();
                }
            }
            if (period.isPresent()) {
                periodId = period.get().getId();
            }
        }
        return new Snapshot(
                cooperative.getMonthlyContributionAmount(), due, ctx.model, configId, rotating, retained, periodId, rotatingUnit);
    }

    // ------------------------------------------------------------------ explicit materialization

    /**
     * Creates the missing obligation rows of the scheme's active members for the current or next month, so each is
     * snapshotted before shares or settings change. Idempotent and serialized per scheme: existing rows are never
     * modified, recreated or duplicated (the unique period key backs this up). Past months are refused, because a snapshot
     * taken now would not describe them. A rotating or hybrid scheme needs its configuration and schedule first.
     */
    @Transactional
    public MaterializeResponse materialize(UUID cooperativeId, int year, int month) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));

        YearMonth target = YearMonth.of(year, month);
        YearMonth current = ContributionDueDates.currentMonth(clock);
        if (target.isBefore(current) || target.isAfter(current.plusMonths(1))) {
            throw new BusinessException(
                    "OBLIGATION_PERIOD_NOT_ALLOWED",
                    "Obligations can only be created for the current or the next month");
        }

        fundLockService.lockSchemeFund(cooperativeId);
        evict(cooperativeId);
        List<CooperativeMembership> members =
                membershipRepository.findByCooperativeIdAndMembershipStatus(cooperativeId, "ACTIVE");
        Map<UUID, Contribution> existing = contributionRepository
                .findByCooperativeIdAndYearAndMonth(cooperativeId, year, month)
                .stream()
                .collect(Collectors.toMap(Contribution::getMemberUserId, c -> c, (a, b) -> a));

        int created = 0;
        List<Contribution> toSave = new ArrayList<>();
        for (CooperativeMembership membership : members) {
            if (existing.containsKey(membership.getUserId())) {
                continue;
            }
            int shares = ShareAmountCalculator.normalizeShareCount(membership.getShareCount());
            BigDecimal expected = ShareAmountCalculator.expectedMonthly(cooperative.getMonthlyContributionAmount(), shares);
            Contribution row = Contribution.builder()
                    .cooperativeId(cooperativeId)
                    .memberUserId(membership.getUserId())
                    .year(year)
                    .month(month)
                    .shareCount(shares)
                    .expectedAmount(expected)
                    .paidAmount(BigDecimal.ZERO)
                    .outstandingAmount(expected)
                    .status(ContributionStatus.PENDING)
                    .ledgerRevision(0)
                    .build();
            snapshotFor(cooperative, membership, year, month, true).writeTo(row, Instant.now(clock));
            toSave.add(row);
            created++;
        }
        contributionRepository.saveAllAndFlush(toSave);
        if (created > 0) {
            auditService.record(
                    principal.getId(),
                    cooperativeId,
                    AuditableAction.CONTRIBUTION_OBLIGATIONS_MATERIALIZE,
                    "ContributionPeriod",
                    null,
                    null,
                    "{\"year\":" + year + ",\"month\":" + month + ",\"created\":" + created + "}",
                    null,
                    null);
        }
        return new MaterializeResponse(year, month, created, members.size() - created, members.size());
    }

    /**
     * Creates the missing obligation rows of every active member for each of {@code months} (current month or later),
     * inside the caller's transaction and under the caller's authorization. Used when a rotation cycle is activated so
     * the whole cycle is owed from day one: a member paid early sees every month still to contribute. Existing rows are
     * never touched. Returns how many rows were created.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int materializeMonths(UUID cooperativeId, List<YearMonth> months, UUID actorId) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        YearMonth current = ContributionDueDates.currentMonth(clock);
        evict(cooperativeId);
        List<CooperativeMembership> members =
                membershipRepository.findByCooperativeIdAndMembershipStatus(cooperativeId, "ACTIVE");
        int created = 0;
        for (YearMonth ym : months) {
            if (ym.isBefore(current)) {
                continue;
            }
            Set<UUID> existing = contributionRepository
                    .findByCooperativeIdAndYearAndMonth(cooperativeId, ym.getYear(), ym.getMonthValue())
                    .stream()
                    .map(Contribution::getMemberUserId)
                    .collect(Collectors.toSet());
            List<Contribution> toSave = new ArrayList<>();
            for (CooperativeMembership membership : members) {
                if (existing.contains(membership.getUserId())) {
                    continue;
                }
                int shares = ShareAmountCalculator.normalizeShareCount(membership.getShareCount());
                if (shares <= 0) {
                    continue;
                }
                BigDecimal expected = ShareAmountCalculator.expectedMonthly(cooperative.getMonthlyContributionAmount(), shares);
                Contribution row = Contribution.builder()
                        .cooperativeId(cooperativeId)
                        .memberUserId(membership.getUserId())
                        .year(ym.getYear())
                        .month(ym.getMonthValue())
                        .shareCount(shares)
                        .expectedAmount(expected)
                        .paidAmount(BigDecimal.ZERO)
                        .outstandingAmount(expected)
                        .status(ContributionStatus.PENDING)
                        .ledgerRevision(0)
                        .build();
                snapshotFor(cooperative, membership, ym.getYear(), ym.getMonthValue(), false).writeTo(row, Instant.now(clock));
                toSave.add(row);
            }
            contributionRepository.saveAllAndFlush(toSave);
            created += toSave.size();
        }
        if (created > 0) {
            auditService.record(
                    actorId,
                    cooperativeId,
                    AuditableAction.CONTRIBUTION_OBLIGATIONS_MATERIALIZE,
                    "ContributionPeriod",
                    null,
                    null,
                    "{\"months\":" + months.size() + ",\"created\":" + created + "}",
                    null,
                    null);
        }
        return created;
    }

    // ------------------------------------------------------------------ late-payment attribution (read only)

    /**
     * What a payment of {@code amount} toward one obligation would add to the rotating and retained portions, using the
     * split fixed for THAT obligation's own period and naming that period's scheduled recipient. A plan only: nothing is
     * allocated, posted or recorded as received.
     *
     * <p>Safeguards a future posting step must keep: the obligation is found by scheme, member and period (never by the
     * current month), it must carry a snapshot, the amount cannot exceed what is still outstanding against the
     * recognized paid amount (so the same money cannot be allocated twice), and the split is computed from cumulative
     * targets so partial payments never drift. A payment never moves to another period or recipient.
     */
    @Transactional(readOnly = true)
    public AttributionPlan previewAttribution(UUID cooperativeId, UUID memberUserId, int year, int month, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("ALLOCATION_AMOUNT_INVALID", "The amount to attribute must be greater than zero");
        }
        Contribution row = contributionRepository
                .findByCooperativeIdAndMemberUserIdAndYearAndMonth(cooperativeId, memberUserId, year, month)
                .orElseThrow(() -> new ResourceNotFoundException("Contribution obligation", year + "-" + month));
        if (row.getObligationSnapshotAt() == null) {
            throw new BusinessException(
                    "OBLIGATION_NOT_SNAPSHOTTED",
                    "This obligation predates allocation snapshots; its rotating and retained split needs a separately approved policy");
        }
        if (row.getStatus() == ContributionStatus.WAIVED || row.getStatus() == ContributionStatus.CANCELLED) {
            throw new BusinessException("OBLIGATION_CLOSED", "This obligation is waived or cancelled and owes nothing");
        }
        BigDecimal paid = row.getPaidAmount() == null ? BigDecimal.ZERO : row.getPaidAmount();
        BigDecimal outstanding = row.getExpectedAmount().subtract(paid).max(BigDecimal.ZERO);
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException(
                    "ALLOCATION_EXCEEDS_OUTSTANDING",
                    "The amount exceeds what is still outstanding for this obligation ("
                            + MoneyUtils.scale(outstanding) + ")");
        }
        BigDecimal rotating = BigDecimal.ZERO;
        BigDecimal retained = amount;
        if (row.getRotatingBpsSnapshot() != null) {
            Split delta = ContributionSplits.delta(row, paid, amount);
            rotating = delta.rotating();
            retained = delta.retained();
        }
        UUID recipient = null;
        if (row.getRotationPeriodId() != null) {
            recipient = periodRepository
                    .findByIdAndCooperativeId(row.getRotationPeriodId(), cooperativeId)
                    .map(RotationPeriod::getScheduledRecipientUserId)
                    .orElse(null);
        }
        return new AttributionPlan(
                row.getId(),
                memberUserId,
                year,
                month,
                parseModel(row.getOperatingModelSnapshot()),
                amount,
                outstanding,
                outstanding.subtract(amount),
                rotating,
                retained,
                row.getRotationPeriodId(),
                recipient);
    }

    // ------------------------------------------------------------------ rule lookups (cached per transaction)

    /** Drops the cached rules of a scheme after they changed in this transaction. */
    public void evict(UUID cooperativeId) {
        @SuppressWarnings("unchecked")
        Map<UUID, Context> cache = (Map<UUID, Context>) TransactionSynchronizationManager.getResource(CACHE_KEY);
        if (cache != null) {
            cache.remove(cooperativeId);
        }
    }

    private Context context(UUID cooperativeId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return load(cooperativeId);
        }
        @SuppressWarnings("unchecked")
        Map<UUID, Context> cache = (Map<UUID, Context>) TransactionSynchronizationManager.getResource(CACHE_KEY);
        if (cache == null) {
            cache = new HashMap<>();
            TransactionSynchronizationManager.bindResource(CACHE_KEY, cache);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(CACHE_KEY);
                }
            });
        }
        return cache.computeIfAbsent(cooperativeId, this::load);
    }

    private Context load(UUID cooperativeId) {
        return new Context(
                cooperativeId,
                cooperativeRepository.findOperatingModelById(cooperativeId),
                dueDayRepository.findByCooperativeIdOrderByEffectiveYearDescEffectiveMonthDesc(cooperativeId),
                configurationRepository.findByCooperativeIdOrderByEffectiveYearDescEffectiveMonthDesc(cooperativeId),
                periodRepository);
    }

    private static boolean isRotationModel(SchemeOperatingModel model) {
        return model == SchemeOperatingModel.ROTATING || model == SchemeOperatingModel.HYBRID;
    }

    private static SchemeOperatingModel parseModel(String value) {
        return value == null ? null : SchemeOperatingModel.valueOf(value);
    }

    private static LocalDate projectedDue(Context ctx, int year, int month) {
        Integer day = ctx.dueDayFor(year, month);
        return day == null ? null : ContributionDueDates.dueDateFor(year, month, day);
    }

    /** The rules of one scheme, loaded once per transaction. Effective-dated lists are newest first. */
    private static final class Context {
        private final UUID cooperativeId;
        private final SchemeOperatingModel model;
        private final List<ContributionDueDaySetting> dueDays;
        private final List<RotationConfiguration> configs;
        private final RotationPeriodRepository periodRepository;
        private final Map<String, Optional<RotationPeriod>> periods = new HashMap<>();

        Context(
                UUID cooperativeId,
                SchemeOperatingModel model,
                List<ContributionDueDaySetting> dueDays,
                List<RotationConfiguration> configs,
                RotationPeriodRepository periodRepository) {
            this.cooperativeId = cooperativeId;
            this.model = model;
            this.dueDays = dueDays;
            this.configs = configs;
            this.periodRepository = periodRepository;
        }

        /** The due day in force for a period (latest setting effective on or before it), or null when none. */
        Integer dueDayFor(int year, int month) {
            int period = year * 12 + month;
            for (ContributionDueDaySetting s : dueDays) {
                if (s.getEffectiveYear() * 12 + s.getEffectiveMonth() <= period) {
                    return s.getDueDay();
                }
            }
            return null;
        }

        Optional<RotationConfiguration> configFor(int year, int month) {
            int period = year * 12 + month;
            for (RotationConfiguration c : configs) {
                if (c.getEffectiveYear() * 12 + c.getEffectiveMonth() <= period) {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }

        Optional<RotationPeriod> livePeriodFor(int year, int month) {
            String key = "%04d-%02d".formatted(year, month);
            return periods.computeIfAbsent(
                    key,
                    k -> periodRepository.findByCooperativeIdAndSlotKeyIn(cooperativeId, List.of(k)).stream()
                            .findFirst());
        }
    }
}
