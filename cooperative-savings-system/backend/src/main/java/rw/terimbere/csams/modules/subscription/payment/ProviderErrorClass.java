package rw.terimbere.csams.modules.subscription.payment;

/**
 * Distinguishes temporary provider outages from confirmed payment outcomes.
 *
 * <p>{@link ProviderPaymentStatus#UNKNOWN} means verification could not complete
 * (timeout, 5xx, connection error). Orchestration must keep the local payment
 * {@code PENDING} — never map UNKNOWN to FAILED.
 *
 * <p>{@link ProviderPaymentStatus#FAILED} / {@link ProviderPaymentStatus#CANCELED}
 * are confirmed by the provider and may transition the local payment.
 */
public enum ProviderErrorClass {
    TEMPORARY,
    CONFIRMED_FAILURE,
    CONFIRMED_SUCCESS,
    IN_FLIGHT;

    public static ProviderErrorClass of(ProviderPaymentStatus status) {
        if (status == null) {
            return TEMPORARY;
        }
        return switch (status) {
            case UNKNOWN -> TEMPORARY;
            case PENDING -> IN_FLIGHT;
            case SUCCESS -> CONFIRMED_SUCCESS;
            case FAILED, CANCELED -> CONFIRMED_FAILURE;
        };
    }
}
