package rw.terimbere.csams.modules.subscription.payment.mtn;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationCommand;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationResult;
import rw.terimbere.csams.modules.subscription.payment.ProviderPaymentStatus;
import rw.terimbere.csams.modules.subscription.payment.SubscriptionPaymentProvider;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@Component
public class MtnMomoSubscriptionPaymentProvider implements SubscriptionPaymentProvider {

    public static final String PROVIDER_NAME = "MTN_MOMO";

    private final MtnMomoClient client;

    public MtnMomoSubscriptionPaymentProvider(MtnMomoClient client) {
        this.client = client;
    }

    @Override
    public SubscriptionPaymentChannel channel() {
        return SubscriptionPaymentChannel.MTN_MOMO;
    }

    @Override
    public boolean available() {
        return client.isConfigured();
    }

    @Override
    public PaymentInitiationResult initiate(PaymentInitiationCommand command) {
        if (!available()) {
            return PaymentInitiationResult.rejected("MTN Mobile Money is not configured");
        }
        if (command == null || command.paymentId() == null) {
            return PaymentInitiationResult.rejected("Payment reference is required");
        }
        if (!StringUtils.hasText(command.payerMsisdn())) {
            throw new ValidationException("A valid MTN Mobile Money phone number is required");
        }
        try {
            client.requestToPay(
                    command.paymentId(),
                    command.payerMsisdn(),
                    command.amount(),
                    command.currency(),
                    command.paymentId().toString());
            return PaymentInitiationResult.accepted(command.paymentId().toString());
        } catch (BusinessException ex) {
            return PaymentInitiationResult.rejected(ex.getMessage());
        }
    }

    @Override
    public ProviderPaymentStatus verify(String externalReference) {
        if (!available()) {
            return ProviderPaymentStatus.UNKNOWN;
        }
        UUID referenceId = parseReference(externalReference);
        if (referenceId == null) {
            return ProviderPaymentStatus.UNKNOWN;
        }
        try {
            return map(client.getRequestToPayStatus(referenceId));
        } catch (BusinessException ex) {
            return ProviderPaymentStatus.UNKNOWN;
        }
    }

    static ProviderPaymentStatus map(MtnCollectionStatus status) {
        if (status == null) {
            return ProviderPaymentStatus.UNKNOWN;
        }
        return switch (status) {
            case PENDING -> ProviderPaymentStatus.PENDING;
            case SUCCESSFUL -> ProviderPaymentStatus.SUCCESS;
            case FAILED, TIMEOUT -> ProviderPaymentStatus.FAILED;
            case UNKNOWN -> ProviderPaymentStatus.UNKNOWN;
        };
    }

    private static UUID parseReference(String externalReference) {
        if (!StringUtils.hasText(externalReference)) {
            return null;
        }
        try {
            return UUID.fromString(externalReference.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
