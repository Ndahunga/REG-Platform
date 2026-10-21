package rw.terimbere.csams.modules.dashboard.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import rw.terimbere.csams.modules.dashboard.support.RepaymentReliabilityClassifier.InstallmentFact;

/**
 * Aggregates installment classifications into member repayment-reliability rankings.
 */
public final class RepaymentReliabilityAggregator {

    public static final int DEFAULT_MINIMUM_SAMPLE = 3;
    public static final String PERIOD_LIFETIME = "LIFETIME";

    private RepaymentReliabilityAggregator() {}

    public record MemberTotals(
            UUID memberId,
            long installmentsDue,
            long installmentsPaidOnTime,
            long installmentsPaidLate,
            long installmentsUnpaidPastDue,
            long dataQualityExcluded,
            BigDecimal onTimeRate) {}

    public static List<MemberTotals> aggregateRanked(
            List<InstallmentFact> facts, LocalDate asOfDate, int minimumSample, int limit) {
        Map<UUID, Acc> byMember = new HashMap<>();
        if (facts != null) {
            for (InstallmentFact fact : facts) {
                if (fact == null || fact.memberUserId() == null) {
                    continue;
                }
                Acc acc = byMember.computeIfAbsent(fact.memberUserId(), id -> new Acc());
                RepaymentTimeliness timeliness = RepaymentReliabilityClassifier.classify(fact, asOfDate);
                switch (timeliness) {
                    case ON_TIME -> {
                        acc.onTime++;
                        acc.due++;
                    }
                    case PAID_LATE -> {
                        acc.paidLate++;
                        acc.due++;
                    }
                    case UNPAID_PAST_DUE -> {
                        acc.unpaidPastDue++;
                        acc.due++;
                    }
                    case EXCLUDED -> {
                        if (RepaymentReliabilityClassifier.isDataQualityExcluded(fact, asOfDate)) {
                            acc.dataQualityExcluded++;
                        }
                    }
                }
            }
        }

        List<MemberTotals> eligible = new ArrayList<>();
        for (Map.Entry<UUID, Acc> entry : byMember.entrySet()) {
            Acc acc = entry.getValue();
            if (acc.due < minimumSample) {
                continue;
            }
            eligible.add(new MemberTotals(
                    entry.getKey(),
                    acc.due,
                    acc.onTime,
                    acc.paidLate,
                    acc.unpaidPastDue,
                    acc.dataQualityExcluded,
                    rate(acc.onTime, acc.due)));
        }

        eligible.sort(Comparator.comparing(MemberTotals::onTimeRate)
                .reversed()
                .thenComparing(Comparator.comparingLong(MemberTotals::installmentsDue).reversed())
                .thenComparing(m -> m.memberId().toString()));

        if (limit < 0 || eligible.size() <= limit) {
            return eligible;
        }
        return new ArrayList<>(eligible.subList(0, limit));
    }

    public static BigDecimal rate(long onTime, long due) {
        if (due <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(onTime)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(due), 1, RoundingMode.HALF_UP);
    }

    private static final class Acc {
        long due;
        long onTime;
        long paidLate;
        long unpaidPastDue;
        long dataQualityExcluded;
    }
}
