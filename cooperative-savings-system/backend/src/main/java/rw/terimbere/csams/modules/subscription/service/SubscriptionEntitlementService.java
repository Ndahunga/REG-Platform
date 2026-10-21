package rw.terimbere.csams.modules.subscription.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.subscription.dto.SubscriptionEntitlement;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.shared.exceptions.SubscriptionInactiveException;

/**
 * Authoritative cooperative subscription entitlement. Effective status is derived from
 * stored status plus dates so access never depends on a scheduler.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionEntitlementService {

    private final CooperativeSubscriptionRepository subscriptionRepository;
    private final SubscriptionPricing pricing;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SubscriptionEntitlement getEntitlement(UUID cooperativeId) {
        if (cooperativeId == null) {
            return SubscriptionEntitlement.none(null);
        }
        return subscriptionRepository
                .findByCooperativeId(cooperativeId)
                .map(this::evaluate)
                .orElseGet(() -> SubscriptionEntitlement.none(cooperativeId));
    }

    public SubscriptionEntitlement evaluate(CooperativeSubscription subscription) {
        return evaluate(subscription, clock.instant());
    }

    public SubscriptionEntitlement evaluate(CooperativeSubscription subscription, Instant now) {
        if (subscription == null) {
            return SubscriptionEntitlement.none(null);
        }
        Instant instant = now == null ? clock.instant() : now;
        SubscriptionStatus stored =
                subscription.getStatus() == null ? SubscriptionStatus.NONE : subscription.getStatus();
        Effective computed = derive(stored, subscription, instant);
        return SubscriptionEntitlement.builder()
                .cooperativeId(subscription.getCooperativeId())
                .storedStatus(stored)
                .effectiveStatus(computed.status())
                .usable(computed.writeAllowed())
                .writeAllowed(computed.writeAllowed())
                .billingCycle(subscription.getBillingCycle())
                .trialStartedAt(subscription.getTrialStartedAt())
                .trialEndsAt(subscription.getTrialEndsAt())
                .currentPeriodStartedAt(subscription.getCurrentPeriodStartedAt())
                .currentPeriodEndsAt(subscription.getCurrentPeriodEndsAt())
                .pastDueUntil(subscription.getPastDueUntil())
                .canceledAt(subscription.getCanceledAt())
                .daysRemaining(daysRemaining(computed.accessUntil(), instant))
                .build();
    }

    @Transactional(readOnly = true)
    public boolean isOperationalAccessAllowed(UUID cooperativeId) {
        return getEntitlement(cooperativeId).usable();
    }

    @Transactional(readOnly = true)
    public boolean isWriteAllowed(UUID cooperativeId) {
        return getEntitlement(cooperativeId).writeAllowed();
    }

    @Transactional(readOnly = true)
    public void requireWriteAllowed(UUID cooperativeId) {
        SubscriptionEntitlement entitlement = getEntitlement(cooperativeId);
        if (!entitlement.writeAllowed()) {
            throw new SubscriptionInactiveException(cooperativeId, entitlement.effectiveStatus());
        }
    }

    private Effective derive(SubscriptionStatus stored, CooperativeSubscription subscription, Instant now) {
        return switch (stored) {
            case TRIAL -> stillOpen(subscription.getTrialEndsAt(), now)
                    ? Effective.usable(SubscriptionStatus.TRIAL, subscription.getTrialEndsAt())
                    : Effective.expired();
            case ACTIVE -> stillOpen(subscription.getCurrentPeriodEndsAt(), now)
                    ? Effective.usable(SubscriptionStatus.ACTIVE, subscription.getCurrentPeriodEndsAt())
                    : Effective.expired();
            case PAST_DUE -> stillOpen(subscription.getPastDueUntil(), now)
                    ? Effective.usable(SubscriptionStatus.PAST_DUE, subscription.getPastDueUntil())
                    : Effective.expired();
            case CANCELED -> stillOpen(subscription.getCurrentPeriodEndsAt(), now)
                    ? Effective.usable(SubscriptionStatus.CANCELED, subscription.getCurrentPeriodEndsAt())
                    : Effective.expired();
            case EXPIRED -> Effective.expired();
            case NONE -> Effective.none();
        };
    }

    /**
     * Inclusive-open window: access holds while {@code now} is strictly before the deadline.
     * A missing deadline fails closed so ACTIVE/TRIAL/PAST_DUE/CANCELED cannot remain usable forever.
     */
    static boolean stillOpen(Instant deadline, Instant now) {
        return deadline != null && now != null && now.isBefore(deadline);
    }

    private Integer daysRemaining(Instant accessUntil, Instant now) {
        if (accessUntil == null || now == null || !now.isBefore(accessUntil)) {
            return null;
        }
        ZoneId zone = pricing.zoneId();
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate end = accessUntil.atZone(zone).toLocalDate();
        long days = ChronoUnit.DAYS.between(today, end);
        return (int) Math.max(days, 0);
    }

    private record Effective(SubscriptionStatus status, boolean writeAllowed, Instant accessUntil) {
        static Effective usable(SubscriptionStatus status, Instant accessUntil) {
            return new Effective(status, true, accessUntil);
        }

        static Effective expired() {
            return new Effective(SubscriptionStatus.EXPIRED, false, null);
        }

        static Effective none() {
            return new Effective(SubscriptionStatus.NONE, false, null);
        }
    }
}
