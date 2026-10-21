package rw.terimbere.csams.modules.subscription.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;

@Builder
public record SubscriptionEntitlement(
        UUID cooperativeId,
        SubscriptionStatus storedStatus,
        SubscriptionStatus effectiveStatus,
        boolean usable,
        boolean writeAllowed,
        SubscriptionBillingCycle billingCycle,
        Instant trialStartedAt,
        Instant trialEndsAt,
        Instant currentPeriodStartedAt,
        Instant currentPeriodEndsAt,
        Instant pastDueUntil,
        Instant canceledAt,
        Integer daysRemaining) {

    public static SubscriptionEntitlement none(UUID cooperativeId) {
        return SubscriptionEntitlement.builder()
                .cooperativeId(cooperativeId)
                .storedStatus(SubscriptionStatus.NONE)
                .effectiveStatus(SubscriptionStatus.NONE)
                .usable(false)
                .writeAllowed(false)
                .build();
    }
}
