package rw.terimbere.csams.modules.rotation.pool;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
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
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService.PeriodEntitlement;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutRepository;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutStatus;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Accounting of the rotating pool: what is scheduled for a recipient, what was collected (verified), what was set aside
 * beforehand, and what is left to pay with.
 *
 * <pre>
 *   CollectedPool   = verified rotating collections of the whole scheme
 *   AvailablePool   = OpeningPool + CollectedPool + OtherReceipts (top-ups) - Expenses - PreviousPayouts (disbursed)
 *   PayoutShortfall = max(0, ScheduledPayout - AvailablePool)
 * </pre>
 *
 * <p><b>What a recipient can actually be paid.</b> A recipient is owed the verified money collected for their own period
 * (never another period's, so one member is never paid with another member's unpaid entitlement). The opening pool and
 * top-ups are a <i>reserve</i> that may cover only the part of a period's scheduled amount that its own collections did not
 * reach: {@code cover = min(unused reserve, max(0, scheduled - collected))}. The reserve already used by other periods'
 * payouts is deducted first, and when a late contribution later raises a period's collections the reserve is released
 * again. Nothing is counted twice: the reserve is cash that was already in the scheme's funds, and a payout is one ledger
 * debit whichever source it is drawn from.
 */
@Service
@RequiredArgsConstructor
public class RotationPoolService {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final java.util.Set<RotationPayoutStatus> DISBURSED = EnumSet.of(RotationPayoutStatus.DISBURSED);
    private static final java.util.Set<RotationPayoutStatus> OPEN = EnumSet.of(RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED);

    private final RotationPoolEntryRepository entryRepository;
    private final RotationEntitlementService entitlementService;
    private final RotationPayoutRepository payoutRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final SchemeFundLockService fundLockService;
    private final LedgerFinancialCalculationService financialCalculationService;
    private final AuditService auditService;
    private final Clock clock;

    public record Command(PoolEntryType type, BigDecimal amount, LocalDate effectiveDate, String reason, String reference) {}

    /** The pool of the whole scheme. */
    public record SchemePool(
            BigDecimal openingPool,
            BigDecimal otherReceipts,
            BigDecimal expenses,
            BigDecimal collectedPool,
            BigDecimal previousPayouts,
            BigDecimal committedPayouts,
            BigDecimal availablePool,
            /** Reserve (opening pool and top-ups less expenses) not yet used to cover any period's shortfall. */
            BigDecimal unusedReserve,
            /** Reserve currently covering payouts above the verified collections of their own periods. */
            BigDecimal reserveInUse) {}

    /** One period's scheduled payout against the pool. */
    public record PeriodPool(
            UUID periodId,
            BigDecimal scheduledPayout,
            BigDecimal collectedPool,
            BigDecimal periodCollected,
            BigDecimal openingPool,
            BigDecimal otherReceipts,
            BigDecimal expenses,
            BigDecimal previousPayouts,
            BigDecimal availablePool,
            BigDecimal payoutShortfall,
            /** Of the shortfall of this period, what the unused reserve can cover. */
            BigDecimal reserveCover,
            /** What may be requested for this period right now (own collections plus reserve cover, less already paid). */
            BigDecimal payableNow) {}

    // ------------------------------------------------------------------ recording

    @Transactional
    public RotationPoolEntry record(UUID cooperativeId, Command command, String idempotencyKey, HttpServletRequest http) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        CooperativeOfficerRoles.requireFundAuthorize(actor);
        requireRotationModel(cooperativeId);
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
        if (key != null) {
            RotationPoolEntry existing = entryRepository.findByCooperativeIdAndIdempotencyKey(cooperativeId, key).orElse(null);
            if (existing != null) {
                if (existing.getEntryType() != command.type()
                        || existing.getAmount().compareTo(MoneyUtils.scaleForStorage(command.amount())) != 0) {
                    throw new ConflictException("IDEMPOTENCY_KEY_REUSED", "This key was already used for a different pool entry");
                }
                return existing;
            }
        }
        fundLockService.lockSchemeFund(cooperativeId);

