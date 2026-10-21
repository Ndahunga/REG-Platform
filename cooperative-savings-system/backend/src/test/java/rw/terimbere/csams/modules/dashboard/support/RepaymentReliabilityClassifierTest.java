package rw.terimbere.csams.modules.dashboard.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.dashboard.support.RepaymentReliabilityClassifier.InstallmentFact;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;

class RepaymentReliabilityClassifierTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 20);
    private static final LocalDate DUE = LocalDate.of(2026, 9, 10);
    private static final UUID MEMBER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INST = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void earlyFullPayment_isOnTime() {
        assertThat(RepaymentReliabilityClassifier.classify(paid(DUE.minusDays(2)), AS_OF))
                .isEqualTo(RepaymentTimeliness.ON_TIME);
    }

    @Test
    void sameDayFullPayment_isOnTime() {
        assertThat(RepaymentReliabilityClassifier.classify(paid(DUE), AS_OF))
                .isEqualTo(RepaymentTimeliness.ON_TIME);
    }

    @Test
    void oneDayLate_isPaidLate() {
        assertThat(RepaymentReliabilityClassifier.classify(paid(DUE.plusDays(1)), AS_OF))
                .isEqualTo(RepaymentTimeliness.PAID_LATE);
    }

    @Test
    void unpaidPastDue_isUnpaidPastDue() {
        assertThat(RepaymentReliabilityClassifier.classify(unpaid(DUE.minusDays(1)), AS_OF))
                .isEqualTo(RepaymentTimeliness.UNPAID_PAST_DUE);
    }

    @Test
    void partialPastDue_isUnpaidPastDue() {
        InstallmentFact fact = fact(
                DUE.minusDays(5),
                LoanInstallmentStatus.PARTIALLY_PAID,
                "100",
                "10",
                "0",
                "40",
                "0",
                "0",
                null);
        assertThat(RepaymentReliabilityClassifier.classify(fact, AS_OF))
                .isEqualTo(RepaymentTimeliness.UNPAID_PAST_DUE);
    }

    @Test
    void futureUnpaid_isExcluded() {
        assertThat(RepaymentReliabilityClassifier.classify(unpaid(AS_OF.plusDays(10)), AS_OF))
                .isEqualTo(RepaymentTimeliness.EXCLUDED);
    }

    @Test
    void dueTodayUnpaid_isExcluded() {
        assertThat(RepaymentReliabilityClassifier.classify(unpaid(AS_OF), AS_OF))
                .isEqualTo(RepaymentTimeliness.EXCLUDED);
    }

    @Test
    void futureInstallmentPaidEarly_isOnTime() {
        LocalDate futureDue = AS_OF.plusDays(30);
        assertThat(RepaymentReliabilityClassifier.classify(paid(futureDue, AS_OF.minusDays(1)), AS_OF))
                .isEqualTo(RepaymentTimeliness.ON_TIME);
    }

    @Test
    void paidWithoutCompletionDate_isDataQualityExcluded() {
        InstallmentFact fact = fact(DUE, LoanInstallmentStatus.PAID, "100", "0", "0", "100", "0", "0", null);
        assertThat(RepaymentReliabilityClassifier.classify(fact, AS_OF)).isEqualTo(RepaymentTimeliness.EXCLUDED);
        assertThat(RepaymentReliabilityClassifier.isDataQualityExcluded(fact, AS_OF)).isTrue();
    }

    @Test
    void fullyPaidDetectedFromZeroRemainingEvenIfStatusNotPaid() {
        InstallmentFact fact =
                fact(DUE, LoanInstallmentStatus.PARTIALLY_PAID, "100", "20", "5", "100", "20", "5", DUE);
        assertThat(RepaymentReliabilityClassifier.isFullyPaid(fact)).isTrue();
        assertThat(RepaymentReliabilityClassifier.classify(fact, AS_OF)).isEqualTo(RepaymentTimeliness.ON_TIME);
    }

    @Test
    void twoPaymentsCompletingLate_usesMaxPaymentDate() {
        // Completion date after due → late (simulates MAX of early + late payments)
        assertThat(RepaymentReliabilityClassifier.classify(paid(DUE.plusDays(3)), AS_OF))
                .isEqualTo(RepaymentTimeliness.PAID_LATE);
    }

    private static InstallmentFact paid(LocalDate completedOn) {
        return paid(DUE, completedOn);
    }

    private static InstallmentFact paid(LocalDate dueDate, LocalDate completedOn) {
        return fact(dueDate, LoanInstallmentStatus.PAID, "100", "0", "0", "100", "0", "0", completedOn);
    }

    private static InstallmentFact unpaid(LocalDate dueDate) {
        return fact(dueDate, LoanInstallmentStatus.PENDING, "100", "0", "0", "0", "0", "0", null);
    }

    private static InstallmentFact fact(
            LocalDate dueDate,
            LoanInstallmentStatus status,
            String pDue,
            String iDue,
            String penDue,
            String pPaid,
            String iPaid,
            String penPaid,
            LocalDate completedOn) {
        return new InstallmentFact(
                MEMBER,
                INST,
                dueDate,
                status,
                money(pDue),
                money(iDue),
                money(penDue),
                money(pPaid),
                money(iPaid),
                money(penPaid),
                completedOn);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
