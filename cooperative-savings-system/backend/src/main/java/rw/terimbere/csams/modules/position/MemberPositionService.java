package rw.terimbere.csams.modules.position;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.entity.MemberOpeningBalance;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutRepository;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Where each member stands, for any kind of saving scheme (accumulating, rotating or hybrid):
 *
 * <ul>
 *   <li><b>Capital</b>: grows by what the member contributes and keeps in the scheme. In an accumulating scheme every
 *       franc paid adds to the member's own capital (a 40,000 payment adds 40,000); in a hybrid scheme only the
 *       retained part does; in a pure rotating scheme there is no capital. Opening capital from the migration and
 *       approved share purchases add to it; share-out lines already paid take from it.
 *   <li><b>Income</b>: interest, fines, investment profit and other income, less expenses, shared <b>equally per
 *       share</b>.
 *   <li><b>Rotation position</b>: rotated money paid in (before and after the cutover) less rotation payouts received.
 *       Positive: the scheme still owes the member (their turn is to come). Negative: the member received ahead of
 *       contributing and owes it back through the months still to pay. Never a loan, never interest.
 *   <li><b>Loans</b> and <b>fines</b> still owed.
 * </ul>
 *
 * Read only: it computes from the records and changes nothing.
 */
@Service
@RequiredArgsConstructor
public class MemberPositionService {

    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final MemberOpeningBalanceRepository openingRepository;
    private final ContributionRepository contributionRepository;
    private final RotationPayoutRepository rotationPayoutRepository;
    private final LedgerFinancialCalculationService financialService;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final JdbcTemplate jdbc;

    public record MemberPosition(
            UUID memberUserId,
            String memberName,
            int shares,
            BigDecimal openingCapital,
            BigDecimal contributedCapital,
            BigDecimal sharePurchases,
            BigDecimal shareOutPaid,
            BigDecimal capital,
            BigDecimal incomeShare,
            BigDecimal openingRotationPaid,
            BigDecimal rotatedPaid,
            BigDecimal openingRotationReceived,
            BigDecimal rotationReceived,
            /** Positive: owed to the member. Negative: an interest-free advance the member is repaying. */
            BigDecimal rotationPosition,
            BigDecimal contributionArrears,
            BigDecimal loansOutstanding,
            BigDecimal finesOutstanding,
            /** Money the scheme owed the member at the migration cutover (a liability to the member). */
            BigDecimal owedToMember,
            /** Paid money whose rotating/retained split was never recorded (hybrid history): shown, not guessed. */
            BigDecimal unclassifiedPaid,
            /** Capital + income + rotation position + owed to member - loans - fines. */
            BigDecimal netPosition) {}

    public record SchemePosition(
            UUID cooperativeId,
            String operatingModel,
            int totalShares,
            BigDecimal incomePool,
            BigDecimal incomePerShare,
            BigDecimal totalCapital,
            BigDecimal totalRotationPosition,
            BigDecimal totalLoansOutstanding,
            BigDecimal totalFinesOutstanding,
            BigDecimal totalOwedToMembers,
            BigDecimal availableFunds,
            /** What members' capital, income and rotation positions say the scheme should hold in cash and loans. */
            BigDecimal expectedAssets,
            /** Cash plus loan principal out plus investments. */
            BigDecimal actualAssets,
            /** actual - expected: zero when every franc is explained. */
            BigDecimal unexplainedDifference,
            List<MemberPosition> members) {}

    @Transactional(readOnly = true)
    public SchemePosition scheme(UUID cooperativeId) {
        requireStaff(cooperativeId);
        return compute(cooperativeId);
    }

