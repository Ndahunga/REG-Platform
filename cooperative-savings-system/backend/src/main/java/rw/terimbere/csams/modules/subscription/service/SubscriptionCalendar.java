package rw.terimbere.csams.modules.subscription.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Calendar helpers for subscription trial and billing periods. */
public final class SubscriptionCalendar {

    public static final ZoneId ZONE_KIGALI = ZoneId.of("Africa/Kigali");

    private SubscriptionCalendar() {}

    public static Instant plusCalendarMonths(Instant instant, int months, ZoneId zone) {
        Objects.requireNonNull(instant, "instant must not be null");
        Objects.requireNonNull(zone, "zone must not be null");
        if (months < 0) {
            throw new IllegalArgumentException("months must be non-negative");
        }
        return instant.atZone(zone).plusMonths(months).toInstant();
    }
}
