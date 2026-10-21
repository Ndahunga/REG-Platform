package rw.terimbere.csams.modules.report.financial;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.report.dto.MemberContributionAggregate;
import rw.terimbere.csams.modules.report.support.ContributionObligationPeriod;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Phase D1 obligation-period member contribution schedule for FULL_FINANCIAL.
 */
@Service
@RequiredArgsConstructor
public class MemberContributionScheduleService {

    private final ContributionRepository contributionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<MemberContributionAggregate> loadAggregates(
            UUID cooperativeId, ContributionObligationPeriod.Range range) {
        if (range.singleMonth()) {
            List<Contribution> rows = contributionRepository.findObligationRowsForMonth(
                    cooperativeId, range.fromYear(), range.fromMonth());
            Map<UUID, String> names = loadMemberNames(
                    rows.stream().map(Contribution::getMemberUserId).distinct().toList());
            List<MemberContributionAggregate> aggregates = new ArrayList<>();
            for (Contribution c : rows) {
                BigDecimal expected = c.getStatus() == ContributionStatus.WAIVED
                        ? MoneyUtils.scale(BigDecimal.ZERO)
                        : MoneyUtils.scale(nvl(c.getExpectedAmount()));
                BigDecimal paid = MoneyUtils.scale(nvl(c.getPaidAmount()));
                BigDecimal remaining = MoneyUtils.scale(nvl(c.getOutstandingAmount()));
                BigDecimal overpaid = BigDecimal.ZERO;
                if (c.getStatus() != ContributionStatus.WAIVED
                        && paid.compareTo(MoneyUtils.scale(nvl(c.getExpectedAmount()))) > 0) {
                    overpaid = MoneyUtils.scale(
                            paid.subtract(MoneyUtils.scale(nvl(c.getExpectedAmount()))));
                }
                aggregates.add(MemberContributionAggregate.builder()
                        .memberUserId(c.getMemberUserId())
                        .memberName(names.getOrDefault(c.getMemberUserId(), ""))
                        .expectedAmount(expected)
                        .paidAmount(paid)
                        .remainingAmount(remaining)
                        .overpaidAmount(overpaid)
                        .periodsCounted(1)
                        .status(c.getStatus() == null ? "" : c.getStatus().name())
                        .build());
            }
            aggregates.sort(Comparator.comparing(
                    a -> a.getMemberName() == null ? "" : a.getMemberName(), String.CASE_INSENSITIVE_ORDER));
            return aggregates;
        }

        List<Object[]> rows = contributionRepository.sumAggregatesByMemberInObligationRange(
                cooperativeId,
                range.fromYear(),
                range.fromMonth(),
                range.toYear(),
                range.toMonth());
        Map<UUID, String> names =
                loadMemberNames(rows.stream().map(r -> (UUID) r[0]).distinct().toList());
        List<MemberContributionAggregate> aggregates = new ArrayList<>();
        for (Object[] row : rows) {
            UUID memberId = (UUID) row[0];
            aggregates.add(MemberContributionAggregate.builder()
                    .memberUserId(memberId)
                    .memberName(names.getOrDefault(memberId, ""))
                    .expectedAmount(MoneyUtils.scale(nvl((BigDecimal) row[1])))
                    .paidAmount(MoneyUtils.scale(nvl((BigDecimal) row[2])))
                    .remainingAmount(MoneyUtils.scale(nvl((BigDecimal) row[3])))
                    .overpaidAmount(MoneyUtils.scale(nvl((BigDecimal) row[4])))
                    .periodsCounted(((Number) row[5]).longValue())
                    .status(null)
                    .build());
        }
        aggregates.sort(Comparator.comparing(
                a -> a.getMemberName() == null ? "" : a.getMemberName(), String.CASE_INSENSITIVE_ORDER));
        return aggregates;
    }

    public FullFinancialReportModel.MemberContributionSection toSection(
            ContributionObligationPeriod.Range range, List<MemberContributionAggregate> aggregates) {
        BigDecimal expectedTotal = BigDecimal.ZERO;
        BigDecimal paidTotal = BigDecimal.ZERO;
        BigDecimal remainingTotal = BigDecimal.ZERO;
        BigDecimal overpaidTotal = BigDecimal.ZERO;
        long periodsTotal = 0;
        for (MemberContributionAggregate a : aggregates) {
            expectedTotal = MoneyUtils.add(expectedTotal, a.getExpectedAmount());
            paidTotal = MoneyUtils.add(paidTotal, a.getPaidAmount());
            remainingTotal = MoneyUtils.add(remainingTotal, a.getRemainingAmount());
            overpaidTotal = MoneyUtils.add(overpaidTotal, a.getOverpaidAmount());
            periodsTotal += a.getPeriodsCounted();
        }
        if (range.singleMonth()) {
            return FullFinancialReportModel.MemberContributionSection.builder()
                    .singleMonth(true)
                    .sheetTitle("Member Contributions — " + range.singleMonthLabel(Locale.ENGLISH))
                    .headers(List.of("Member", "Expected", "Paid", "Remaining", "Status"))
                    .rows(aggregates)
                    .totalExpected(MoneyUtils.scale(expectedTotal))
                    .totalPaid(MoneyUtils.scale(paidTotal))
                    .totalRemaining(MoneyUtils.scale(remainingTotal))
                    .totalOverpaid(MoneyUtils.scale(overpaidTotal))
                    .totalPeriodsCounted(periodsTotal)
                    .build();
        }
        return FullFinancialReportModel.MemberContributionSection.builder()
                .singleMonth(false)
                .sheetTitle("Member Contribution Summary")
                .headers(List.of("Member", "Periods", "Expected", "Paid", "Remaining", "Overpaid"))
                .rows(aggregates)
                .totalExpected(MoneyUtils.scale(expectedTotal))
                .totalPaid(MoneyUtils.scale(paidTotal))
                .totalRemaining(MoneyUtils.scale(remainingTotal))
                .totalOverpaid(MoneyUtils.scale(overpaidTotal))
                .totalPeriodsCounted(periodsTotal)
                .build();
    }

    private Map<UUID, String> loadMemberNames(List<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        for (User user : userRepository.findAllById(userIds)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