        if (command.type() == null) {
            throw new BusinessException("POOL_ENTRY_TYPE_REQUIRED", "The kind of pool entry is required");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "A pool entry must be greater than zero");
        }
        BigDecimal amount = MoneyUtils.scaleForStorage(command.amount());
        String reason = StringUtils.hasText(command.reason()) ? command.reason().trim() : null;
        if (reason == null || reason.length() < 5) {
            throw new BusinessException("POOL_REASON_REQUIRED", "Explain the pool entry (at least 5 characters)");
        }
        if (reason.length() > 500) {
            throw new BusinessException("POOL_REASON_TOO_LONG", "The reason is limited to 500 characters");
        }
        LocalDate today = LocalDate.now(clock.withZone(KIGALI));
        LocalDate effective = command.effectiveDate() == null ? today : command.effectiveDate();
        if (effective.isAfter(today)) {
            throw new BusinessException("POOL_DATE_IN_FUTURE", "A pool entry cannot be dated in the future");
        }

        BigDecimal base = reserveBase(cooperativeId);
        BigDecimal inUse = reserveInUse(cooperativeId, periodsOf(cooperativeId)).add(shareReserveInUse(cooperativeId));
        BigDecimal unused = base.subtract(inUse);
        switch (command.type()) {
            case OPENING -> {
                if (entryRepository.existsByCooperativeIdAndEntryType(cooperativeId, PoolEntryType.OPENING)) {
                    throw new ConflictException(
                            "POOL_OPENING_EXISTS", "This scheme already has an opening pool. Add to it with a top-up entry.");
                }
                if (payoutRepository.sumAmountForScheme(cooperativeId, EnumSet.of(
                                        RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED, RotationPayoutStatus.DISBURSED))
                                .signum()
                        > 0) {
                    throw new BusinessException(
                            "POOL_OPENING_TOO_LATE", "The opening pool must be recorded before the first payout of the scheme");
                }
                requireBackedByCash(cooperativeId, unused.add(amount));
            }
            case TOP_UP -> requireBackedByCash(cooperativeId, unused.add(amount));
            case EXPENSE -> {
                if (amount.compareTo(unused) > 0) {
                    throw new BusinessException(
                            "POOL_EXPENSE_EXCEEDS_RESERVE",
                            "Only " + unused.max(BigDecimal.ZERO).toPlainString()
                                    + " of the pool is unused. Reserve already covering payouts cannot be released.");
                }
            }
        }

        RotationPoolEntry saved;
        try {
            saved = entryRepository.saveAndFlush(RotationPoolEntry.builder()
                    .cooperativeId(cooperativeId)
                    .entryType(command.type())
                    .amount(amount)
                    .effectiveDate(effective)
                    .reason(reason)
                    .reference(StringUtils.hasText(command.reference()) ? command.reference().trim() : null)
                    .idempotencyKey(key)
                    .openingKey(command.type() == PoolEntryType.OPENING ? cooperativeId : null)
                    .recordedBy(actor.getId())
                    .createdAt(Instant.now(clock))
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("POOL_ENTRY_CONFLICT", "The pool entry conflicts with one recorded at the same time. Try again.");
        }
        // Only enum/uuid/number values go into the JSON, never free text.
        auditService.record(
                actor.getId(),
                cooperativeId,
                AuditableAction.ROTATION_POOL_ENTRY,
                "RotationPoolEntry",
                saved.getId(),
                null,
                "{\"type\":\"" + saved.getEntryType() + "\",\"amount\":\"" + saved.getAmount().toPlainString() + "\"}",
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RotationPoolEntry> entries(UUID cooperativeId) {
        requireStaff(cooperativeId);
        return entryRepository.findByCooperativeIdOrderByCreatedAtAscIdAsc(cooperativeId);
    }

    // ------------------------------------------------------------------ views

    @Transactional(readOnly = true)
    public SchemePool scheme(UUID cooperativeId) {
        requireStaff(cooperativeId);
        return schemePool(cooperativeId);
    }

    @Transactional(readOnly = true)
    public PeriodPool forPeriod(UUID cooperativeId, UUID periodId) {
        requireStaff(cooperativeId);
        List<PeriodEntitlement> periods = periodsOf(cooperativeId);
        PeriodEntitlement period = periods.stream()
                .filter(p -> p.periodId().equals(periodId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", periodId));
        SchemePool pool = schemePool(cooperativeId, periods);
        BigDecimal shortfall = MoneyUtils.scaleForStorage(period.expectedRotating().subtract(pool.availablePool()).max(BigDecimal.ZERO));
        BigDecimal ceiling = ceilingTotal(cooperativeId, period, periods);
        BigDecimal payable = MoneyUtils.scaleForStorage(
                ceiling.subtract(period.distributed()).subtract(period.committed()).max(BigDecimal.ZERO));
        return new PeriodPool(
                periodId,
                period.expectedRotating(),
                pool.collectedPool(),
                period.collectedRotating(),
                pool.openingPool(),
                pool.otherReceipts(),
                pool.expenses(),
                pool.previousPayouts(),
                pool.availablePool(),
                shortfall,
                MoneyUtils.scaleForStorage(ceiling.subtract(period.collectedRotating()).max(BigDecimal.ZERO)),
                payable);
    }

    /**
     * The most a payout of this period may reach in total: its own verified collections plus whatever reserve covers the
     * shortfall of what was scheduled. The caller subtracts what the period has already paid out. Used when a payout is
     * requested, approved and disbursed, each time against the data as it is then.
     */
    @Transactional(readOnly = true)
    public BigDecimal payoutCeiling(UUID cooperativeId, UUID periodId) {
        List<PeriodEntitlement> periods = periodsOf(cooperativeId);
        PeriodEntitlement period = periods.stream()
                .filter(p -> p.periodId().equals(periodId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", periodId));
        return ceilingTotal(cooperativeId, period, periods);
    }

    // ------------------------------------------------------------------ arithmetic

    /** The ceiling of every period in one pass (see {@link #payoutCeiling}); used to say which periods can be paid. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> payoutCeilings(UUID cooperativeId) {
        List<PeriodEntitlement> periods = periodsOf(cooperativeId);
        Map<UUID, BigDecimal> paid = paidAndCommitted(cooperativeId);
        BigDecimal base = reserveBase(cooperativeId);
        BigDecimal covered = BigDecimal.ZERO;
        for (PeriodEntitlement p : periods) {
            covered = covered.add(coveredBy(p, paid));
        }
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (PeriodEntitlement p : periods) {
            BigDecimal coveredByOthers = covered.subtract(coveredBy(p, paid));
            BigDecimal reserveFree = base.subtract(coveredByOthers).max(BigDecimal.ZERO);
            BigDecimal shortfall = p.expectedRotating().subtract(p.collectedRotating()).max(BigDecimal.ZERO);
            result.put(p.periodId(), MoneyUtils.scaleForStorage(p.collectedRotating().add(reserveFree.min(shortfall))));
        }
        return result;
    }

    /** The period-collection periods (each funds its own recipient); share-based periods are pooled separately. */
    private List<PeriodEntitlement> periodsOf(UUID cooperativeId) {
        java.util.Set<UUID> share = entitlementService.sharePeriodIds(cooperativeId);
        List<PeriodEntitlement> all = entitlementService.overview(cooperativeId);
        return share.isEmpty() ? all : all.stream().filter(p -> !share.contains(p.periodId())).toList();
    }

    // ------------------------------------------------------------------ share-based cycles

    /**
     * The pool that pays the turns of share-based cycles: verified rotating money collected in their months, plus the
     * reserve (opening pool, top-ups, less expenses) not already covering a period-collection payout, less what their
     * turns were paid or have committed.
     */
    public record SharePool(
            BigDecimal collected, BigDecimal reserveFree, BigDecimal paidOut, BigDecimal committed, BigDecimal available) {}

    @Transactional(readOnly = true)
    public SharePool sharePool(UUID cooperativeId) {
        java.util.Set<UUID> share = entitlementService.sharePeriodIds(cooperativeId);
        List<PeriodEntitlement> all = entitlementService.overview(cooperativeId);
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal committed = BigDecimal.ZERO;
        List<PeriodEntitlement> legacy = new java.util.ArrayList<>();
        for (PeriodEntitlement p : all) {
            if (share.contains(p.periodId())) {
                collected = collected.add(p.collectedRotating());
                paid = paid.add(p.distributed());
                committed = committed.add(p.committed());
            } else {
                legacy.add(p);
            }
        }
        BigDecimal reserveFree = reserveBase(cooperativeId).subtract(reserveInUse(cooperativeId, legacy)).max(BigDecimal.ZERO);
        BigDecimal available = collected.add(reserveFree).subtract(paid).subtract(committed).max(BigDecimal.ZERO);
        return new SharePool(
                MoneyUtils.scaleForStorage(collected),
                MoneyUtils.scaleForStorage(reserveFree),
                MoneyUtils.scaleForStorage(paid),
                MoneyUtils.scaleForStorage(committed),
                MoneyUtils.scaleForStorage(available));
    }

    /** Reserve that share-based turns are using: what they paid or committed above their own verified collections. */
    private BigDecimal shareReserveInUse(UUID cooperativeId) {
        java.util.Set<UUID> share = entitlementService.sharePeriodIds(cooperativeId);
        if (share.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal net = BigDecimal.ZERO;
        for (PeriodEntitlement p : entitlementService.overview(cooperativeId)) {
            if (share.contains(p.periodId())) {
                net = net.add(p.distributed()).add(p.committed()).subtract(p.collectedRotating());
            }
        }
        return net.max(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public SharePool sharePoolView(UUID cooperativeId) {
        requireStaff(cooperativeId);
        return sharePool(cooperativeId);
    }

    private BigDecimal reserveBase(UUID cooperativeId) {
        BigDecimal opening = entryRepository.sumByType(cooperativeId, PoolEntryType.OPENING);
        BigDecimal topUps = entryRepository.sumByType(cooperativeId, PoolEntryType.TOP_UP);
        BigDecimal expenses = entryRepository.sumByType(cooperativeId, PoolEntryType.EXPENSE);
        return MoneyUtils.scaleForStorage(opening.add(topUps).subtract(expenses));
    }

    /** Per period: what was disbursed or is committed to payouts, from the payouts table (period id to amount). */
    private Map<UUID, BigDecimal> paidAndCommitted(UUID cooperativeId) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (Object[] row : payoutRepository.sumAmountByPeriod(
                cooperativeId, EnumSet.of(RotationPayoutStatus.DISBURSED, RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED))) {
            totals.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return totals;
    }

    /** Reserve covering payouts (disbursed or committed) above the verified collections of their own periods. */
    private BigDecimal reserveInUse(UUID cooperativeId, List<PeriodEntitlement> periods) {
        Map<UUID, BigDecimal> paid = paidAndCommitted(cooperativeId);
        BigDecimal total = BigDecimal.ZERO;
        for (PeriodEntitlement p : periods) {
            total = total.add(coveredBy(p, paid));
        }
        return MoneyUtils.scaleForStorage(total);
    }

    private static BigDecimal coveredBy(PeriodEntitlement period, Map<UUID, BigDecimal> paid) {
        BigDecimal committed = paid.getOrDefault(period.periodId(), BigDecimal.ZERO);
        return committed.subtract(period.collectedRotating()).max(BigDecimal.ZERO);
    }

    private BigDecimal ceilingTotal(UUID cooperativeId, PeriodEntitlement period, List<PeriodEntitlement> periods) {
        Map<UUID, BigDecimal> paid = paidAndCommitted(cooperativeId);
        BigDecimal coveredByOthers = BigDecimal.ZERO;
        for (PeriodEntitlement other : periods) {
            if (!other.periodId().equals(period.periodId())) {
                coveredByOthers = coveredByOthers.add(coveredBy(other, paid));
            }
        }
        BigDecimal reserveFree = reserveBase(cooperativeId).subtract(coveredByOthers).max(BigDecimal.ZERO);
        BigDecimal shortfall = period.expectedRotating().subtract(period.collectedRotating()).max(BigDecimal.ZERO);
        return MoneyUtils.scaleForStorage(period.collectedRotating().add(reserveFree.min(shortfall)));
    }

    private SchemePool schemePool(UUID cooperativeId) {
        return schemePool(cooperativeId, periodsOf(cooperativeId));
    }

    private SchemePool schemePool(UUID cooperativeId, List<PeriodEntitlement> periods) {
        BigDecimal opening = MoneyUtils.scaleForStorage(entryRepository.sumByType(cooperativeId, PoolEntryType.OPENING));
        BigDecimal topUps = MoneyUtils.scaleForStorage(entryRepository.sumByType(cooperativeId, PoolEntryType.TOP_UP));
        BigDecimal expenses = MoneyUtils.scaleForStorage(entryRepository.sumByType(cooperativeId, PoolEntryType.EXPENSE));
        BigDecimal collected = MoneyUtils.scaleForStorage(entitlementService.overview(cooperativeId).stream()
                .map(PeriodEntitlement::collectedRotating)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal previous = MoneyUtils.scaleForStorage(payoutRepository.sumAmountForScheme(cooperativeId, DISBURSED));
        BigDecimal committed = MoneyUtils.scaleForStorage(payoutRepository.sumAmountForScheme(cooperativeId, OPEN));
        BigDecimal available = MoneyUtils.scaleForStorage(
                opening.add(collected).add(topUps).subtract(expenses).subtract(previous));
        BigDecimal inUse = MoneyUtils.scaleForStorage(reserveInUse(cooperativeId, periods).add(shareReserveInUse(cooperativeId)));
        BigDecimal unused = MoneyUtils.scaleForStorage(opening.add(topUps).subtract(expenses).subtract(inUse));
        return new SchemePool(opening, topUps, expenses, collected, previous, committed, available, unused, inUse);
    }

    /**
     * The reserve is cash already in the scheme's funds, and cash that recipients are already owed cannot also be
     * reserved: the funds must cover what recipients are owed from their own periods AND the reserve. Without this a
     * pool of "30,000" could be set aside from the very money collected for a recipient, and then be paid out twice.
     */
    private void requireBackedByCash(UUID cooperativeId, BigDecimal reserveAfter) {
        BigDecimal funds = MoneyUtils.scaleForStorage(financialCalculationService.calculateAvailableGroupFund(cooperativeId));
        BigDecimal owedToRecipients = entitlementService.totalOutstandingEntitlement(cooperativeId);
        BigDecimal free = funds.subtract(owedToRecipients);
        if (reserveAfter.compareTo(free) > 0) {
            throw new BusinessException(
                    "POOL_EXCEEDS_AVAILABLE_FUNDS",
                    "The pool would be " + reserveAfter.toPlainString() + " but only " + free.max(BigDecimal.ZERO).toPlainString()
                            + " of the scheme's funds is not already owed to recipients");
        }
    }

    private void requireRotationModel(UUID cooperativeId) {
        SchemeOperatingModel model = cooperativeRepository.findOperatingModelById(cooperativeId);
        if (model != SchemeOperatingModel.ROTATING && model != SchemeOperatingModel.HYBRID) {
            throw new BusinessException(
                    "ROTATION_POOL_NOT_APPLICABLE", "A rotating pool exists only for Rotating and Hybrid saving schemes");
        }
    }

    private void requireStaff(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority("PAYOUT_WRITE")
                && !actor.hasAuthority("CONTRIBUTION_WRITE")) {
            throw new ForbiddenException("The rotating pool is for officers");
        }
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        return StringUtils.hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
