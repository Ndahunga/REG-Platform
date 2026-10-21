package rw.terimbere.csams.modules.subscription.payment.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationCommand;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationResult;
import rw.terimbere.csams.modules.subscription.payment.ProviderPaymentStatus;
import rw.terimbere.csams.shared.exceptions.BusinessException;

@ExtendWith(MockitoExtension.class)
class MtnMomoSubscriptionPaymentProviderTest {

    @Mock
    private MtnMomoClient client;

    private MtnMomoSubscriptionPaymentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MtnMomoSubscriptionPaymentProvider(client);
    }

    @Test
    void availableFollowsClientConfiguration() {
        when(client.isConfigured()).thenReturn(true);
        assertThat(provider.available()).isTrue();
        when(client.isConfigured()).thenReturn(false);
        assertThat(provider.available()).isFalse();
    }

    @Test
    void initiateMapsAcceptedRequestToPaymentIdReference() {
        when(client.isConfigured()).thenReturn(true);
        UUID paymentId = UUID.randomUUID();
        PaymentInitiationResult result = provider.initiate(command(paymentId));
        assertThat(result.accepted()).isTrue();
        assertThat(result.externalReference()).isEqualTo(paymentId.toString());
        verify(client).requestToPay(eq(paymentId), eq("250781234567"), any(), eq("RWF"), eq(paymentId.toString()));
    }

    @Test
    void initiateFailureDoesNotPretendSuccess() {
        when(client.isConfigured()).thenReturn(true);
        doThrow(new BusinessException("PAYMENT_PROVIDER_ERROR", "Payment request could not be sent to MTN Mobile Money"))
                .when(client)
                .requestToPay(any(), any(), any(), any(), any());
        PaymentInitiationResult result = provider.initiate(command(UUID.randomUUID()));
        assertThat(result.accepted()).isFalse();
        assertThat(result.failureMessage()).contains("could not be sent");
    }

    @Test
    void mapsCollectionStatuses() {
        assertThat(MtnMomoSubscriptionPaymentProvider.map(MtnCollectionStatus.SUCCESSFUL))
                .isEqualTo(ProviderPaymentStatus.SUCCESS);
        assertThat(MtnMomoSubscriptionPaymentProvider.map(MtnCollectionStatus.PENDING))
                .isEqualTo(ProviderPaymentStatus.PENDING);
        assertThat(MtnMomoSubscriptionPaymentProvider.map(MtnCollectionStatus.FAILED))
                .isEqualTo(ProviderPaymentStatus.FAILED);
        assertThat(MtnMomoSubscriptionPaymentProvider.map(MtnCollectionStatus.TIMEOUT))
                .isEqualTo(ProviderPaymentStatus.FAILED);
        assertThat(MtnMomoSubscriptionPaymentProvider.map(MtnCollectionStatus.UNKNOWN))
                .isEqualTo(ProviderPaymentStatus.UNKNOWN);
    }

    @Test
    void amountStringDropsTrailingZeros() {
        assertThat(RestClientMtnMomoClient.amountString(new BigDecimal("2000.0000"))).isEqualTo("2000");
        assertThat(RestClientMtnMomoClient.amountString(new BigDecimal("18000.0000"))).isEqualTo("18000");
    }

    private static PaymentInitiationCommand command(UUID paymentId) {
        return new PaymentInitiationCommand(
                paymentId,
                UUID.randomUUID(),
                SubscriptionBillingCycle.MONTHLY,
                new BigDecimal("2000.0000"),
                "RWF",
                "250781234567");
    }
}
