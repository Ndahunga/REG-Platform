package rw.terimbere.csams.modules.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator.Input;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator.Position;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator.PositionException;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator.RemainingInstallment;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator.RepaymentType;

class ImportedLoanPositionCalculatorTest {

    private static final LocalDate CUTOVER = LocalDate.of(2026, 9, 30);

    private static Input monthly(String amount, String rate, LocalDate issued, int term, String paid, String outstanding) {
        return monthly(amount, rate, issued, term, paid, outstanding, LoanRepaymentDateModel.SAME_DAY_OF_MONTH);
    }

    private static Input monthly(
            String amount,
            String rate,
            LocalDate issued,
            int term,
            String paid,
            String outstanding,
            LoanRepaymentDateModel model) {
        return new Input(
                RepaymentType.MONTHLY,
                new BigDecimal(amount),
                new BigDecimal(rate),
                issued,
                term,
                new BigDecimal(paid),
                new BigDecimal(outstanding),
                CUTOVER,
                model);
    }

    private static Input endOfTerm(String amount, String rate, LocalDate issued, int term, String paid, String outstanding) {
        return new Input(
                RepaymentType.END_OF_TERM,
                new BigDecimal(amount),
                new BigDecimal(rate),
                issued,
                term,
                new BigDecimal(paid),
                new BigDecimal(outstanding),
                CUTOVER,
                null);
    }

    // ------------------------------------------------------------------ MONTHLY

    @Test
    void partiallyPaidMonthlyLoan_derivesHistoricalBreakdownAndRemainingSchedule() {
        // 1,200,000 at 2%/month for 12 months: 24,000 interest per instalment, 124,000 per instalment.
        Position p = ImportedLoanPositionCalculator.calculate(
                monthly("1200000", "2", LocalDate.of(2026, 1, 5), 12, "600000", "650000"));

        assertThat(p.totalInterest()).isEqualByComparingTo("288000.00");
        assertThat(p.principalPaid()).isEqualByComparingTo("550000.00"); // loan - outstanding
        assertThat(p.interestPaid()).isEqualByComparingTo("50000.00"); // paid - principal paid
        assertThat(p.outstandingPrincipal()).isEqualByComparingTo("650000.00");
        assertThat(p.outstandingInterest()).isEqualByComparingTo("238000.00");
        assertThat(p.dueDate()).isEqualTo(LocalDate.of(2027, 1, 5));
        assertThat(p.equalInstallmentAmount()).isEqualByComparingTo("124000.00");
        assertThat(sumPrincipal(p)).isEqualByComparingTo("650000.00");
        assertThat(sumInterest(p)).isEqualByComparingTo("238000.00");
    }

    @Test
    void remainingScheduleKeepsOriginalNumbersAndDatesAndSkipsFullyPaidInstallments() {
        Position p = ImportedLoanPositionCalculator.calculate(
                monthly("1200000", "2", LocalDate.of(2026, 1, 5), 12, "600000", "650000"));

        // Instalments 1 and 2 (100,000 principal + 24,000 interest each) are fully historical.
        List<RemainingInstallment> rows = p.installments();
        assertThat(rows).hasSize(10);
        assertThat(rows.get(0).installmentNumber()).isEqualTo(3);
        assertThat(rows.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 4, 5));
        // Instalment 3: principal fully consumed, interest only 2,000 of 24,000 consumed.
        assertThat(rows.get(0).principalDue()).isEqualByComparingTo("0.00");
        assertThat(rows.get(0).interestDue()).isEqualByComparingTo("22000.00");
        // Instalment 6 straddles the historical principal (500,000 consumed through #5, 50,000 into #6).
        RemainingInstallment sixth = rows.get(3);
        assertThat(sixth.installmentNumber()).isEqualTo(6);
        assertThat(sixth.principalDue()).isEqualByComparingTo("50000.00");
        assertThat(sixth.interestDue()).isEqualByComparingTo("24000.00");
        assertThat(rows.get(rows.size() - 1).installmentNumber()).isEqualTo(12);
        assertThat(rows.get(rows.size() - 1).dueDate()).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void openingPrincipalBalancesStartAtOutstandingPrincipalAndChain() {
        Position p = ImportedLoanPositionCalculator.calculate(
                monthly("1200000", "2", LocalDate.of(2026, 1, 5), 12, "600000", "650000"));
        BigDecimal running = new BigDecimal("650000.00");
        for (RemainingInstallment row : p.installments()) {
            assertThat(row.openingPrincipalBalance()).isEqualByComparingTo(running);
            running = running.subtract(row.principalDue());
        }
        assertThat(running).isEqualByComparingTo("0.00");
    }

