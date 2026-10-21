package rw.terimbere.csams.modules.subscription.payment.flutterwave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationCommand;
import rw.terimbere.csams.modules.subscription.payment.PaymentInitiationResult;
import rw.terimbere.csams.modules.subscription.payment.PaymentVerification;
import rw.terimbere.csams.modules.subscription.payment.ProviderPaymentStatus;
import rw.terimbere.csams.shared.exceptions.BusinessException;

@ExtendWith(MockitoExtension.class)
class FlutterwaveCardSubscriptionPaymentProviderTest {

    @Mock
    private FlutterwaveClient client;

    private SubscriptionProperties properties;
    private FlutterwaveCardSubscriptionPaymentProvider provider;

    @BeforeEach
    void setUp() {
        properties = new SubscriptionProperties();
        properties.getPayment().getFlutterwave().setRedirectUrl("https://app.local/billing/payment-return");
        provider = new FlutterwaveCardSubscriptionPaymentProvider(client, properties);
    }

    @Test
    void initiateCreatesHostedCheckoutWithServerOwnedAmountAndTxRef() {
        when(client.isConfigured()).thenReturn(true);
        UUID paymentId = UUID.randomUUID();
        UUID cooperativeId = UUID.randomUUID();
        when(client.createHostedCheckout(any()))
                .thenReturn(new FlutterwaveClient.HostedCheckoutSession("https://checkout.flutterwave.com/pay/abc"));

        PaymentInitiationResult result = provider.initiate(new PaymentInitiationCommand(
                paymentId,
                cooperativeId,
                SubscriptionBillingCycle.MONTHLY,
                new BigDecimal("2000.0000"),
                "RWF",
                null,
                "pat@test.local",
                "Pat President",
                "0781234567"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.externalReference()).isEqualTo("ouwealth-sub-" + paymentId);
        assertThat(result.checkoutUrl()).isEqualTo("https://checkout.flutterwave.com/pay/abc");

        ArgumentCaptor<FlutterwaveClient.HostedCheckoutRequest> captor =
                ArgumentCaptor.forClass(FlutterwaveClient.HostedCheckoutRequest.class);
        verify(client).createHostedCheckout(captor.capture());
        FlutterwaveClient.HostedCheckoutRequest request = captor.getValue();
        assertThat(request.txRef()).isEqualTo("ouwealth-sub-" + paymentId);
        assertThat(request.amount()).isEqualByComparingTo("2000.0000");
        assertThat(request.currency()).isEqualTo("RWF");
        assertThat(request.customerEmail()).isEqualTo("pat@test.local");
        assertThat(request.redirectUrl()).contains("paymentId=" + paymentId);
        assertThat(request.redirectUrl()).contains("cooperativeId=" + cooperativeId);
    }

    @Test
    void initiateFailureDoesNotPretendSuccess() {
        when(client.isConfigured()).thenReturn(true);
        when(client.createHostedCheckout(any()))
                .thenThrow(new BusinessException("PAYMENT_PROVIDER_ERROR", "Card checkout could not be started"));
        PaymentInitiationResult result = provider.initiate(new PaymentInitiationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SubscriptionBillingCycle.MONTHLY,
                new BigDecimal("2000.0000"),
                "RWF",
                null,
                "pat@test.local",
                "Pat President",
                null));
        assertThat(result.accepted()).isFalse();
        assertThat(result.checkoutUrl()).isNull();
    }

    @Test
    void inspectRequiresMatchingTxRefAmountAndCurrencyForSuccess() {
        when(client.isConfigured()).thenReturn(true);
        UUID paymentId = UUID.randomUUID();
        String txRef = "ouwealth-sub-" + paymentId;
        when(client.verifyByTransactionId("999"))
                .thenReturn(new FlutterwaveClient.VerifiedTransaction(
                        "successful", txRef, "RWF", new BigDecimal("2000.0000"), "999"));
        PaymentVerification verification = provider.inspect(txRef, "999");
        SubscriptionPayment payment = SubscriptionPayment.builder()
                .amount(new BigDecimal("2000.0000"))
                .currency("RWF")
                .externalReference(txRef)
                .build();
        assertThat(verification.status()).isEqualTo(ProviderPaymentStatus.SUCCESS);
        assertThat(verification.verifiedSuccess(payment)).isTrue();
    }

    @Test
    void mapsProviderStatuses() {
        assertThat(FlutterwaveCardSubscriptionPaymentProvider.mapStatus("successful"))
                .isEqualTo(ProviderPaymentStatus.SUCCESS);
        assertThat(FlutterwaveCardSubscriptionPaymentProvider.mapStatus("failed"))
                .isEqualTo(ProviderPaymentStatus.FAILED);
        assertThat(FlutterwaveCardSubscriptionPaymentProvider.mapStatus("cancelled"))
                .isEqualTo(ProviderPaymentStatus.CANCELED);
        assertThat(FlutterwaveCardSubscriptionPaymentProvider.mapStatus("pending"))
                .isEqualTo(ProviderPaymentStatus.PENDING);
    }

    @Test
    void webhookSignatureRejectsWrongHash() {
        assertThat(FlutterwaveWebhookSignatures.matches("expected-hash", "other-hash")).isFalse();
        assertThat(FlutterwaveWebhookSignatures.matches("expected-hash", "expected-hash")).isTrue();
        assertThat(FlutterwaveWebhookSignatures.matches("expected-hash", null)).isFalse();
    }

    @Test
    void amountValueDropsTrailingZeros() {
        assertThat(RestClientFlutterwaveClient.amountValue(new BigDecimal("2000.0000"))).isEqualTo(2000);
        assertThat(RestClientFlutterwaveClient.amountValue(new BigDecimal("18000.0000"))).isEqualTo(18000);
    }
}
