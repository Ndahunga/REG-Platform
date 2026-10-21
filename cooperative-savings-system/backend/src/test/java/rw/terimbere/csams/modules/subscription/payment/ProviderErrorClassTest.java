package rw.terimbere.csams.modules.subscription.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderErrorClassTest {

    @Test
    void classifiesTemporaryVsConfirmed() {
        assertThat(ProviderErrorClass.of(ProviderPaymentStatus.UNKNOWN)).isEqualTo(ProviderErrorClass.TEMPORARY);
        assertThat(ProviderErrorClass.of(ProviderPaymentStatus.PENDING)).isEqualTo(ProviderErrorClass.IN_FLIGHT);
        assertThat(ProviderErrorClass.of(ProviderPaymentStatus.SUCCESS)).isEqualTo(ProviderErrorClass.CONFIRMED_SUCCESS);
        assertThat(ProviderErrorClass.of(ProviderPaymentStatus.FAILED)).isEqualTo(ProviderErrorClass.CONFIRMED_FAILURE);
        assertThat(ProviderErrorClass.of(ProviderPaymentStatus.CANCELED)).isEqualTo(ProviderErrorClass.CONFIRMED_FAILURE);
    }
}
