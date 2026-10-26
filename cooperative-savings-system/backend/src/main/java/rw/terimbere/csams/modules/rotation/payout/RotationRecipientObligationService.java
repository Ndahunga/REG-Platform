package rw.terimbere.csams.modules.rotation.payout;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriod;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriodStatus;
import rw.terimbere.csams.modules.rotation.repository.RotationPeriodRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * What a member of a rotating scheme has put in, been paid out and still has to contribute: the picture an officer needs
 * when a member receives their payout before the end of the cycle.
 *
 * <p>The members who are paid early keep contributing for the months that remain. Nothing new is owed because of the
 * early payout: the amounts still to pay are the contributions already on the member's obligations. The
 * <b>rotational advance</b> is only a way of looking at the same money: what the member received ahead of having
 * contributed it ({@code payoutReceived - paidRotating}). It is shown separately so it is understood, and it is
 * <b>never added</b> to the remaining or overdue amounts, which are already what repays it. It is not a loan and it
 * earns no interest.
 */
@Service
@RequiredArgsConstructor
public class RotationRecipientObligationService {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private final ContributionRepository contributionRepository;
    private final RotationPeriodRepository periodRepository;
    private final RotationPayoutRepository payoutRepository;
    private final RotationEntitlementService entitlementService;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final Clock clock;
    private final rw.terimbere.csams.modules.rotation.repository.RotationTurnRepository turnRepository;
    private final rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository openingRepository;

    public record RecipientPeriod(UUID periodId, int year, int month, RotationPeriodStatus status) {}

    public record MemberObligation(
            UUID memberUserId,
            String memberName,
            /** The periods in which this member is the scheduled recipient. */
            List<RecipientPeriod> recipientPeriods,
            /** What the member has paid on their rotation obligations (cash recorded, any path). */
            BigDecimal paidContributions,
            /** The rotating part of that payment. */
            BigDecimal paidRotating,
            /** Of the paid rotating money, the part whose receipt was verified. */
            BigDecimal verifiedRotating,
            /** Rotation payouts actually disbursed to the member. */
            BigDecimal payoutReceived,
            /** Unpaid part of contributions whose deadline has not passed yet. */
            BigDecimal remainingScheduled,
            /** Unpaid part of contributions whose deadline has passed. */
            BigDecimal overdue,
            /** The earliest deadline among the unpaid contributions that are not overdue; null when there is none. */
            LocalDate nextDeadline,
            /**
             * Received ahead of contributing: payoutReceived less paidRotating, never below zero. A view of the same
             * money, already part of remaining + overdue, never added to them. Not a loan.
             */
            BigDecimal rotationalAdvance,
            /** Cycle periods for which the member has no obligation row yet (not counted in any amount above). */
            int periodsWithoutObligation,
            /** True when the member was paid out and still has contributions to make. */
            boolean paidOutAndStillContributing) {}

    @Transactional(readOnly = true)
    public List<MemberObligation> overview(UUID cooperativeId) {
        requireStaff(cooperativeId);
        return build(cooperativeId, null);
    }

    @Transactional(readOnly = true)
    public MemberObligation forMember(UUID cooperativeId, UUID memberUserId) {
        requireStaff(cooperativeId);
        return build(cooperativeId, memberUserId).stream()
                .findFirst()
                .orElseGet(() -> empty(memberUserId, names(Set.of(memberUserId)).getOrDefault(memberUserId, "")));
    }

    /** A member's own position. */
    @Transactional(readOnly = true)
    public MemberObligation mine(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UUID me = authorizationService.currentPrincipal().getId();
        return build(cooperativeId, me).stream()
                .findFirst()
                .orElseGet(() -> empty(me, names(Set.of(me)).getOrDefault(me, "")));
    }

