package rw.terimbere.csams.modules.subscription.payment.mtn;

/**
 * MTN Collection requestToPay status values. Mapped to domain payment status in the provider.
 */
public enum MtnCollectionStatus {
    PENDING,
    SUCCESSFUL,
    FAILED,
    TIMEOUT,
    UNKNOWN
}
