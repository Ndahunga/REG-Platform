package rw.terimbere.csams.modules.subscription.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderPublicUrlRulesTest {

    @Test
    void acceptsPublicHttpsUrls() {
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl("https://api.ouwealth.example/callback")).isTrue();
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl("https://app.ouwealth.example/billing/payment-return"))
                .isTrue();
    }

    @Test
    void rejectsLocalAndHttpUrls() {
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl("http://api.ouwealth.example/callback")).isFalse();
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl("https://localhost/callback")).isFalse();
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl("https://127.0.0.1/callback")).isFalse();
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl("https://api.local/callback")).isFalse();
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl("https://192.168.1.10/callback")).isFalse();
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl("")).isFalse();
        assertThat(ProviderPublicUrlRules.isSafeProductionUrl(null)).isFalse();
    }
}
