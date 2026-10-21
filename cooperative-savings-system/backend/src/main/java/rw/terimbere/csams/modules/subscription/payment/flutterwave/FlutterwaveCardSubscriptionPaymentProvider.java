package rw.terimbere.csams.modules.subscription.payment.flutterwave;

import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationCommand;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationResult;
import rw.terimbere.csams.modules.subscription.payment.PaymentVerification;
import rw.terimbere.csams.modules.subscription.payment.ProviderPaymentStatus;
import rw.terimbere.csams.modules.subscription.payment.SubscriptionPaymentProvider;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@Component
public class FlutterwaveCardSubscriptionPaymentProvider implements SubscriptionPaymentProvider {

    public static final String PROVIDER_NAME = "FLUTTERWAVE";
    public static final String TX_REF_PREFIX = "ouwealth-sub-";

    private final FlutterwaveClient client;
    private final SubscriptionProperties properties;

    public FlutterwaveCardSubscriptionPaymentProvider(
            FlutterwaveClient client, SubscriptionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public SubscriptionPaymentChannel channel() {
        return SubscriptionPaymentChannel.CARD;
    }

    @Override
    public boolean available() {
        return client.isConfigured();
    }

    @Override
    public PaymentInitiationResult initiate(PaymentInitiationCommand command) {
        if (!available()) {
            return PaymentInitiationResult.rejected("Card payment is not configured");
        }
        if (command == null || command.paymentId() == null) {
            return PaymentInitiationResult.rejected("Payment reference is required");
        }
        if (!StringUtils.hasText(command.customerEmail())) {
            throw new ValidationException("A billing email is required for card checkout");
        }
        String txRef = txRef(command.paymentId());
        String redirectUrl = redirectUrl(command.paymentId(), command.cooperativeId());
        try {
            FlutterwaveClient.HostedCheckoutSession session = client.createHostedCheckout(
                    new FlutterwaveClient.HostedCheckoutRequest(
                            txRef,
                            command.amount(),
                            command.currency(),
                            redirectUrl,
                            command.customerEmail(),
                            command.customerName(),
                            command.customerPhone(),
                            Math.max(1, properties.getPayment().getPendingReuseMinutes())));
            return PaymentInitiationResult.hosted(txRef, session.checkoutUrl());
        } catch (BusinessException ex) {
            return PaymentInitiationResult.rejected(ex.getMessage());
        }
    }

    @Override
    public ProviderPaymentStatus verify(String externalReference) {
        return inspect(externalReference, null).status();
    }

    @Override
    public PaymentVerification inspect(String externalReference, String providerTransactionId) {
        if (!available()) {
            return PaymentVerification.ofStatus(ProviderPaymentStatus.UNKNOWN);
        }
        FlutterwaveClient.VerifiedTransaction verified = null;
        if (StringUtils.hasText(providerTransactionId)) {
            verified = client.verifyByTransactionId(providerTransactionId.trim());
        }
        if (verified == null && StringUtils.hasText(externalReference)) {
            verified = client.verifyByReference(externalReference.trim());
        }
        if (verified == null) {
            return PaymentVerification.ofStatus(ProviderPaymentStatus.UNKNOWN);
        }
        return new PaymentVerification(
                mapStatus(verified.status()),
                verified.txRef(),
                PaymentVerification.normalizeCurrency(verified.currency()),
                verified.amount(),
                null);
    }

    public static String txRef(java.util.UUID paymentId) {
        return TX_REF_PREFIX + paymentId;
    }

    static ProviderPaymentStatus mapStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return ProviderPaymentStatus.UNKNOWN;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "successful", "success" -> ProviderPaymentStatus.SUCCESS;
            case "failed", "failure", "error" -> ProviderPaymentStatus.FAILED;
            case "cancelled", "canceled" -> ProviderPaymentStatus.CANCELED;
            case "pending" -> ProviderPaymentStatus.PENDING;
            default -> ProviderPaymentStatus.UNKNOWN;
        };
    }

    private String redirectUrl(java.util.UUID paymentId, java.util.UUID cooperativeId) {
        String base = properties.getPayment().getFlutterwave().getRedirectUrl();
        if (!StringUtils.hasText(base)) {
            throw new ValidationException("Card payment return URL is not configured");
        }
        String trimmed = base.trim();
        String separator = trimmed.contains("?") ? "&" : "?";
        StringBuilder url = new StringBuilder(trimmed)
                .append(separator)
                .append("paymentId=")
                .append(paymentId);
        if (cooperativeId != null) {
            url.append("&cooperativeId=").append(cooperativeId);
        }
        return url.toString();
    }
}
