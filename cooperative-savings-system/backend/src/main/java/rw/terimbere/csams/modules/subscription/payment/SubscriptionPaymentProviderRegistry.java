package rw.terimbere.csams.modules.subscription.payment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.shared.exceptions.PaymentIntegrationUnavailableException;

@Component
public class SubscriptionPaymentProviderRegistry {

    private final Map<SubscriptionPaymentChannel, SubscriptionPaymentProvider> providers =
            new EnumMap<>(SubscriptionPaymentChannel.class);

    public SubscriptionPaymentProviderRegistry(List<SubscriptionPaymentProvider> providers) {
        for (SubscriptionPaymentProvider provider : providers) {
            this.providers.put(provider.channel(), provider);
        }
    }

    public SubscriptionPaymentProvider requireAvailable(SubscriptionPaymentChannel channel) {
        SubscriptionPaymentProvider provider = providers.get(channel);
        if (provider == null || !provider.available()) {
            throw new PaymentIntegrationUnavailableException();
        }
        return provider;
    }

    public SubscriptionPaymentProvider find(SubscriptionPaymentChannel channel) {
        return providers.get(channel);
    }
}
