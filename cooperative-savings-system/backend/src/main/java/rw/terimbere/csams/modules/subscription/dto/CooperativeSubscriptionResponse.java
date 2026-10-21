package rw.terimbere.csams.modules.subscription.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CooperativeSubscriptionResponse {

    private UUID id;
    private UUID cooperativeId;
    private SubscriptionStatus status;
    private SubscriptionStatus storedStatus;
    private SubscriptionStatus effectiveStatus;
    private boolean usable;
    private boolean writeAllowed;
    private SubscriptionBillingCycle billingCycle;
    private Instant trialStartedAt;
    private Instant trialEndsAt;
    private Instant currentPeriodStartedAt;
    private Instant currentPeriodEndsAt;
    private Instant pastDueUntil;
    private Instant canceledAt;
    private Integer daysRemaining;
    private Instant createdAt;
    private Instant updatedAt;
}
