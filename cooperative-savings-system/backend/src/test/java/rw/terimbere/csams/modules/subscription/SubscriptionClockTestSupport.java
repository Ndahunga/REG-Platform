package rw.terimbere.csams.modules.subscription;

import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.service.SubscriptionCalendar;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;

/**
 * Keeps frozen-clock integration tests from accidentally expiring a trial that was
 * initialized at a much earlier fixture date.
 */
public final class SubscriptionClockTestSupport {

    private SubscriptionClockTestSupport() {}

    public static Instant freeze(Clock clock, LocalDate date) {
        Instant instant = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        when(clock.instant()).thenReturn(instant);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        return instant;
    }

    /**
     * Realigns an existing usable subscription so it remains valid at {@code now}.
     * Does not convert NONE/EXPIRED rows into a trial.
     */
    public static void keepUsable(
            CooperativeSubscriptionRepository repository,
            SubscriptionPricing pricing,
            UUID cooperativeId,
            Instant now) {
        if (repository == null || pricing == null || cooperativeId == null || now == null) {
            return;
        }
        repository.findByCooperativeId(cooperativeId).ifPresent(subscription -> keepUsable(repository, pricing, subscription, now));
    }

    private static void keepUsable(
            CooperativeSubscriptionRepository repository,
            SubscriptionPricing pricing,
            CooperativeSubscription subscription,
            Instant now) {
        SubscriptionStatus status =
                subscription.getStatus() == null ? SubscriptionStatus.NONE : subscription.getStatus();
        switch (status) {
            case TRIAL -> {
                subscription.setTrialStartedAt(now);
                subscription.setTrialEndsAt(
                        SubscriptionCalendar.plusCalendarMonths(now, pricing.trialMonths(), pricing.zoneId()));
                repository.saveAndFlush(subscription);
            }
            case ACTIVE, CANCELED -> {
                Instant ends = subscription.getCurrentPeriodEndsAt();
                if (ends == null || !ends.isAfter(now)) {
                    subscription.setCurrentPeriodStartedAt(now);
                    subscription.setCurrentPeriodEndsAt(
                            SubscriptionCalendar.plusCalendarMonths(now, 1, pricing.zoneId()));
                    repository.saveAndFlush(subscription);
                }
            }
            case PAST_DUE -> {
                Instant until = subscription.getPastDueUntil();
                if (until == null || !until.isAfter(now)) {
                    subscription.setPastDueUntil(now.plusSeconds(86_400));
                    repository.saveAndFlush(subscription);
                }
            }
            case NONE, EXPIRED -> {
                // Intentionally unusable; tests that need writes should not freeze into these states.
            }
        }
    }
}
