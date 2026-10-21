package rw.terimbere.csams.modules.contribution.obligation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Calendar rules for contribution due dates. Every "today" and "current month" decision in the obligation code goes
 * through {@link #today(Clock)} so it uses the Africa/Kigali calendar and nothing depends on the server's own zone.
 */
public final class ContributionDueDates {

    public static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    private ContributionDueDates() {}

    /**
     * The due date of a contribution period: the configured day of that month, or the month's last day when the month
     * is shorter (day 31 in April is 30 April; day 30 in February is 28 or 29 February).
     */
    public static LocalDate dueDateFor(int year, int month, int dueDay) {
        if (dueDay < 1 || dueDay > 31) {
            throw new IllegalArgumentException("Due day must be between 1 and 31");
        }
        YearMonth period = YearMonth.of(year, month);
        return period.atDay(Math.min(dueDay, period.lengthOfMonth()));
    }

    /** Today's date on the Kigali calendar. */
    public static LocalDate today(Clock clock) {
        return clock.instant().atZone(KIGALI).toLocalDate();
    }

    /** The current month on the Kigali calendar. */
    public static YearMonth currentMonth(Clock clock) {
        return YearMonth.from(clock.instant().atZone(KIGALI));
    }
}
