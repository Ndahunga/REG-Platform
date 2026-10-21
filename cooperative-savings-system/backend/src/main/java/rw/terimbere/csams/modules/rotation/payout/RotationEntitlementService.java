package rw.terimbere.csams.modules.rotation.payout;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.obligation.ContributionAllocationCalculator;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPosting;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPostingReversal;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentPostingRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentPostingReversalRepository;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriodStatus;
import rw.terimbere.csams.modules.membercredit.MemberCreditEntry;
import rw.terimbere.csams.modules.membercredit.MemberCreditEntryRepository;
import rw.terimbere.csams.modules.rotation.reconciliation.LegacyReconciliationRepository;
import rw.terimbere.csams.modules.rotation.reconciliation.LegacyReconciliationStatus;
import rw.terimbere.csams.modules.rotation.reconciliation.LegacyRotatingReconciliation;
import rw.terimbere.csams.modules.rotation.repository.RotationPeriodRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * The rotating entitlement of each rotation period.
 *
 * <p><b>What makes money eligible.</b> A recipient is owed only rotating money whose receipt was VERIFIED: the rotating
 * part recorded in the immutable posting rows of verified, independently posted payments, net of the rows their
 * reversals took back. Contributions recorded through the older direct paths (officer grid, single edit, member
 * submission approval) have an approved ledger credit but no recorded confirmation of how the funds were received
 * (no verification method, evidence, or independent poster). They are therefore NOT eligible: their rotating part is
 * reported as <i>requiring reconciliation</i> and stays out of the entitlement until the money is confirmed through the
 * verified-payment process. Nothing is deleted or rewritten; balances and ledger entries are untouched.
 *
 * <p>The one other way money becomes verified is a <i>legacy reconciliation</i>: credible confirmation, approved by an
 * independent authorizer, that part of an unverified contribution was really received. It adds a verified amount to
 * the obligation and never touches the ledger, so nothing is counted twice.
 *
 * <p>For each obligation the <i>verified amount</i> is the sum of its postings, minus the amounts its reversals took
 * back, plus its approved reconciliations. The eligible rotating amount is the rotating part of
 * {@code min(verified amount, what is currently paid)}, so a later downward correction can only shrink eligibility,
 * never inflate it.
 *
 * <ul>
 *   <li><b>Expected rotating</b>: the rotating part of the obligations bound to the period (never cash).
 *   <li><b>Collected rotating</b> (verified): the eligible amount above. A late payment belongs to its obligation's
 *       period and so to that period's original recipient.
 *   <li><b>Requiring reconciliation</b>: rotating money paid through unverified paths: cash in the ledger, but not owed
 *       to anyone until confirmed.
 *   <li><b>Distributed</b>: payouts whose disbursement was confirmed.
 *   <li><b>Outstanding entitlement</b>: verified collected minus distributed.
 *   <li><b>Rotating arrears</b>: expected minus everything paid on the obligations. A contribution shortfall, kept
 *       apart from the entitlement, and never moved to another recipient.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RotationEntitlementService {

    private final RotationPeriodRepository periodRepository;
    private final ContributionRepository contributionRepository;
    private final RotationPayoutRepository payoutRepository;
    private final MemberPaymentPostingRepository postingRepository;
    private final MemberPaymentPostingReversalRepository postingReversalRepository;
    private final UserRepository userRepository;
    private final LegacyReconciliationRepository reconciliationRepository;
    private final MemberCreditEntryRepository creditRepository;

    public record PeriodEntitlement(
            UUID periodId,
            UUID cycleId,
            int year,
            int month,
            UUID recipientUserId,
            String recipientName,
            RotationPeriodStatus periodStatus,
            BigDecimal expectedRotating,
            /** Rotating money whose receipt was verified (eligible for payout). */
            BigDecimal collectedRotating,
            /** Rotating money paid through unverified paths: not eligible until reconciled. */
            BigDecimal unreconciledRotating,
            /** Expected minus everything paid: a contribution shortfall, not an amount owed to the recipient. */
            BigDecimal rotatingArrears,
            BigDecimal distributed,
            BigDecimal committed,
            BigDecimal outstandingEntitlement,
            BigDecimal payableEntitlement) {}

    public record LateRecovery(
            UUID postingId,
            UUID paymentId,
            LocalDate paymentDate,
            BigDecimal amount,
            BigDecimal rotatingAmount,
            boolean late,
            boolean reversed) {}

    private static final Set<RotationPayoutStatus> DISBURSED = EnumSet.of(RotationPayoutStatus.DISBURSED);
    private static final Set<RotationPayoutStatus> OPEN = EnumSet.of(RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED);

    /** The rotating part of {@code amount} of this obligation, or zero when it carries no rotating split. */
    public static BigDecimal rotatingOf(Contribution row, BigDecimal amount) {
        String model = row.getOperatingModelSnapshot();
        if (row.getRotatingBpsSnapshot() == null
                || row.getRotationPeriodId() == null
                || (!"ROTATING".equals(model) && !"HYBRID".equals(model))
                || amount == null
                || amount.signum() <= 0) {
            return MoneyUtils.scaleForStorage(BigDecimal.ZERO);
        }
        return MoneyUtils.scaleForStorage(
                rw.terimbere.csams.modules.contribution.obligation.ContributionSplits.split(row, MoneyUtils.scaleForStorage(amount)).rotating());
    }

    /**
     * Verified amount per obligation: what its verified postings recorded, minus what reversals took back, plus what
     * approved reconciliations confirmed. The amount is the contribution's own (not only its rotating part).
     */
    static Map<UUID, BigDecimal> verifiedAmounts(
            List<MemberPaymentPosting> postings,
            List<MemberPaymentPostingReversal> reversals,
            List<LegacyRotatingReconciliation> reconciliations) {
        return verifiedAmounts(postings, reversals, reconciliations, List.of());
    }

    /**
     * As above, plus the held credit an officer applied to the obligation: that money was verified when the payment that
     * created the credit was posted, so it counts exactly like a verified posting.
     */
    static Map<UUID, BigDecimal> verifiedAmounts(
            List<MemberPaymentPosting> postings,
            List<MemberPaymentPostingReversal> reversals,
            List<LegacyRotatingReconciliation> reconciliations,
            List<MemberCreditEntry> creditApplications) {
        Map<UUID, BigDecimal> verified = new HashMap<>();
        for (MemberCreditEntry e : creditApplications) {
            if (e.getAppliedContributionId() != null && e.getAmount() != null) {
                verified.merge(e.getAppliedContributionId(), e.getAmount(), BigDecimal::add);
            }
        }
        for (MemberPaymentPosting p : postings) {
            if (p.getContributionId() != null && p.getAmount() != null) {
                verified.merge(p.getContributionId(), p.getAmount(), BigDecimal::add);
            }
        }
        for (MemberPaymentPostingReversal r : reversals) {
            if (r.getAmount() != null) {
                verified.merge(r.getContributionId(), r.getAmount().negate(), BigDecimal::add);
            }
        }
        for (LegacyRotatingReconciliation c : reconciliations) {
            verified.merge(c.getContributionId(), c.getAmount(), BigDecimal::add);
        }
        return verified;
    }

    /** Rotating money of this obligation whose receipt was verified, never more than what is currently paid. */
    static BigDecimal eligibleRotating(Contribution row, BigDecimal verifiedAmount) {
        BigDecimal verified = verifiedAmount == null ? BigDecimal.ZERO : verifiedAmount.max(BigDecimal.ZERO);
        BigDecimal paid = row.getPaidAmount() == null ? BigDecimal.ZERO : row.getPaidAmount();
        return rotatingOf(row, verified.min(paid));
    }

    private Map<UUID, BigDecimal> verifiedForPeriod(UUID cooperativeId, UUID periodId) {
        return verifiedAmounts(
                postingRepository.findByCooperativeIdAndRotationPeriodIdOrderByPostedAtAscIdAsc(cooperativeId, periodId),
                postingReversalRepository.findByCooperativeIdAndRotationPeriodId(cooperativeId, periodId),
                reconciliationRepository.findByCooperativeIdAndRotationPeriodIdAndStatus(
                        cooperativeId, periodId, LegacyReconciliationStatus.APPROVED),
                creditRepository.findAppliedToPeriod(cooperativeId, periodId));
    }

    private Map<UUID, BigDecimal> verifiedForScheme(UUID cooperativeId) {
        return verifiedAmounts(
                postingRepository.findByCooperativeIdAndRotationPeriodIdIsNotNull(cooperativeId),
                postingReversalRepository.findByCooperativeIdAndRotationPeriodIdIsNotNull(cooperativeId),
                reconciliationRepository.findByCooperativeIdAndStatus(cooperativeId, LegacyReconciliationStatus.APPROVED),
                creditRepository.findAppliedToRotationBound(cooperativeId));
    }

    /** Verified amount of every rotation-bound obligation of the scheme (contribution id to amount). */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> verifiedAmounts(UUID cooperativeId) {
        return verifiedForScheme(cooperativeId);
    }

    @Transactional(readOnly = true)
    public PeriodEntitlement forPeriod(UUID cooperativeId, UUID periodId) {
        RotationPeriod period = periodRepository
                .findByIdAndCooperativeId(periodId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", periodId));
        Map<UUID, BigDecimal> net = verifiedForPeriod(cooperativeId, periodId);
        return build(
                period,
                contributionRepository.findByCooperativeIdAndRotationPeriodId(cooperativeId, periodId),
                net,
                names(Set.of(period.getScheduledRecipientUserId())));
    }

    /** Every period of the scheme, in calendar order. */
    @Transactional(readOnly = true)
    public List<PeriodEntitlement> overview(UUID cooperativeId) {
        return build(cooperativeId, periodRepository.findByCooperativeIdOrderByPeriodYearAscPeriodMonthAsc(cooperativeId));
    }

    /** The periods of which the user is the original scheduled recipient. */
    @Transactional(readOnly = true)
    public List<PeriodEntitlement> mine(UUID cooperativeId, UUID userId) {
        return build(
                cooperativeId,
                periodRepository.findByCooperativeIdAndScheduledRecipientUserIdOrderByPeriodYearAscPeriodMonthAsc(
                        cooperativeId, userId));
    }

    /** Total recipient entitlement still owed across the scheme (verified money only): a liability, never member capital. */
    @Transactional(readOnly = true)
    public BigDecimal totalOutstandingEntitlement(UUID cooperativeId) {
        // Owed per period, then added up: a payout that a period's reserve cover paid above its own collections must not
        // reduce what another period's recipient is still owed.
        BigDecimal owed = BigDecimal.ZERO;
        for (PeriodEntitlement period : overview(cooperativeId)) {
            owed = owed.add(period.outstandingEntitlement());
        }
        return MoneyUtils.scaleForStorage(owed);
    }

    /**
     * Paid money whose original rotation period cannot be established, in a rotating or hybrid scheme.
     *
     * @param knownRotating money that is known to be rotating (a rotating obligation, or the rotating part of a hybrid
     *     one whose split was recorded) but cannot be tied to a period or recipient. It is held for recipients (a
     *     liability), never member capital, and never payout-eligible until it is attributed.
     * @param unknown money whose character is genuinely unknown (no model snapshot, or a hybrid split that was never
     *     recorded). It is neither assigned to retained capital nor to a recipient; it is reported on its own.
     */
    public record UnresolvedHistory(BigDecimal knownRotating, BigDecimal unknown) {
        public static UnresolvedHistory none() {
            return new UnresolvedHistory(MoneyUtils.scaleForStorage(BigDecimal.ZERO), MoneyUtils.scaleForStorage(BigDecimal.ZERO));
        }
    }

    @Transactional(readOnly = true)
    public UnresolvedHistory unresolvedHistory(UUID cooperativeId, rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel model) {
        if (model != rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel.ROTATING
                && model != rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel.HYBRID) {
            return UnresolvedHistory.none();
        }
        BigDecimal known = BigDecimal.ZERO;
        BigDecimal unknown = BigDecimal.ZERO;
        for (Contribution row : contributionRepository.findPaidWithoutRotationAttribution(cooperativeId)) {
            BigDecimal paid = row.getPaidAmount();
            String snapshot = row.getOperatingModelSnapshot();
            if (snapshot == null) {
                unknown = unknown.add(paid);
            } else if ("ROTATING".equals(snapshot)) {
                known = known.add(paid);
            } else if (row.getRotatingBpsSnapshot() != null) {
                known = known.add(rw.terimbere.csams.modules.contribution.obligation.ContributionSplits.split(row, MoneyUtils.scaleForStorage(paid)).rotating());
            } else {
                unknown = unknown.add(paid);
            }
        }
        return new UnresolvedHistory(MoneyUtils.scaleForStorage(known), MoneyUtils.scaleForStorage(unknown));
    }

    /** Total rotating funds whose receipt was verified (before any distribution). */
    @Transactional(readOnly = true)
    public BigDecimal totalCollectedRotating(UUID cooperativeId) {
        return totals(cooperativeId)[0];
    }

    /** Rotating money paid through unverified paths: in the ledger as cash, owed to nobody until reconciled. */
    @Transactional(readOnly = true)
    public BigDecimal totalUnreconciledRotating(UUID cooperativeId) {
        return totals(cooperativeId)[1];
    }

    /** Verified rotating funds of one period right now (what a payout may draw on). */
    @Transactional(readOnly = true)
    public BigDecimal verifiedRotatingOfPeriod(UUID cooperativeId, UUID periodId) {
        Map<UUID, BigDecimal> net = verifiedForPeriod(cooperativeId, periodId);
        BigDecimal total = BigDecimal.ZERO;
        for (Contribution row : contributionRepository.findByCooperativeIdAndRotationPeriodId(cooperativeId, periodId)) {
            total = total.add(eligibleRotating(row, net.get(row.getId())));
        }
        return MoneyUtils.scaleForStorage(total);
    }

    /** What payouts have already taken (or committed through approval) out of one period. */
    @Transactional(readOnly = true)
    public BigDecimal distributedOrApproved(UUID cooperativeId, UUID periodId) {
        return MoneyUtils.scaleForStorage(
                payoutRepository.sumAmount(cooperativeId, periodId, EnumSet.of(RotationPayoutStatus.DISBURSED, RotationPayoutStatus.APPROVED)));
    }

    /**
     * True when changing one obligation (a lower paid amount, or a reversal that removes {@code removedVerifiedAmount}
     * from its verified amount) would leave the period's verified rotating funds below what payouts already disbursed
     * or approved, i.e. would make a distribution inconsistent.
     */
    @Transactional(readOnly = true)
    public boolean wouldUndercutDistributions(Contribution row, BigDecimal newPaid, BigDecimal removedVerifiedAmount) {
        if (row.getRotationPeriodId() == null) {
            return false;
        }
        UUID cooperativeId = row.getCooperativeId();
        BigDecimal committed = distributedOrApproved(cooperativeId, row.getRotationPeriodId());
        if (committed.signum() == 0) {
            return false;
        }
        Map<UUID, BigDecimal> net = verifiedForPeriod(cooperativeId, row.getRotationPeriodId());
        BigDecimal others = BigDecimal.ZERO;
        for (Contribution other : contributionRepository.findByCooperativeIdAndRotationPeriodId(cooperativeId, row.getRotationPeriodId())) {
            if (!other.getId().equals(row.getId())) {
                others = others.add(eligibleRotating(other, net.get(other.getId())));
            }
        }
        BigDecimal rowVerified = net.getOrDefault(row.getId(), BigDecimal.ZERO)
                .subtract(removedVerifiedAmount == null ? BigDecimal.ZERO : removedVerifiedAmount)
                .max(BigDecimal.ZERO);
        BigDecimal rowAfter = rotatingOf(row, rowVerified.min(newPaid == null ? BigDecimal.ZERO : newPaid));
        return MoneyUtils.scaleForStorage(others.add(rowAfter)).compareTo(committed) < 0;
    }

    /** Postings attributed to the period: the recovery history of its recipient, with late ones flagged. */
    @Transactional(readOnly = true)
    public List<LateRecovery> recoveries(UUID cooperativeId, UUID periodId) {
        RotationPeriod period = periodRepository
                .findByIdAndCooperativeId(periodId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation period", periodId));
        LocalDate periodEnd = YearMonth.of(period.getPeriodYear(), period.getPeriodMonth()).atEndOfMonth();
        Set<UUID> reversed = new HashSet<>();
        postingReversalRepository.findByCooperativeIdAndRotationPeriodId(cooperativeId, periodId)
                .forEach(r -> reversed.add(r.getPostingId()));
        List<LateRecovery> history = new ArrayList<>();
        for (MemberPaymentPosting posting :
                postingRepository.findByCooperativeIdAndRotationPeriodIdOrderByPostedAtAscIdAsc(cooperativeId, periodId)) {
            history.add(new LateRecovery(
                    posting.getId(),
                    posting.getPaymentId(),
                    posting.getPaymentDate(),
                    posting.getAmount(),
                    posting.getRotatingAmount(),
                    posting.getPaymentDate().isAfter(periodEnd),
                    reversed.contains(posting.getId())));
        }
        return history;
    }

    // ------------------------------------------------------------------ internals

    /** {verified rotating, unreconciled rotating} across every rotation-bound obligation of the scheme. */
    private BigDecimal[] totals(UUID cooperativeId) {
        Map<UUID, BigDecimal> net = verifiedForScheme(cooperativeId);
        BigDecimal verified = BigDecimal.ZERO;
        BigDecimal derived = BigDecimal.ZERO;
        for (Contribution row : contributionRepository.findByCooperativeIdAndRotationPeriodIdIsNotNull(cooperativeId)) {
            verified = verified.add(eligibleRotating(row, net.get(row.getId())));
            derived = derived.add(rotatingOf(row, row.getPaidAmount()));
        }
        verified = MoneyUtils.scaleForStorage(verified);
        BigDecimal unreconciled = MoneyUtils.scaleForStorage(derived.subtract(verified).max(BigDecimal.ZERO));
        return new BigDecimal[] {verified, unreconciled};
    }

    private List<PeriodEntitlement> build(UUID cooperativeId, List<RotationPeriod> periods) {
        Map<UUID, List<Contribution>> rowsByPeriod = new HashMap<>();
        for (Contribution row : contributionRepository.findByCooperativeIdAndRotationPeriodIdIsNotNull(cooperativeId)) {
            rowsByPeriod.computeIfAbsent(row.getRotationPeriodId(), k -> new ArrayList<>()).add(row);
        }
        Map<UUID, BigDecimal> net = verifiedForScheme(cooperativeId);
        Set<UUID> recipients = new HashSet<>();
        periods.forEach(p -> recipients.add(p.getScheduledRecipientUserId()));
        Map<UUID, String> names = names(recipients);
        List<PeriodEntitlement> result = new ArrayList<>();
        for (RotationPeriod period : periods) {
            result.add(build(period, rowsByPeriod.getOrDefault(period.getId(), List.of()), net, names));
        }
        return result;
    }

    private PeriodEntitlement build(
            RotationPeriod period, List<Contribution> rows, Map<UUID, BigDecimal> net, Map<UUID, String> names) {
        BigDecimal expected = BigDecimal.ZERO;
        BigDecimal verified = BigDecimal.ZERO;
        BigDecimal derived = BigDecimal.ZERO;
        for (Contribution row : rows) {
            if (row.getStatus() != ContributionStatus.WAIVED && row.getStatus() != ContributionStatus.CANCELLED) {
                expected = expected.add(rotatingOf(row, row.getExpectedAmount()));
            }
            verified = verified.add(eligibleRotating(row, net.get(row.getId())));
            derived = derived.add(rotatingOf(row, row.getPaidAmount()));
        }
        expected = MoneyUtils.scaleForStorage(expected);
        verified = MoneyUtils.scaleForStorage(verified);
        derived = MoneyUtils.scaleForStorage(derived);
        UUID cooperativeId = period.getCooperativeId();
        BigDecimal distributed = MoneyUtils.scaleForStorage(payoutRepository.sumAmount(cooperativeId, period.getId(), DISBURSED));
        BigDecimal committed = MoneyUtils.scaleForStorage(payoutRepository.sumAmount(cooperativeId, period.getId(), OPEN));
        BigDecimal outstanding = MoneyUtils.scaleForStorage(verified.subtract(distributed).max(BigDecimal.ZERO));
        BigDecimal payable = MoneyUtils.scaleForStorage(outstanding.subtract(committed).max(BigDecimal.ZERO));
        BigDecimal unreconciled = MoneyUtils.scaleForStorage(derived.subtract(verified).max(BigDecimal.ZERO));
        BigDecimal arrears = MoneyUtils.scaleForStorage(expected.subtract(derived).max(BigDecimal.ZERO));
        return new PeriodEntitlement(
                period.getId(),
                period.getCycleId(),
                period.getPeriodYear(),
                period.getPeriodMonth(),
                period.getScheduledRecipientUserId(),
                names.getOrDefault(period.getScheduledRecipientUserId(), ""),
                period.getStatus(),
                expected,
                verified,
                unreconciled,
                arrears,
                distributed,
                committed,
                outstanding,
                payable);
    }

    private Map<UUID, String> names(Set<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(userIds)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }
}
