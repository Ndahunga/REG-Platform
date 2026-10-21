package rw.terimbere.csams.modules.loan.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleResponse;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Derives the OuWealth loan state for a loan that was issued and partly repaid before the cutover
 * date (opening-balance migration).
 *
 * <p>The platform's FLAT rules stay authoritative. The <em>original</em> contract is rebuilt with
 * {@link LoanScheduleCalculator} from the original principal, monthly rate, term and issue date, so
 * total interest and every instalment component keep their original meaning. The historical amounts
 * are then <em>consumed</em> from that original schedule, component by component and in instalment
 * order:
 *
 * <pre>
 * principalPaid       = loanAmount - outstandingPrincipal
 * interestPaid        = amountPaid - principalPaid     (must be &gt;= 0 and &lt;= total interest)
 * outstandingInterest = totalInterest - interestPaid
 * </pre>
 *
 * Instalments whose principal and interest are both fully consumed are historical and are not
 * created. The rest (a partly consumed one is reduced to what is still owed) form the remaining
 * schedule, keeping their original instalment numbers and due dates. Their principal sums to the
 * outstanding principal and their interest to the outstanding interest, exactly. No repayment is
 * manufactured.
 *
 * <p>END_OF_TERM loans have one obligation at {@code issued + term}: outstanding principal plus
 * outstanding FLAT interest (monthly rate x principal x term).
 */
public final class ImportedLoanPositionCalculator {

    public enum RepaymentType {
        END_OF_TERM,
        MONTHLY
    }

    public record Input(
            RepaymentType type,
            BigDecimal loanAmount,
            BigDecimal monthlyRatePercent,
            LocalDate issuedDate,
            int termMonths,
            BigDecimal amountPaid,
            BigDecimal outstandingPrincipal,
            LocalDate cutoverDate,
            LoanRepaymentDateModel repaymentDateModel) {}

    public record RemainingInstallment(
            int installmentNumber,
            LocalDate dueDate,
            BigDecimal openingPrincipalBalance,
            BigDecimal principalDue,
            BigDecimal interestDue) {

        public BigDecimal totalDue() {
            return MoneyUtils.add(principalDue, interestDue);
        }
    }

    public record Position(
            BigDecimal principalAmount,
            BigDecimal totalInterest,
            BigDecimal principalPaid,
            BigDecimal interestPaid,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            LocalDate dueDate,
            LoanStatus status,
            LoanRepaymentDateModel repaymentDateModel,
            boolean prorataEnabled,
            Integer firstPeriodDays,
            BigDecimal equalInstallmentAmount,
            List<RemainingInstallment> installments) {}

    /** A rule violation tied to the sheet column it comes from. */
    public static final class PositionException extends RuntimeException {
        private final String field;

        public PositionException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    public static final String F_PAID = "Amount Paid to Date (RWF)";
    public static final String F_OUTSTANDING = "Outstanding Principal (RWF)";

    private ImportedLoanPositionCalculator() {}

    public static Position calculate(Input in) {
        BigDecimal loanAmount = MoneyUtils.scale(in.loanAmount());
        BigDecimal outstandingPrincipal = MoneyUtils.scale(in.outstandingPrincipal());
        BigDecimal amountPaid = MoneyUtils.scale(in.amountPaid());
        if (outstandingPrincipal.compareTo(BigDecimal.ZERO) < 0) {
            throw new PositionException(F_OUTSTANDING, "Outstanding principal cannot be negative.");
        }
        if (outstandingPrincipal.compareTo(loanAmount) > 0) {
            throw new PositionException(F_OUTSTANDING, "Outstanding principal cannot exceed the original loan amount.");
        }
        BigDecimal principalPaid = MoneyUtils.subtract(loanAmount, outstandingPrincipal);
        BigDecimal interestPaid = MoneyUtils.subtract(amountPaid, principalPaid);
        if (interestPaid.compareTo(BigDecimal.ZERO) < 0) {
            throw new PositionException(
                    F_PAID,
                    "Amount paid to date (" + amountPaid + ") is less than the principal already repaid ("
                            + principalPaid + " = loan amount - outstanding principal). The balances are inconsistent.");
        }

        boolean monthly = in.type() == RepaymentType.MONTHLY;
        LoanRepaymentDateModel model = !monthly || in.repaymentDateModel() == null
                ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH
                : in.repaymentDateModel();
        LoanScheduleResponse original = LoanScheduleCalculator.calculate(
                loanAmount, in.monthlyRatePercent(), in.termMonths(), model, in.issuedDate());
        BigDecimal totalInterest = MoneyUtils.scale(original.getTotalInterest());
        if (interestPaid.compareTo(totalInterest) > 0) {
            throw new PositionException(
                    F_PAID,
                    "Amount paid to date implies interest paid of " + interestPaid
                            + ", which exceeds the total interest of the loan (" + totalInterest + ").");
        }
        BigDecimal outstandingInterest = MoneyUtils.subtract(totalInterest, interestPaid);
        if (MoneyUtils.isZero(outstandingPrincipal) && MoneyUtils.isZero(outstandingInterest)) {
            throw new PositionException(
                    F_OUTSTANDING,
                    "This loan is fully repaid. Only loans that are still outstanding at the cutover date are imported.");
        }

        List<LoanInstallmentResponse> originalRows = original.getInstallments();
        LocalDate finalDueDate = originalRows.get(originalRows.size() - 1).getDueDate();
        List<RemainingInstallment> remaining = new ArrayList<>();
        if (monthly) {
            BigDecimal principalToConsume = principalPaid;
            BigDecimal interestToConsume = interestPaid;
            BigDecimal running = outstandingPrincipal;
            for (LoanInstallmentResponse row : originalRows) {
                BigDecimal principalConsumed = row.getPrincipalComponent().min(principalToConsume);
                BigDecimal interestConsumed = row.getInterestComponent().min(interestToConsume);
                principalToConsume = MoneyUtils.subtract(principalToConsume, principalConsumed);
                interestToConsume = MoneyUtils.subtract(interestToConsume, interestConsumed);
                BigDecimal principalDue = MoneyUtils.subtract(row.getPrincipalComponent(), principalConsumed);
                BigDecimal interestDue = MoneyUtils.subtract(row.getInterestComponent(), interestConsumed);
                if (MoneyUtils.isZero(principalDue) && MoneyUtils.isZero(interestDue)) {
                    continue;
                }
                remaining.add(new RemainingInstallment(
                        row.getInstallmentNumber(), row.getDueDate(), running, principalDue, interestDue));
                running = MoneyUtils.subtract(running, principalDue);
            }
        } else {
            remaining.add(new RemainingInstallment(
                    1, finalDueDate, outstandingPrincipal, outstandingPrincipal, outstandingInterest));
        }

        boolean behind = remaining.stream().anyMatch(r -> r.dueDate().isBefore(in.cutoverDate()));
        BigDecimal equalInstallment = monthly
                ? MoneyUtils.scale(original.getEqualInstallmentAmount())
                : MoneyUtils.add(outstandingPrincipal, outstandingInterest);
        return new Position(
                loanAmount,
                totalInterest,
                principalPaid,
                interestPaid,
                outstandingPrincipal,
                outstandingInterest,
                finalDueDate,
                behind ? LoanStatus.OVERDUE : LoanStatus.ACTIVE,
                model,
                monthly && original.isProrataEnabled(),
                monthly ? original.getFirstPeriodDays() : null,
                equalInstallment,
                List.copyOf(remaining));
    }
}
