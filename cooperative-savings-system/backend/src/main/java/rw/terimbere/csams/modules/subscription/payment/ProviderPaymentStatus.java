package rw.terimbere.csams.modules.subscription.payment;

/**
 * Provider-side payment state, mapped onto {@code SubscriptionPaymentStatus}
 * by orchestration. {@code UNKNOWN} is treated as still in flight.
 */
public enum ProviderPaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    CANCELED,
    UNKNOWN
}
