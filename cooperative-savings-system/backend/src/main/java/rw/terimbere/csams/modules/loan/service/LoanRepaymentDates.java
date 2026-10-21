package rw.terimbere.csams.modules.loan.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Generates installment due dates from a disbursement date and Ikimina model.
 *
 * <p>{@link LoanRepaymentDateModel#SAME_DAY_OF_MONTH} always applies the original
 * cycle day with a last-valid-day fallback, so a 31 January start does not
 * permanently drift to the 28th after February.
 */
public final class LoanRepaymentDates {

    private LoanRepaymentDates() {}

    public static List<LocalDate> generate(
            LoanRepaymentDateModel model, LocalDate disbursementDate, int numberOfInstallments) {
        if (disbursementDate == null) {
            throw new ValidationException("Disbursement date is required to generate installment dates");
        }
        if (numberOfInstallments <= 0) {
            throw new ValidationException("Number of installments must be greater than 0");
        }
        LoanRepaymentDateModel resolved = model == null ? LoanRepaymentDateModel.SAME_DAY_OF_MONTH : model;
        return resolved == LoanRepaymentDateModel.MONTH_END
                ? monthEndDates(disbursementDate, numberOfInstallments)
                : sameDayDates(disbursementDate, numberOfInstallments);
    }

    public static LocalDate sameDayOfMonth(LocalDate start, int monthsAfter, int cycleDay) {
        YearMonth month = YearMonth.from(start).plusMonths(monthsAfter);
        return month.atDay(Math.min(cycleDay, month.lengthOfMonth()));
    }

    private static List<LocalDate> sameDayDates(LocalDate disbursementDate, int numberOfInstallments) {
        int cycleDay = disbursementDate.getDayOfMonth();
        List<LocalDate> dates = new ArrayList<>(numberOfInstallments);
        for (int i = 1; i <= numberOfInstallments; i++) {
            dates.add(sameDayOfMonth(disbursementDate, i, cycleDay));
        }
        return List.copyOf(dates);
    }

    private static List<LocalDate> monthEndDates(LocalDate disbursementDate, int numberOfInstallments) {
        YearMonth month = YearMonth.from(disbursementDate);
        List<LocalDate> dates = new ArrayList<>(numberOfInstallments);
        for (int i = 0; i < numberOfInstallments; i++) {
            dates.add(month.plusMonths(i).atEndOfMonth());
        }
        return List.copyOf(dates);
    }
}
