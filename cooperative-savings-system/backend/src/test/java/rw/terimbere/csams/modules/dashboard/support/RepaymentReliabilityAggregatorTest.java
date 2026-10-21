package rw.terimbere.csams.modules.dashboard.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.dashboard.support.RepaymentReliabilityClassifier.InstallmentFact;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;

class RepaymentReliabilityAggregatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 20);
    private static final UUID M1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID M2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID M3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void aggregatesCountsAndPercentage() {
        List<InstallmentFact> facts = List.of(
                onTime(M1, LocalDate.of(2026, 1, 1)),
                onTime(M1, LocalDate.of(2026, 2, 1)),
                late(M1, LocalDate.of(2026, 3, 1)),
                unpaid(M1, LocalDate.of(2026, 4, 1)),
                onTime(M1, LocalDate.of(2026, 5, 1)));

        var ranked = RepaymentReliabilityAggregator.aggregateRanked(facts, AS_OF, 3, 5);
        assertThat(ranked).hasSize(1);
        var row = ranked.get(0);
        assertThat(row.installmentsDue()).isEqualTo(5);
        assertThat(row.installmentsPaidOnTime()).isEqualTo(3);
        assertThat(row.installmentsPaidLate()).isEqualTo(1);
        assertThat(row.installmentsUnpaidPastDue()).isEqualTo(1);
        assertThat(row.onTimeRate()).isEqualByComparingTo("60.0");
    }

    @Test
    void minimumSampleExcludesOneAndTwo() {
        List<InstallmentFact> facts = new ArrayList<>();
        facts.add(onTime(M1, LocalDate.of(2026, 1, 1)));
        facts.add(onTime(M2, LocalDate.of(2026, 1, 1)));
        facts.add(onTime(M2, LocalDate.of(2026, 2, 1)));

        assertThat(RepaymentReliabilityAggregator.aggregateRanked(facts, AS_OF, 3, 5)).isEmpty();
    }

    @Test
    void threeOfThreeIncluded() {
        List<InstallmentFact> facts = List.of(
                onTime(M1, LocalDate.of(2026, 1, 1)),
                onTime(M1, LocalDate.of(2026, 2, 1)),
                onTime(M1, LocalDate.of(2026, 3, 1)));
        assertThat(RepaymentReliabilityAggregator.aggregateRanked(facts, AS_OF, 3, 5)).hasSize(1);
    }

    @Test
    void rateSortingDescendingThenSampleSize() {
        List<InstallmentFact> facts = new ArrayList<>();
        // M1 96% with 25 samples (24 on time, 1 late)
        for (int i = 0; i < 24; i++) {
            facts.add(onTime(M1, LocalDate.of(2026, 1, 1).plusDays(i)));
        }
        facts.add(late(M1, LocalDate.of(2026, 3, 1)));
        // M2 100% with 3 samples
        facts.add(onTime(M2, LocalDate.of(2026, 1, 1)));
        facts.add(onTime(M2, LocalDate.of(2026, 2, 1)));
        facts.add(onTime(M2, LocalDate.of(2026, 3, 1)));
        // M3 100% with 24 samples — should beat M2 on tie-break
        for (int i = 0; i < 24; i++) {
            facts.add(onTime(M3, LocalDate.of(2026, 1, 1).plusDays(i)));
        }

        var ranked = RepaymentReliabilityAggregator.aggregateRanked(facts, AS_OF, 3, 5);
        assertThat(ranked.get(0).memberId()).isEqualTo(M3);
        assertThat(ranked.get(1).memberId()).isEqualTo(M2);
        assertThat(ranked.get(2).memberId()).isEqualTo(M1);
        assertThat(ranked.get(2).onTimeRate()).isEqualByComparingTo("96.0");
    }

    @Test
    void top5Limit() {
        List<InstallmentFact> facts = new ArrayList<>();
        for (int m = 0; m < 7; m++) {
            UUID member = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", m + 1));
            for (int i = 0; i < 3; i++) {
                facts.add(onTime(member, LocalDate.of(2026, 1, 1).plusDays(i)));
            }
        }
        assertThat(RepaymentReliabilityAggregator.aggregateRanked(facts, AS_OF, 3, 5)).hasSize(5);
    }

    @Test
    void excludesFutureFromDenominator() {
        List<InstallmentFact> facts = List.of(
                onTime(M1, LocalDate.of(2026, 1, 1)),
                onTime(M1, LocalDate.of(2026, 2, 1)),
                onTime(M1, LocalDate.of(2026, 3, 1)),
                unpaid(M1, AS_OF.plusDays(40)));
        var ranked = RepaymentReliabilityAggregator.aggregateRanked(facts, AS_OF, 3, 5);
        assertThat(ranked.get(0).installmentsDue()).isEqualTo(3);
    }

    private static InstallmentFact onTime(UUID member, LocalDate due) {
        return fact(member, due, LoanInstallmentStatus.PAID, due.minusDays(1), "100", "100");
    }

    private static InstallmentFact late(UUID member, LocalDate due) {
        return fact(member, due, LoanInstallmentStatus.PAID, due.plusDays(2), "100", "100");
    }

    private static InstallmentFact unpaid(UUID member, LocalDate due) {
        return fact(member, due, LoanInstallmentStatus.OVERDUE, null, "100", "0");
    }

    private static InstallmentFact fact(
            UUID member,
            LocalDate due,
            LoanInstallmentStatus status,
            LocalDate completedOn,
            String dueAmt,
            String paidAmt) {
        return new InstallmentFact(
                member,
                UUID.randomUUID(),
                due,
                status,
                new BigDecimal(dueAmt).setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                new BigDecimal(paidAmt).setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                completedOn);
    }
}