    @Test
    void noPaymentMonthlyLoan_keepsTheFullOriginalSchedule() {
        Position p = ImportedLoanPositionCalculator.calculate(
                monthly("300000", "2", LocalDate.of(2026, 9, 15), 5, "0", "300000"));
        assertThat(p.principalPaid()).isEqualByComparingTo("0.00");
        assertThat(p.interestPaid()).isEqualByComparingTo("0.00");
        assertThat(p.installments()).hasSize(5);
        assertThat(p.installments().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(sumPrincipal(p)).isEqualByComparingTo("300000.00");
        assertThat(sumInterest(p)).isEqualByComparingTo("30000.00");
        assertThat(p.status()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void monthlyLoanWithDueInstallmentBeforeCutover_isOverdueWithoutInventingPayments() {
        // Nothing paid, first instalment fell due on 2026-09-05 (< cutover).
        Position p = ImportedLoanPositionCalculator.calculate(
                monthly("300000", "2", LocalDate.of(2026, 8, 5), 5, "0", "300000"));
        assertThat(p.status()).isEqualTo(LoanStatus.OVERDUE);
        assertThat(p.interestPaid()).isEqualByComparingTo("0.00");
    }

    @Test
    void monthlyLoanWhoseDueInstallmentsAreAllPaid_isActive() {
        // 5 x 66,000; first two instalments paid in full (132,000 = 120,000 principal + 12,000 interest).
        Position p = ImportedLoanPositionCalculator.calculate(
                monthly("300000", "2", LocalDate.of(2026, 7, 15), 5, "132000", "180000"));
        // #1 due 08-15, #2 due 09-15 are consumed; #3 due 10-15 is after the cutover.
        assertThat(p.installments().get(0).installmentNumber()).isEqualTo(3);
        assertThat(p.status()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void scheduleRoundingReconcilesToTheCent_forAwkwardAmountsAndMonthEnd() {
        Position p = ImportedLoanPositionCalculator.calculate(monthly(
                "1000001", "1.5", LocalDate.of(2026, 3, 17), 7, "123457", "900001", LoanRepaymentDateModel.MONTH_END));
        assertThat(p.prorataEnabled()).isTrue();
        assertThat(p.principalPaid()).isEqualByComparingTo("100000.00");
        assertThat(sumPrincipal(p)).isEqualByComparingTo("900001.00");
        assertThat(sumInterest(p)).isEqualByComparingTo(p.outstandingInterest());
        assertThat(p.outstandingInterest().add(p.interestPaid())).isEqualByComparingTo(p.totalInterest());
        // Month-end dates.
        assertThat(p.installments().get(p.installments().size() - 1).dueDate())
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void finalInstallmentAbsorbsRoundingSoPrincipalAndInterestAreExact() {
        Position p = ImportedLoanPositionCalculator.calculate(
                monthly("100000", "3.3333", LocalDate.of(2026, 5, 31), 7, "0", "100000"));
        RemainingInstallment last = p.installments().get(p.installments().size() - 1);
        assertThat(sumPrincipal(p)).isEqualByComparingTo("100000.00");
        assertThat(sumInterest(p)).isEqualByComparingTo(p.totalInterest());
        assertThat(last.openingPrincipalBalance()).isEqualByComparingTo(last.principalDue());
    }

    @Test
    void outstandingPrincipalAboveLoanAmount_isRejected() {
        assertThatThrownBy(() -> ImportedLoanPositionCalculator.calculate(
                        monthly("100000", "2", LocalDate.of(2026, 5, 1), 6, "0", "100000.01")))
                .isInstanceOf(PositionException.class)
                .hasMessageContaining("cannot exceed the original loan amount")
                .extracting(e -> ((PositionException) e).field())
                .isEqualTo(ImportedLoanPositionCalculator.F_OUTSTANDING);
    }

    @Test
    void amountPaidBelowPrincipalAlreadyRepaid_isInconsistent() {
        // principal paid = 1,000,000 - 400,000 = 600,000 but only 500,000 was paid in total.
        assertThatThrownBy(() -> ImportedLoanPositionCalculator.calculate(
                        monthly("1000000", "2", LocalDate.of(2026, 1, 5), 12, "500000", "400000")))
                .isInstanceOf(PositionException.class)
                .hasMessageContaining("inconsistent")
                .extracting(e -> ((PositionException) e).field())
                .isEqualTo(ImportedLoanPositionCalculator.F_PAID);
    }

    @Test
    void interestPaidAboveTotalInterest_isRejected() {
        assertThatThrownBy(() -> ImportedLoanPositionCalculator.calculate(
                        monthly("100000", "2", LocalDate.of(2026, 5, 1), 6, "999999", "100000")))
                .isInstanceOf(PositionException.class)
                .hasMessageContaining("exceeds the total interest");
    }

    @Test
    void fullyRepaidLoan_isRejectedBecauseOnlyOutstandingLoansAreImported() {
        // 100,000 + 12,000 interest, all paid.
        assertThatThrownBy(() -> ImportedLoanPositionCalculator.calculate(
                        monthly("100000", "2", LocalDate.of(2026, 5, 1), 6, "112000", "0")))
                .isInstanceOf(PositionException.class)
                .hasMessageContaining("fully repaid");
    }

    @Test
    void zeroOutstandingPrincipalWithInterestLeft_keepsInterestOnlyInstallments() {
        Position p = ImportedLoanPositionCalculator.calculate(
                monthly("100000", "2", LocalDate.of(2026, 5, 1), 6, "100000", "0"));
        assertThat(p.outstandingPrincipal()).isEqualByComparingTo("0.00");
        assertThat(p.outstandingInterest()).isEqualByComparingTo("12000.00");
        assertThat(sumInterest(p)).isEqualByComparingTo("12000.00");
        assertThat(sumPrincipal(p)).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ END_OF_TERM

    @Test
    void endOfTermLoanNotYetDue_isActiveWithOneFinalObligation() {
        // 100,000 at 3%/month for 6 months, issued 2026-06-30 -> due 2026-12-30; interest 3,000 x 6.
        Position p = ImportedLoanPositionCalculator.calculate(
                endOfTerm("100000", "3", LocalDate.of(2026, 6, 30), 6, "0", "100000"));
        assertThat(p.dueDate()).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(p.status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(p.outstandingPrincipal()).isEqualByComparingTo("100000.00");
        assertThat(p.outstandingInterest()).isEqualByComparingTo("18000.00");
        assertThat(p.installments()).hasSize(1);
        RemainingInstallment only = p.installments().get(0);
        assertThat(only.installmentNumber()).isEqualTo(1);
        assertThat(only.dueDate()).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(only.principalDue()).isEqualByComparingTo("100000.00");
        assertThat(only.interestDue()).isEqualByComparingTo("18000.00");
    }

    @Test
    void endOfTermLoanPastDue_isOverdue() {
        Position p = ImportedLoanPositionCalculator.calculate(
                endOfTerm("100000", "3", LocalDate.of(2026, 1, 31), 6, "0", "100000"));
        assertThat(p.dueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(p.status()).isEqualTo(LoanStatus.OVERDUE);
    }

    @Test
    void endOfTermLoanDueExactlyOnCutover_isStillActive() {
        Position p = ImportedLoanPositionCalculator.calculate(
                endOfTerm("100000", "3", LocalDate.of(2026, 3, 30), 6, "0", "100000"));
        assertThat(p.dueDate()).isEqualTo(CUTOVER);
        assertThat(p.status()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void endOfTermLoanWithPartialPayment_reducesTheSingleObligation() {
        Position p = ImportedLoanPositionCalculator.calculate(
                endOfTerm("100000", "3", LocalDate.of(2026, 6, 30), 6, "25000", "90000"));
        // 10,000 principal repaid, 15,000 of the 18,000 interest repaid.
        assertThat(p.principalPaid()).isEqualByComparingTo("10000.00");
        assertThat(p.interestPaid()).isEqualByComparingTo("15000.00");
        assertThat(p.installments()).hasSize(1);
        assertThat(p.installments().get(0).principalDue()).isEqualByComparingTo("90000.00");
        assertThat(p.installments().get(0).interestDue()).isEqualByComparingTo("3000.00");
    }

    private static BigDecimal sumPrincipal(Position p) {
        return p.installments().stream().map(RemainingInstallment::principalDue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumInterest(Position p) {
        return p.installments().stream().map(RemainingInstallment::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
