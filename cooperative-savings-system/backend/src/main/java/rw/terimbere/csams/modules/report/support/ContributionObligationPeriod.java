package rw.terimbere.csams.modules.report.support;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Maps FULL_FINANCIAL from/to (or explicit year/month) onto contribution obligation YearMonths.
 */
public final class ContributionObligationPeriod {

    private ContributionObligationPeriod() {}

    public record Range(YearMonth from, YearMonth to, boolean singleMonth) {
        public int fromYear() {
            return from.getYear();
        }

        public int fromMonth() {
            return from.getMonthValue();
        }

        public int toYear() {
            return to.getYear();
        }

        public int toMonth() {
            return to.getMonthValue();
        }

        /** Inclusive count of calendar months in the selected range (not persisted periods). */
        public int selectedMonthSpan() {
            return (int) (from.until(to, java.time.temporal.ChronoUnit.MONTHS) + 1);
        }

        public String singleMonthLabel(Locale locale) {
            return from.getMonth().getDisplayName(TextStyle.FULL, locale) + " " + from.getYear();
        }
    }

    public static Range resolve(ReportExportRequest request) {
        if (request == null) {
            throw new ValidationException("report request is required");
        }
        if (request.getYear() != null && request.getMonth() != null) {
            YearMonth ym = YearMonth.of(request.getYear(), request.getMonth());
            return new Range(ym, ym, true);
        }
        LocalDate fromDate = request.getFromDate();
        LocalDate toDate = request.getToDate();
        if (fromDate == null || toDate == null) {
            throw new ValidationException("fromDate and toDate are required");
        }
        if (toDate.isBefore(fromDate)) {
            throw new ValidationException("toDate must be on or after fromDate");
        }
        YearMonth from = YearMonth.from(fromDate);
        YearMonth to = YearMonth.from(toDate);
        return new Range(from, to, from.equals(to));
    }
}