    private List<MemberObligation> build(UUID cooperativeId, UUID only) {
        LocalDate today = LocalDate.now(clock.withZone(KIGALI));
        List<RotationPeriod> periods = periodRepository.findByCooperativeIdOrderByPeriodYearAscPeriodMonthAsc(cooperativeId).stream()
                .filter(p -> p.getStatus() == RotationPeriodStatus.SCHEDULED)
                .toList();
        Map<UUID, List<RotationPeriod>> asRecipient = new HashMap<>();
        Map<UUID, RotationPeriod> periodById = new HashMap<>();
        periods.forEach(p -> periodById.put(p.getId(), p));
        Set<UUID> sharePeriods = new HashSet<>();
        Map<UUID, BigDecimal> openingReceived = new HashMap<>();
        for (var turn : turnRepository.findByCooperativeId(cooperativeId)) {
            RotationPeriod p = periodById.get(turn.getRotationPeriodId());
            if (p == null) {
                continue;
            }
            sharePeriods.add(p.getId());
            asRecipient.computeIfAbsent(turn.getMemberUserId(), k -> new ArrayList<>()).add(p);
            openingReceived.merge(turn.getMemberUserId(), turn.getOpeningReceivedAmount(), BigDecimal::add);
        }
        for (RotationPeriod period : periods) {
            if (!sharePeriods.contains(period.getId())) {
                asRecipient.computeIfAbsent(period.getScheduledRecipientUserId(), k -> new ArrayList<>()).add(period);
            }
        }
        Map<UUID, BigDecimal> openingPaid = new HashMap<>();
        for (var o : openingRepository.findByCooperativeId(cooperativeId)) {
            openingPaid.put(o.getMemberUserId(), o.getOpeningRotationPaid());
            openingReceived.merge(o.getMemberUserId(), BigDecimal.ZERO, BigDecimal::add);
            if (o.getOpeningRotationReceived().compareTo(openingReceived.get(o.getMemberUserId())) > 0) {
                openingReceived.put(o.getMemberUserId(), o.getOpeningRotationReceived());
            }
        }
        Map<UUID, List<Contribution>> rowsByMember = new HashMap<>();
        for (Contribution row : contributionRepository.findByCooperativeIdAndRotationPeriodIdIsNotNull(cooperativeId)) {
            if (only == null || only.equals(row.getMemberUserId())) {
                rowsByMember.computeIfAbsent(row.getMemberUserId(), k -> new ArrayList<>()).add(row);
            }
        }
        Map<UUID, BigDecimal> received = new HashMap<>();
        for (Object[] row : payoutRepository.sumAmountByRecipient(cooperativeId, EnumSet.of(RotationPayoutStatus.DISBURSED))) {
            received.put((UUID) row[0], (BigDecimal) row[1]);
        }
        Map<UUID, BigDecimal> verified = entitlementService.verifiedAmounts(cooperativeId);

        Set<UUID> members = new HashSet<>(rowsByMember.keySet());
        members.addAll(asRecipient.keySet());
        members.addAll(openingPaid.keySet());
        if (only != null) {
            members.removeIf(id -> !id.equals(only));
        }
        Map<UUID, String> names = names(members);
        List<MemberObligation> result = new ArrayList<>();
        for (UUID member : members) {
            List<Contribution> rows = rowsByMember.getOrDefault(member, List.of());
            BigDecimal paid = BigDecimal.ZERO;
            BigDecimal paidRotating = BigDecimal.ZERO;
            BigDecimal verifiedRotating = BigDecimal.ZERO;
            BigDecimal remaining = BigDecimal.ZERO;
            BigDecimal overdue = BigDecimal.ZERO;
            LocalDate next = null;
            Set<UUID> periodsWithRow = new HashSet<>();
            for (Contribution row : rows) {
                periodsWithRow.add(row.getRotationPeriodId());
                BigDecimal rowPaid = row.getPaidAmount() == null ? BigDecimal.ZERO : row.getPaidAmount();
                paid = paid.add(rowPaid);
                paidRotating = paidRotating.add(RotationEntitlementService.rotatingOf(row, rowPaid));
                verifiedRotating = verifiedRotating.add(RotationEntitlementService.eligibleRotating(row, verified.get(row.getId())));
                if (row.getStatus() == ContributionStatus.WAIVED || row.getStatus() == ContributionStatus.CANCELLED) {
                    continue;
                }
                BigDecimal outstanding = row.getOutstandingAmount() == null ? BigDecimal.ZERO : row.getOutstandingAmount();
                if (outstanding.signum() <= 0) {
                    continue;
                }
                if (row.getDueDate() != null && row.getDueDate().isBefore(today)) {
                    overdue = overdue.add(outstanding);
                } else {
                    remaining = remaining.add(outstanding);
                    if (row.getDueDate() != null && (next == null || row.getDueDate().isBefore(next))) {
                        next = row.getDueDate();
                    }
                }
            }
            // Before the cutover counts too: rotated money paid and payouts received while the scheme ran elsewhere.
            paidRotating = paidRotating.add(openingPaid.getOrDefault(member, BigDecimal.ZERO));
            BigDecimal payout = received.getOrDefault(member, BigDecimal.ZERO)
                    .add(openingReceived.getOrDefault(member, BigDecimal.ZERO));
            BigDecimal advance = payout.subtract(paidRotating).max(BigDecimal.ZERO);
            int withoutRow = (int) periods.stream().filter(p -> !periodsWithRow.contains(p.getId())).count();
            List<RecipientPeriod> mine = asRecipient.getOrDefault(member, List.of()).stream()
                    .distinct()
                    .sorted(Comparator.comparingInt(RotationPeriod::getPeriodYear).thenComparingInt(RotationPeriod::getPeriodMonth))
                    .map(p -> new RecipientPeriod(p.getId(), p.getPeriodYear(), p.getPeriodMonth(), p.getStatus()))
                    .toList();
            BigDecimal stillToPay = remaining.add(overdue);
            result.add(new MemberObligation(
                    member,
                    names.getOrDefault(member, ""),
                    mine,
                    MoneyUtils.scaleForStorage(paid),
                    MoneyUtils.scaleForStorage(paidRotating),
                    MoneyUtils.scaleForStorage(verifiedRotating),
                    MoneyUtils.scaleForStorage(payout),
                    MoneyUtils.scaleForStorage(remaining),
                    MoneyUtils.scaleForStorage(overdue),
                    next,
                    MoneyUtils.scaleForStorage(advance),
                    withoutRow,
                    payout.signum() > 0 && stillToPay.signum() > 0));
        }
        result.sort(Comparator.comparing(MemberObligation::memberName).thenComparing(MemberObligation::memberUserId));
        return result;
    }

    private MemberObligation empty(UUID memberUserId, String name) {
        BigDecimal zero = MoneyUtils.scaleForStorage(BigDecimal.ZERO);
        return new MemberObligation(memberUserId, name, List.of(), zero, zero, zero, zero, zero, zero, null, zero, 0, false);
    }

    private Map<UUID, String> names(Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    private void requireStaff(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        var actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority("PAYOUT_WRITE")
                && !actor.hasAuthority("CONTRIBUTION_WRITE")) {
            throw new ForbiddenException("Members' rotation obligations are for officers");
        }
    }
}