    @Transactional(readOnly = true)
    public MemberPosition member(UUID cooperativeId, UUID memberUserId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.getId().equals(memberUserId) && !isStaff(actor)) {
            throw new ForbiddenException("You can only see your own statement");
        }
        return compute(cooperativeId).members().stream()
                .filter(m -> m.memberUserId().equals(memberUserId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Member position", memberUserId));
    }

    @Transactional(readOnly = true)
    public MemberPosition mine(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        return member(cooperativeId, authorizationService.currentPrincipal().getId());
    }

    /** No authorization: for reports and exports whose callers already checked access. */
    @Transactional(readOnly = true)
    public SchemePosition compute(UUID cooperativeId) {
        return compute(cooperativeId, null);
    }

    /**
     * Positions as they stood at the end of {@code asAt} (Kigali time): what was paid, received, lent, repaid and fined up
     * to that day. {@code null} or today gives the current positions.
     */
    @Transactional(readOnly = true)
    public SchemePosition compute(UUID cooperativeId, java.time.LocalDate asAt) {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Kigali"));
        java.time.LocalDate at = asAt == null || !asAt.isBefore(today) ? null : asAt;
        java.sql.Date day = at == null ? null : java.sql.Date.valueOf(at);
        java.sql.Timestamp end = at == null ? null
                : java.sql.Timestamp.from(at.plusDays(1).atStartOfDay(java.time.ZoneId.of("Africa/Kigali")).toInstant());
        Cooperative cooperative = cooperativeRepository.findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        Map<UUID, Integer> shares = new HashMap<>();
        for (CooperativeMembership m : membershipRepository.findByCooperativeIdAndMembershipStatus(cooperativeId, "ACTIVE")) {
            if (at != null && m.getMembershipDate() != null && m.getMembershipDate().isAfter(at)) {
                continue; // joined after the date
            }
            shares.put(m.getUserId(), m.getShareCount() == null ? 0 : m.getShareCount());
        }
        if (at != null) {
            // Shares bought after the date were not held yet.
            jdbc.query("select member_user_id, coalesce(sum(number_of_shares),0) from share_purchases where cooperative_id = ?"
                            + " and status = 'APPROVED' and reviewed_at >= ? group by member_user_id",
                    rs -> {
                        UUID id = (UUID) rs.getObject(1);
                        if (shares.containsKey(id)) {
                            shares.put(id, Math.max(0, shares.get(id) - rs.getInt(2)));
                        }
                    }, cooperativeId, end);
        }
        Map<UUID, MemberOpeningBalance> opening = new HashMap<>();
        openingRepository.findByCooperativeId(cooperativeId).forEach(o -> opening.put(o.getMemberUserId(), o));

        Map<UUID, BigDecimal> retained = new HashMap<>();
        Map<UUID, BigDecimal> rotated = new HashMap<>();
        Map<UUID, BigDecimal> unclassified = new HashMap<>();
        for (Contribution row : contributionRepository.findPaid(cooperativeId)) {
            if (at != null && row.getPaymentDate() != null && row.getPaymentDate().isAfter(at)) {
                continue; // paid after the date
            }
            var split = ContributionService.ledgerSplit(row);
            if (split == null) {
                unclassified.merge(row.getMemberUserId(), row.getPaidAmount(), BigDecimal::add);
            } else {
                retained.merge(row.getMemberUserId(), split.retained(), BigDecimal::add);
                rotated.merge(row.getMemberUserId(), split.rotating(), BigDecimal::add);
            }
        }
        Map<UUID, BigDecimal> received = new HashMap<>();
        if (at == null) {
            for (Object[] r : rotationPayoutRepository.sumAmountByRecipient(cooperativeId, EnumSet.of(RotationPayoutStatus.DISBURSED))) {
                received.put((UUID) r[0], (BigDecimal) r[1]);
            }
        } else {
            received = byMember("select recipient_user_id, coalesce(sum(amount),0) from rotation_payouts where cooperative_id = ?"
                    + " and status = 'DISBURSED' and disbursed_at < ? group by recipient_user_id", cooperativeId, end);
        }
        Map<UUID, BigDecimal> openingTurnReceived = byMember(
                "select member_user_id, coalesce(sum(opening_received_amount),0) from rotation_turns where cooperative_id = ? group by member_user_id",
                cooperativeId);
        Map<UUID, BigDecimal> purchases;
        Map<UUID, BigDecimal> shareOut;
        Map<UUID, BigDecimal> loans;
        Map<UUID, BigDecimal> fines;
        Map<UUID, BigDecimal> arrears;
        if (at == null) {
            purchases = byMember(
                    "select member_user_id, coalesce(sum(total_amount),0) from share_purchases where cooperative_id = ? and status = 'APPROVED' group by member_user_id",
                    cooperativeId);
            shareOut = byMember(
                    "select member_user_id, coalesce(sum(payout_amount),0) from payout_lines where cooperative_id = ? and status = 'PAID' group by member_user_id",
                    cooperativeId);
            loans = byMember(
                    "select member_user_id, coalesce(sum(outstanding_principal + outstanding_interest),0) from loans where cooperative_id = ?"
                            + " and status in ('ACTIVE','OVERDUE') group by member_user_id",
                    cooperativeId);
            fines = byMember(
                    "select member_user_id, coalesce(sum(outstanding_amount),0) from fines where cooperative_id = ?"
                            + " and status in ('UNPAID','PARTIALLY_PAID') group by member_user_id",
                    cooperativeId);
            arrears = byMember(
                    "select member_user_id, coalesce(sum(outstanding_amount),0) from contributions where cooperative_id = ?"
                            + " and status not in ('WAIVED','CANCELLED') and outstanding_amount > 0 and due_date < current_date group by member_user_id",
                    cooperativeId);
        } else {
            purchases = byMember("select member_user_id, coalesce(sum(total_amount),0) from share_purchases where cooperative_id = ?"
                    + " and status = 'APPROVED' and reviewed_at < ? group by member_user_id", cooperativeId, end);
            shareOut = byMember("select l.member_user_id, coalesce(sum(l.payout_amount),0) from payout_lines l join payout_runs r on r.id = l.payout_run_id"
                    + " where l.cooperative_id = ? and l.status = 'PAID' and r.paid_at < ? group by l.member_user_id", cooperativeId, end);
            // What is owed today plus what was repaid after the date, on loans already given out by then.
            loans = byMember("select l.member_user_id, coalesce(sum(l.outstanding_principal + l.outstanding_interest"
                    + " + coalesce((select sum(r.principal_portion + r.interest_portion) from loan_repayments r"
                    + "   where r.loan_id = l.id and r.reversed_at is null and r.payment_date > ?), 0)), 0)"
                    + " from loans l where l.cooperative_id = ? and l.status in ('ACTIVE','OVERDUE','CLOSED') and l.disbursement_date <= ?"
                    + " group by l.member_user_id", day, cooperativeId, day);
            fines = byMember("select f.member_user_id, coalesce(sum(f.outstanding_amount"
                    + " + coalesce((select sum(p.amount) from fine_payments p where p.fine_id = f.id and p.status = 'APPROVED' and p.payment_date > ?), 0)), 0)"
                    + " from fines f where f.cooperative_id = ? and f.status in ('UNPAID','PARTIALLY_PAID','PAID') and f.issued_date <= ?"
                    + " group by f.member_user_id", day, cooperativeId, day);
            arrears = byMember("select member_user_id, coalesce(sum(outstanding_amount + case when payment_date > ? then paid_amount else 0 end),0)"
                    + " from contributions where cooperative_id = ? and status not in ('WAIVED','CANCELLED') and due_date < ?"
                    + " group by member_user_id", day, cooperativeId, day);
        }

        BigDecimal incomePool = at == null ? incomePool(cooperativeId) : incomePoolAt(cooperativeId, day);
        int totalShares = shares.values().stream().mapToInt(Integer::intValue).sum();
        BigDecimal perShare = totalShares == 0 ? BigDecimal.ZERO
                : incomePool.divide(BigDecimal.valueOf(totalShares), 4, RoundingMode.HALF_UP);

        Set<UUID> memberIds = new HashSet<>(shares.keySet());
        memberIds.addAll(opening.keySet());
        memberIds.addAll(retained.keySet());
        memberIds.addAll(received.keySet());
        Map<UUID, String> names = new HashMap<>();
        for (User u : userRepository.findAllById(memberIds)) {
            names.put(u.getId(), u.getFullName());
        }

        List<MemberPosition> members = new ArrayList<>();
        BigDecimal totalCapital = BigDecimal.ZERO;
        BigDecimal totalRotation = BigDecimal.ZERO;
        BigDecimal totalLoans = BigDecimal.ZERO;
        BigDecimal totalFines = BigDecimal.ZERO;
        BigDecimal totalUnclassified = BigDecimal.ZERO;
        BigDecimal totalOwed = BigDecimal.ZERO;
        for (UUID id : memberIds) {
            MemberOpeningBalance o = opening.get(id);
            int s = shares.getOrDefault(id, 0);
            BigDecimal openingCapital = o == null ? BigDecimal.ZERO : o.getOpeningShareCapitalAmount();
            BigDecimal contributed = retained.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal bought = purchases.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal paidOut = shareOut.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal capital = openingCapital.add(contributed).add(bought).subtract(paidOut);
            BigDecimal income = perShare.multiply(BigDecimal.valueOf(s));
            BigDecimal openRotPaid = o == null ? BigDecimal.ZERO : o.getOpeningRotationPaid();
            BigDecimal openRotReceived = (o == null ? BigDecimal.ZERO : o.getOpeningRotationReceived())
                    .max(openingTurnReceived.getOrDefault(id, BigDecimal.ZERO));
            BigDecimal rotPaid = rotated.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal rotReceived = received.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal rotation = openRotPaid.add(rotPaid).subtract(openRotReceived).subtract(rotReceived);
            BigDecimal loan = loans.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal fine = fines.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal unc = unclassified.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal owed = o == null ? BigDecimal.ZERO : o.getOpeningOwedToMember();
            BigDecimal net = capital.add(income).add(rotation).add(owed).subtract(loan).subtract(fine);
            members.add(new MemberPosition(
                    id, names.getOrDefault(id, ""), s,
                    m(openingCapital), m(contributed), m(bought), m(paidOut), m(capital), m(income),
                    m(openRotPaid), m(rotPaid), m(openRotReceived), m(rotReceived), m(rotation),
                    m(arrears.getOrDefault(id, BigDecimal.ZERO)), m(loan), m(fine), m(owed), m(unc), m(net)));
            totalOwed = totalOwed.add(owed);
            totalCapital = totalCapital.add(capital);
            totalRotation = totalRotation.add(rotation);
            totalLoans = totalLoans.add(loan);
            totalFines = totalFines.add(fine);
            totalUnclassified = totalUnclassified.add(unc);
        }
        members.sort(Comparator.comparing(MemberPosition::memberName).thenComparing(MemberPosition::memberUserId));

        BigDecimal available = m(financialService.calculateAvailableGroupFund(cooperativeId));
        BigDecimal loanPrincipalOut = m(scalar(
                "select coalesce(sum(outstanding_principal),0) from loans where cooperative_id = ? and status in ('ACTIVE','OVERDUE')",
                cooperativeId));
        BigDecimal investments = m(financialService.sumActiveInvestmentCapital(cooperativeId));
        if (at != null) {
            // Cash as at the date: today's cash less what came in after it, plus what went out after it.
            BigDecimal inAfter = ledgerAfter("credit_amount", day, cooperativeId, "REGULAR_CONTRIBUTION", "SPECIAL_CONTRIBUTION",
                    "FINE_PAYMENT", "OTHER_INCOME", "INVESTMENT_CAPITAL_RETURN", "SHARE_PURCHASE", "LOAN_INTEREST_PAYMENT",
                    "INVESTMENT_PROFIT", "LOAN_PRINCIPAL_REPAYMENT");
            BigDecimal outAfter = ledgerAfter("debit_amount", day, cooperativeId, "INTEREST_EXPENSE", "INVESTMENT_OUTFLOW",
                    "GENERAL_EXPENSE", "MEMBER_PAYOUT", "LOAN_DISBURSEMENT");
            available = m(available.subtract(inAfter).add(outAfter));
            loanPrincipalOut = m(scalar("select coalesce(sum(l.outstanding_principal + coalesce((select sum(r.principal_portion)"
                    + " from loan_repayments r where r.loan_id = l.id and r.reversed_at is null and r.payment_date > ?), 0)), 0)"
                    + " from loans l where l.cooperative_id = ? and l.status in ('ACTIVE','OVERDUE','CLOSED') and l.disbursement_date <= ?",
                    day, cooperativeId, day));
            investments = m(investments
                    .subtract(ledgerAfter("debit_amount", day, cooperativeId, "INVESTMENT_OUTFLOW"))
                    .add(ledgerAfter("credit_amount", day, cooperativeId, "INVESTMENT_CAPITAL_RETURN")));
        }
        BigDecimal actual = available.add(loanPrincipalOut).add(investments);
        // Rotation positions already net the advances, so their sum is the rotated money still held.
        BigDecimal expected = totalCapital.add(incomePool).add(totalRotation).add(totalUnclassified).add(totalOwed);
        return new SchemePosition(
                cooperativeId,
                cooperative.getOperatingModel() == null ? null : cooperative.getOperatingModel().name(),
                totalShares,
                m(incomePool),
                m(perShare),
                m(totalCapital),
                m(totalRotation),
                m(totalLoans),
                m(totalFines),
                m(totalOwed),
                available,
                m(expected),
                actual,
                m(actual.subtract(expected)),
                members);
    }

    /** Income realised since the cutover: interest, investment profit, fines, other income, less expenses. */
    private BigDecimal incomePool(UUID cooperativeId) {
        BigDecimal interest = financialService.calculateAvailableInterest(cooperativeId);
        BigDecimal fines = financialService.sumApprovedCreditsByType(cooperativeId, LedgerTransactionType.FINE_PAYMENT);
        BigDecimal other = financialService.sumApprovedCreditsByType(cooperativeId, LedgerTransactionType.OTHER_INCOME);
        BigDecimal expenses = financialService.sumApprovedDebitsByType(cooperativeId, LedgerTransactionType.GENERAL_EXPENSE);
        BigDecimal opening = scalar(
                "select coalesce(sum(unallocated_income),0) from scheme_opening_positions where cooperative_id = ?", cooperativeId);
        return interest.add(fines).add(other).subtract(expenses).add(opening);
    }

    /** Income realised up to the end of a day, from the ledger: the same items as {@link #incomePool}. */
    private BigDecimal incomePoolAt(UUID cooperativeId, java.sql.Date day) {
        BigDecimal credits = scalar("select coalesce(sum(credit_amount),0) from financial_ledger where cooperative_id = ? and status = 'APPROVED'"
                + " and transaction_date <= ? and transaction_type in ('LOAN_INTEREST_PAYMENT','INVESTMENT_PROFIT','FINE_PAYMENT','OTHER_INCOME')",
                cooperativeId, day);
        BigDecimal debits = scalar("select coalesce(sum(debit_amount),0) from financial_ledger where cooperative_id = ? and status = 'APPROVED'"
                + " and transaction_date <= ? and transaction_type in ('INTEREST_EXPENSE','GENERAL_EXPENSE')", cooperativeId, day);
        BigDecimal opening = scalar(
                "select coalesce(sum(unallocated_income),0) from scheme_opening_positions where cooperative_id = ?", cooperativeId);
        return credits.subtract(debits).add(opening);
    }

    /** Approved ledger amounts (credit or debit column) of these types dated after a day. */
    private BigDecimal ledgerAfter(String column, java.sql.Date day, UUID cooperativeId, String... types) {
        String in = String.join(",", java.util.Collections.nCopies(types.length, "?"));
        Object[] args = new Object[types.length + 2];
        args[0] = cooperativeId;
        args[1] = day;
        System.arraycopy(types, 0, args, 2, types.length);
        return scalar("select coalesce(sum(" + column + "),0) from financial_ledger where cooperative_id = ? and status = 'APPROVED'"
                + " and transaction_date > ? and transaction_type in (" + in + ")", args);
    }

    private Map<UUID, BigDecimal> byMember(String sql, Object... args) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            result.put((UUID) rs.getObject(1), rs.getBigDecimal(2));
        }, args);
        return result;
    }

    private BigDecimal scalar(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal m(BigDecimal v) {
        return MoneyUtils.scaleForStorage(v == null ? BigDecimal.ZERO : v);
    }

    private static boolean isStaff(UserPrincipal actor) {
        return actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || actor.hasAuthority("PAYOUT_WRITE")
                || actor.hasAuthority("CONTRIBUTION_WRITE")
                || actor.hasAuthority("REPORT_READ");
    }

    private void requireStaff(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        if (!isStaff(authorizationService.currentPrincipal())) {
            throw new ForbiddenException("Members' positions are for officers");
        }
    }
}
