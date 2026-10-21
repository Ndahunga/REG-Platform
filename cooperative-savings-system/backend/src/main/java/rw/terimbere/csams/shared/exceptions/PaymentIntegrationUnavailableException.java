package rw.terimbere.csams.shared.exceptions;

/**
 * Checkout channel is not available (CARD in Phase 6A, or MTN when disabled / unconfigured).
 * Does not record a payment or change subscription entitlement.
 */
public class PaymentIntegrationUnavailableException extends RuntimeException {

    public static final String CODE = "PAYMENT_INTEGRATION_UNAVAILABLE";

    public PaymentIntegrationUnavailableException() {
        super("Payment processing is not available yet. Your Saving Scheme was not charged.");
    }
}
