package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.subscription.service.SubscriptionCalendar;

class SubscriptionCalendarTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Test
    void plusCalendarMonths_isExactlyFourCalendarMonthsNotFixedDays() {
        Instant start = ZonedDateTime.of(2026, 3, 1, 10, 30, 0, 0, KIGALI).toInstant();
        Instant end = SubscriptionCalendar.plusCalendarMonths(start, 4, KIGALI);

        assertThat(end).isEqualTo(ZonedDateTime.of(2026, 7, 1, 10, 30, 0, 0, KIGALI).toInstant());
        assertThat(end).isNotEqualTo(start.plusSeconds(4L * 30 * 24 * 60 * 60));
    }

    @Test
    void plusCalendarMonths_preservesLocalTimeOnMonthEnd() {
        Instant start = ZonedDateTime.of(2026, 1, 31, 9, 0, 0, 0, KIGALI).toInstant();
        Instant end = SubscriptionCalendar.plusCalendarMonths(start, 4, KIGALI);

        assertThat(end.atZone(KIGALI).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 5, 31, 9, 0, 0));
    }

    @Test
    void plusCalendarMonths_clampsWhenTargetMonthIsShorter() {
        Instant start = ZonedDateTime.of(2026, 10, 31, 8, 0, 0, 0, KIGALI).toInstant();
        Instant end = SubscriptionCalendar.plusCalendarMonths(start, 4, KIGALI);

        assertThat(end.atZone(KIGALI).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2027, 2, 28, 8, 0, 0));
    }
}
