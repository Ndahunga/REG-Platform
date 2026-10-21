package rw.terimbere.csams.modules.subscription.payment.flutterwave;

import java.math.BigDecimal;

/**
 * Flutterwave Standard (hosted checkout) HTTP adapter. Secret keys are never logged.
 */
public interface FlutterwaveClient {

    boolean isConfigured();

    HostedCheckoutSession createHostedCheckout(HostedCheckoutRequest request);

    VerifiedTransaction verifyByReference(String txRef);

    VerifiedTransaction verifyByTransactionId(String transactionId);

    record HostedCheckoutRequest(
            String txRef,
            BigDecimal amount,
            String currency,
            String redirectUrl,
            String customerEmail,
            String customerName,
            String customerPhone,
            int sessionDurationMinutes) {}

    record HostedCheckoutSession(String checkoutUrl) {}

    record VerifiedTransaction(String status, String txRef, String currency, BigDecimal amount, String transactionId) {}
}
