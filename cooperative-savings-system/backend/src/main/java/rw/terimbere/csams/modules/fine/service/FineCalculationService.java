package rw.terimbere.csams.modules.fine.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
public class FineCalculationService {

    /**
     * Progressive formula: TotalFine = BaseFine + (OverdueDays × DailyIncrement).
     */
    public BigDecimal calculateProgressive(BigDecimal base, BigDecimal dailyIncrement, int overdueDays) {
        BigDecimal baseScaled = MoneyUtils.scaleForStorage(base == null ? BigDecimal.ZERO : base);
        BigDecimal increment = MoneyUtils.scaleForStorage(dailyIncrement == null ? BigDecimal.ZERO : dailyIncrement);
        int days = Math.max(0, overdueDays);
        BigDecimal variable = MoneyUtils.scaleForStorage(
                increment.multiply(BigDecimal.valueOf(days)));
        return MoneyUtils.scale(MoneyUtils.scaleForStorage(baseScaled.add(variable)));
    }

    /**
     * Overdue days counted from (contributionDueDate + graceDays) until asOf (inclusive gap).
     * Returns 0 when asOf is on or before the grace end date.
     */
    public int computeOverdueDays(LocalDate contributionDueDate, int graceDays, LocalDate asOf) {
        if (contributionDueDate == null || asOf == null) {
            return 0;
        }
        LocalDate graceEnd = contributionDueDate.plusDays(Math.max(0, graceDays));
        long days = ChronoUnit.DAYS.between(graceEnd, asOf);
        return days > 0 ? (int) days : 0;
    }

    public LocalDate contributionDueDate(int year, int month, int contributionDueDay) {
        int maxDay = LocalDate.of(year, month, 1).lengthOfMonth();
        int day = Math.min(Math.max(1, contributionDueDay), maxDay);
        return LocalDate.of(year, month, day);
    }
}
