package rw.terimbere.csams.shared.exceptions;

import java.util.UUID;
import lombok.Getter;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;

@Getter
public class SubscriptionInactiveException extends RuntimeException {

    public static final String CODE = "SUBSCRIPTION_INACTIVE";

    private final UUID cooperativeId;
    private final SubscriptionStatus subscriptionStatus;

    public SubscriptionInactiveException(UUID cooperativeId, SubscriptionStatus subscriptionStatus) {
        super("This Saving Scheme's OuWealth subscription is inactive.");
        this.cooperativeId = cooperativeId;
        this.subscriptionStatus = subscriptionStatus;
    }
}
