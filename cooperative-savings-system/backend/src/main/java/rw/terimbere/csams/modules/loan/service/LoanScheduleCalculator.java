package rw.terimbere.csams.modules.loan.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import rw.terimbere.csams.modules.loan.dto.LoanInstallmentResponse;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleResponse;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Authoritative FLAT monthly interest schedule. Interest is always
 * {@code originalPrincipal × monthlyRate}; it is never declining-balance.
 *
 * <p>{@link LoanRepaymentDateModel#SAME_DAY_OF_MONTH} uses a full first month.
 * {@link LoanRepaymentDateModel#MONTH_END} prorates the first period with the
 * actual calendar-month length:
 * {@code firstPeriodInterest = monthly × DAYS.between(start, monthEnd) / lengthOfMonth}.
 */
public final class LoanScheduleCalculator {

    private static final MathContext MATH_CONTEXT = new MathContext(19, MoneyUtils.ROUNDING);

    private LoanScheduleCalculator() {}

    public static LoanScheduleResponse calculate(
            BigDecimal principal,
            BigDecimal monthlyRatePercent,
            int numberOfInstallments,
            LoanRepaymentDateModel model,
            LocalDate scheduleStartDate) {
        if (principal == null || principal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Loan principal must be greater than 0");
        }
        if (monthlyRatePercent == null || monthlyRatePercent.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("Monthly interest rate must be 0 or greater");
        }
        if (numberOfInstallments <= 0) {
            throw new ValidationException("Number of installments must be greater than 0");
        }
        if (scheduleStartDate == null) {
            throw new ValidationException("A schedule start / disbursement date is required");
        }

        LoanRepaymentDateModel resolved =
                model == null ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH : model;
        boolean prorataEnabled = resolved == LoanRepaymentDateModel.MONTH_END;

        BigDecimal scaledPrincipal = MoneyUtils.scale(principal);
        BigDecimal regularMonthlyInterest = MoneyUtils.percentage(scaledPrincipal, monthlyRatePercent);
        int firstPeriodDays = 0;
        int daysInMonth = scheduleStartDate.lengthOfMonth();
        BigDecimal firstPeriodInterest = regularMonthlyInterest;
        if (prorataEnabled) {
            LocalDate monthEnd = scheduleStartDate.withDayOfMonth(daysInMonth);
            firstPeriodDays = (int) ChronoUnit.DAYS.between(scheduleStartDate, monthEnd);
            firstPeriodInterest = prorateMonthlyInterest(regularMonthlyInterest, firstPeriodDays, daysInMonth);
        }

        BigDecimal remainingFullPeriodInterest = numberOfInstallments == 1
                ? BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE)
                : MoneyUtils.scale(
                        regularMonthlyInterest.multiply(BigDecimal.valueOf(numberOfInstallments - 1L), MATH_CONTEXT));
        BigDecimal totalInterest = MoneyUtils.add(firstPeriodInterest, remainingFullPeriodInterest);
        BigDecimal totalRepayment = MoneyUtils.add(scaledPrincipal, totalInterest);
        BigDecimal equalInstallment = MoneyUtils.divide(totalRepayment, BigDecimal.valueOf(numberOfInstallments));
        List<LocalDate> dueDates = LoanRepaymentDates.generate(resolved, scheduleStartDate, numberOfInstallments);

        List<LoanInstallmentResponse> installments = new ArrayList<>(numberOfInstallments);
        BigDecimal allocatedPrincipal = BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE);
        BigDecimal allocatedInterest = BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE);
        BigDecimal allocatedPayment = BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE);
        BigDecimal openingPrincipal = scaledPrincipal;

        for (int i = 1; i <= numberOfInstallments; i++) {
            boolean last = i == numberOfInstallments;
            BigDecimal interestComponent = last
                    ? MoneyUtils.subtract(totalInterest, allocatedInterest)
                    : (i == 1 ? firstPeriodInterest : regularMonthlyInterest);
            BigDecimal paymentAmount = last
                    ? MoneyUtils.subtract(totalRepayment, allocatedPayment)
                    : equalInstallment;
            BigDecimal principalComponent = last
                    ? MoneyUtils.subtract(scaledPrincipal, allocatedPrincipal)
                    : MoneyUtils.subtract(paymentAmount, interestComponent);
            allocatedPrincipal = MoneyUtils.add(allocatedPrincipal, principalComponent);
            allocatedInterest = MoneyUtils.add(allocatedInterest, interestComponent);
            allocatedPayment = MoneyUtils.add(allocatedPayment, paymentAmount);
            BigDecimal remainingPrincipal = MoneyUtils.subtract(scaledPrincipal, allocatedPrincipal);

            installments.add(LoanInstallmentResponse.builder()
                    .installmentNumber(i)
                    .dueDate(dueDates.get(i - 1))
                    .openingPrincipalBalance(openingPrincipal)
                    .paymentAmount(paymentAmount)
                    .scheduledInstallmentAmount(paymentAmount)
                    .principalComponent(principalComponent)
                    .interestComponent(interestComponent)
                    .penaltyDue(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE))
                    .remainingPrincipal(remainingPrincipal)
                    .status(LoanInstallmentStatus.PENDING)
                    .amountPaid(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE))
                    .balance(paymentAmount)
                    .principalPaid(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE))
                    .interestPaid(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE))
                    .penaltyPaid(BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE))
                    .remainingAmount(paymentAmount)
                    .build());
            openingPrincipal = remainingPrincipal;
        }

        return LoanScheduleResponse.builder()
                .principal(scaledPrincipal)
                .monthlyInterestRatePercent(MoneyUtils.scale(monthlyRatePercent))
                .numberOfInstallments(numberOfInstallments)
                .repaymentDateModel(resolved)
                .prorataEnabled(prorataEnabled)
                .firstPeriodDays(prorataEnabled ? firstPeriodDays : null)
                .daysInFirstMonth(prorataEnabled ? daysInMonth : null)
                .regularMonthlyInterest(regularMonthlyInterest)
                .firstPeriodInterest(firstPeriodInterest)
                .totalInterest(totalInterest)
                .totalRepayment(totalRepayment)
                .equalInstallmentAmount(equalInstallment)
                .scheduleFinalized(false)
                .installments(List.copyOf(installments))
                .build();
    }

    private static BigDecimal prorateMonthlyInterest(
            BigDecimal monthlyInterest, int remainingDays, int daysInMonth) {
        if (remainingDays <= 0 || daysInMonth <= 0) {
            return BigDecimal.ZERO.setScale(MoneyUtils.MONEY_SCALE);
        }
        if (remainingDays == daysInMonth) {
            return MoneyUtils.scale(monthlyInterest);
        }
        return MoneyUtils.scale(monthlyInterest
                .multiply(BigDecimal.valueOf(remainingDays), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(daysInMonth), MATH_CONTEXT));
    }
}
