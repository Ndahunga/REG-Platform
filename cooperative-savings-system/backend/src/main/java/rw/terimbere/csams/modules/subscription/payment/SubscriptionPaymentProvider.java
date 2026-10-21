package rw.terimbere.csams.modules.subscription.payment;

import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;

/**
 * Channel adapter for OuWealth platform subscription payments.
 *
 * <p>Initiation must not be treated as payment. Activation happens only after
 * {@link #inspect(String, String)} (or a callback that re-verifies) reports a successful match.
 */
public interface SubscriptionPaymentProvider {

    SubscriptionPaymentChannel channel();

    boolean available();

    PaymentInitiationResult initiate(PaymentInitiationCommand command);

    ProviderPaymentStatus verify(String externalReference);

    /**
     * Re-queries the provider. {@code providerTransactionId} is optional (webhook/return hint).
     */
    default PaymentVerification inspect(String externalReference, String providerTransactionId) {
        return PaymentVerification.ofStatus(verify(externalReference));
    }

    /**
     * Collection callbacks are not trusted. Implementations re-query the provider.
     */
    default ProviderPaymentStatus handleCallback(String externalReference) {
        return verify(externalReference);
    }
}
