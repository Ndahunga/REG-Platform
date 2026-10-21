package rw.terimbere.csams.modules.contribution.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.contribution.obligation.ContributionAllocationCalculator.Split;

/** Pure calendar, arrears and allocation arithmetic. No database, no ledger, no money moves. */
class ContributionObligationUnitTest {

    private static BigDecimal rwf(long amount) {
        return BigDecimal.valueOf(amount);
    }

    // ------------------------------------------------------------------ due dates

    @Test
    void theDueDateIsTheConfiguredDayOfTheMonth() {
        assertThat(ContributionDueDates.dueDateFor(2026, 3, 10)).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(ContributionDueDates.dueDateFor(2026, 3, 1)).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(ContributionDueDates.dueDateFor(2026, 3, 31)).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void shortMonthsFallBackToTheLastCalendarDay() {
        // February: 28 days in 2026, 29 in the leap year 2028
        assertThat(ContributionDueDates.dueDateFor(2026, 2, 29)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(ContributionDueDates.dueDateFor(2026, 2, 31)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(ContributionDueDates.dueDateFor(2028, 2, 29)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(ContributionDueDates.dueDateFor(2028, 2, 31)).isEqualTo(LocalDate.of(2028, 2, 29));
        // 30-day months
        assertThat(ContributionDueDates.dueDateFor(2026, 4, 31)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(ContributionDueDates.dueDateFor(2026, 11, 31)).isEqualTo(LocalDate.of(2026, 11, 30));
        // a day that exists is never moved
        assertThat(ContributionDueDates.dueDateFor(2026, 4, 30)).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void anInvalidDueDayIsRejected() {
        assertThatThrownBy(() -> ContributionDueDates.dueDateFor(2026, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContributionDueDates.dueDateFor(2026, 3, 32)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void todayAndTheCurrentMonthUseTheKigaliCalendarNotUtc() {
        // 23:30 UTC on 31 March is already 1 April in Kigali (UTC+2), and 22:00 UTC on 28 February is 1 March.
        Clock lateMarch = Clock.fixed(Instant.parse("2026-03-31T23:30:00Z"), ZoneOffset.UTC);
        assertThat(ContributionDueDates.today(lateMarch)).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(ContributionDueDates.currentMonth(lateMarch)).isEqualTo(YearMonth.of(2026, 4));
        Clock earlyMarch = Clock.fixed(Instant.parse("2026-02-28T22:00:00Z"), ZoneOffset.UTC);
        assertThat(ContributionDueDates.currentMonth(earlyMarch)).isEqualTo(YearMonth.of(2026, 3));
        // 21:59 UTC is still the 28th in Kigali
        Clock justBefore = Clock.fixed(Instant.parse("2026-02-28T21:59:00Z"), ZoneOffset.UTC);
        assertThat(ContributionDueDates.currentMonth(justBefore)).isEqualTo(YearMonth.of(2026, 2));
        // the server's own zone makes no difference
        Clock tokyo = Clock.fixed(Instant.parse("2026-03-31T23:30:00Z"), java.time.ZoneId.of("Asia/Tokyo"));
        assertThat(ContributionDueDates.today(tokyo)).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    // ------------------------------------------------------------------ arrears

    @Test
    void outstandingIsExpectedMinusPaidAndNeverNegative() {
        LocalDate today = LocalDate.of(2026, 3, 20);
        assertThat(ObligationArrears.calculate(rwf(10_000), rwf(4_000), false, null, today).outstanding())
                .isEqualByComparingTo("6000");
        assertThat(ObligationArrears.calculate(rwf(10_000), rwf(12_000), false, null, today).outstanding())
                .isEqualByComparingTo("0");
        assertThat(ObligationArrears.calculate(rwf(10_000), null, false, null, today).outstanding())
                .isEqualByComparingTo("10000");
    }

    @Test
    void anObligationIsOverdueOnlyAfterItsDueDate() {
        LocalDate due = LocalDate.of(2026, 3, 10);
        BigDecimal expected = rwf(10_000);
        BigDecimal paid = rwf(4_000);

        var before = ObligationArrears.calculate(expected, paid, false, due, due.minusDays(1));
        assertThat(before.overdue()).isFalse();
        assertThat(before.overdueAmount()).isEqualByComparingTo("0");
        assertThat(before.outstanding()).as("outstanding but not yet due is not overdue").isEqualByComparingTo("6000");

        var onTheDay = ObligationArrears.calculate(expected, paid, false, due, due);
        assertThat(onTheDay.overdue()).as("the due date itself is still on time").isFalse();

        var after = ObligationArrears.calculate(expected, paid, false, due, due.plusDays(1));
        assertThat(after.overdue()).isTrue();
        assertThat(after.overdueAmount()).isEqualByComparingTo("6000");
    }

    @Test
    void nothingIsOverdueWithoutADueDateOrWhenSettledOrClosed() {
        LocalDate today = LocalDate.of(2026, 3, 20);
        assertThat(ObligationArrears.calculate(rwf(10_000), rwf(0), false, null, today).overdue())
                .as("a scheme with no configured due day never produces overdue dates")
                .isFalse();
        assertThat(ObligationArrears.calculate(rwf(10_000), rwf(10_000), false, LocalDate.of(2026, 3, 1), today).overdue())
                .isFalse();
        var waived = ObligationArrears.calculate(rwf(10_000), rwf(0), true, LocalDate.of(2026, 3, 1), today);
        assertThat(waived.overdue()).isFalse();
        assertThat(waived.outstanding()).isEqualByComparingTo("0");
    }

    @Test
    void theKigaliDateDecidesWhetherAnObligationIsOverdue() {
        LocalDate due = LocalDate.of(2026, 3, 10);
        // 23:30 UTC on 10 March is 11 March in Kigali: already overdue there, although it is still the 10th in UTC.
        Clock kigaliEleventh = Clock.fixed(Instant.parse("2026-03-10T23:30:00Z"), ZoneOffset.UTC);
        assertThat(ObligationArrears.calculate(rwf(1000), rwf(0), false, due, ContributionDueDates.today(kigaliEleventh)).overdue())
                .isTrue();
        Clock kigaliTenth = Clock.fixed(Instant.parse("2026-03-10T21:30:00Z"), ZoneOffset.UTC);
        assertThat(ObligationArrears.calculate(rwf(1000), rwf(0), false, due, ContributionDueDates.today(kigaliTenth)).overdue())
                .isFalse();
    }

    // ------------------------------------------------------------------ split

    @Test
    void aRotatingSchemeIsEntirelyRotating() {
        Split split = ContributionAllocationCalculator.split(rwf(5_005), 10_000);
        assertThat(split.rotating()).isEqualByComparingTo("5005");
        assertThat(split.retained()).isEqualByComparingTo("0");
        // exact even for a fractional amount: 100% is never rounded
        Split fractional = ContributionAllocationCalculator.split(new BigDecimal("1000.5000"), 10_000);
        assertThat(fractional.rotating()).isEqualByComparingTo("1000.5");
        assertThat(fractional.retained()).isEqualByComparingTo("0");
    }

    @Test
    void aHybridSplitRoundsTheRotatingPartHalfUpAndKeepsTheExactRemainderAsRetained() {
        // 5005 x 33.33% = 1668.1665 -> 1668 rotating, 3337 retained
        Split a = ContributionAllocationCalculator.split(rwf(5_005), 3_333);
        assertThat(a.rotating()).isEqualByComparingTo("1668");
        assertThat(a.retained()).isEqualByComparingTo("3337");
        // exactly .5 rounds UP: 1001 x 50% = 500.5 -> 501 rotating, 500 retained
        Split b = ContributionAllocationCalculator.split(rwf(1_001), 5_000);
        assertThat(b.rotating()).isEqualByComparingTo("501");
        assertThat(b.retained()).isEqualByComparingTo("500");
        // below .5 rounds down: 1003 x 33.33% = 334.2999 -> 334
        Split c = ContributionAllocationCalculator.split(rwf(1_003), 3_333);
        assertThat(c.rotating()).isEqualByComparingTo("334");
        assertThat(c.retained()).isEqualByComparingTo("669");
        // a whole-number result is untouched
        Split d = ContributionAllocationCalculator.split(rwf(10_000), 7_000);
        assertThat(d.rotating()).isEqualByComparingTo("7000");
        assertThat(d.retained()).isEqualByComparingTo("3000");
    }

    @Test
    void retainedPlusRotatingAlwaysEqualsTheAmountAndNeitherIsNegative() {
        Random random = new Random(42);
        for (int i = 0; i < 2_000; i++) {
            BigDecimal amount = rwf(random.nextInt(2_000_000));
            int bps = 1 + random.nextInt(10_000);
            Split split = ContributionAllocationCalculator.split(amount, bps);
            assertThat(split.rotating().add(split.retained())).isEqualByComparingTo(amount);
            assertThat(split.rotating().signum()).isGreaterThanOrEqualTo(0);
            assertThat(split.retained().signum()).isGreaterThanOrEqualTo(0);
            assertThat(split.rotating()).isLessThanOrEqualTo(amount);
        }
        // a fractional amount can never push retained below zero either
        Split edge = ContributionAllocationCalculator.split(new BigDecimal("0.4000"), 9_999);
        assertThat(edge.retained().signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> ContributionAllocationCalculator.split(rwf(-1), 5_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContributionAllocationCalculator.split(rwf(10), 10_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContributionAllocationCalculator.split(rwf(10), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContributionAllocationCalculator.delta(rwf(0), rwf(-5), 5_000)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ partial payments: no cumulative drift

    @Test
    void partialPaymentsAddUpToExactlyTheSplitOfTheTotalWhateverTheirSizesAndOrder() {
        Random random = new Random(2026);
        for (int trial = 0; trial < 500; trial++) {
            BigDecimal expected = rwf(1_000 + random.nextInt(500_000));
            int bps = 1 + random.nextInt(9_999);
            List<BigDecimal> payments = randomPartition(expected, random);

            BigDecimal paid = BigDecimal.ZERO;
            BigDecimal rotating = BigDecimal.ZERO;
            BigDecimal retained = BigDecimal.ZERO;
            for (BigDecimal payment : payments) {
                Split delta = ContributionAllocationCalculator.delta(paid, payment, bps);
                assertThat(delta.rotating().signum()).isGreaterThanOrEqualTo(0);
                assertThat(delta.retained().signum()).as("a payment never gives back retained money").isGreaterThanOrEqualTo(0);
                assertThat(delta.rotating().add(delta.retained())).isEqualByComparingTo(payment);
                rotating = rotating.add(delta.rotating());
                retained = retained.add(delta.retained());
                paid = paid.add(payment);
            }
            Split obligation = ContributionAllocationCalculator.split(expected, bps);
            assertThat(rotating).as("trial %d: rotating over %d payments", trial, payments.size())
                    .isEqualByComparingTo(obligation.rotating());
            assertThat(retained).isEqualByComparingTo(obligation.retained());
            assertThat(rotating.add(retained)).isEqualByComparingTo(expected);
        }
    }

    @Test
    void roundingEachPaymentSeparatelyWouldDriftWhichIsWhyCumulativeTargetsAreUsed() {
        // 100 payments of RWF 1 at 33.33%: rounding each one gives 0 rotating in total instead of 33.
        int bps = 3_333;
        BigDecimal naiveRotating = BigDecimal.ZERO;
        BigDecimal cumulativeRotating = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (int i = 0; i < 100; i++) {
            naiveRotating = naiveRotating.add(BigDecimal.ONE
                    .multiply(BigDecimal.valueOf(bps))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP));
            cumulativeRotating = cumulativeRotating.add(ContributionAllocationCalculator.delta(paid, BigDecimal.ONE, bps).rotating());
            paid = paid.add(BigDecimal.ONE);
        }
        BigDecimal target = ContributionAllocationCalculator.split(rwf(100), bps).rotating();
        assertThat(target).isEqualByComparingTo("33");
        assertThat(naiveRotating).as("per-payment rounding loses the whole rotating share").isEqualByComparingTo("0");
        assertThat(cumulativeRotating).as("cumulative targets hit the exact split").isEqualByComparingTo(target);
    }

    @Test
    void aFullPaymentLandsExactlyOnTheObligationsOwnSplit() {
        BigDecimal expected = rwf(5_005);
        Split whole = ContributionAllocationCalculator.delta(BigDecimal.ZERO, expected, 3_333);
        Split obligation = ContributionAllocationCalculator.split(expected, 3_333);
        assertThat(whole.rotating()).isEqualByComparingTo(obligation.rotating());
        assertThat(whole.retained()).isEqualByComparingTo(obligation.retained());
        // and a 100% rotating obligation sends every payment entirely to rotating
        Split all = ContributionAllocationCalculator.delta(rwf(1_000), rwf(777), 10_000);
        assertThat(all.rotating()).isEqualByComparingTo("777");
        assertThat(all.retained()).isEqualByComparingTo("0");
    }

    /** Splits an amount into 1 to 12 positive whole parts, in a random order. */
    private static List<BigDecimal> randomPartition(BigDecimal total, Random random) {
        int parts = 1 + random.nextInt(12);
        long remaining = total.longValue();
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < parts - 1 && remaining > 1; i++) {
            long piece = 1 + random.nextInt((int) Math.max(1, remaining - 1));
            out.add(rwf(piece));
            remaining -= piece;
        }
        out.add(rwf(remaining));
        return out;
    }
}
