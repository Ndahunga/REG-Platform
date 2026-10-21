package rw.terimbere.csams.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;

class SubscriptionPricingTest {

    @Test
    void catalogMatchesProductPrices() {
        SubscriptionPricing pricing = new SubscriptionPricing(new SubscriptionProperties());
        pricing.validateCatalog();

        assertThat(pricing.currency()).isEqualTo("RWF");
        assertThat(pricing.trialMonths()).isEqualTo(4);
        assertThat(pricing.zoneId().getId()).isEqualTo("Africa/Kigali");

        SubscriptionPricing.PlanQuote monthly = pricing.monthly();
        assertThat(monthly.billingCycle()).isEqualTo(SubscriptionBillingCycle.MONTHLY);
        assertThat(monthly.charge()).isEqualByComparingTo("2000.0000");
        assertThat(monthly.listPrice()).isEqualByComparingTo("2000.0000");
        assertThat(monthly.periodMonths()).isEqualTo(1);
        assertThat(monthly.discountPercent()).isEqualByComparingTo(BigDecimal.ZERO);

        SubscriptionPricing.PlanQuote annual = pricing.annual();
        assertThat(annual.billingCycle()).isEqualTo(SubscriptionBillingCycle.ANNUAL);
        assertThat(annual.listPrice()).isEqualByComparingTo("24000.0000");
        assertThat(annual.charge()).isEqualByComparingTo("18000.0000");
        assertThat(annual.discountPercent()).isEqualByComparingTo("25");
        assertThat(annual.periodMonths()).isEqualTo(12);
    }

    @Test
    void annualChargeMustEqualListMinusDiscount() {
        SubscriptionProperties properties = new SubscriptionProperties();
        properties.setAnnualCharge(new BigDecimal("20000.0000"));
        SubscriptionPricing pricing = new SubscriptionPricing(properties);

        assertThatThrownBy(pricing::validateCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Annual subscription charge");
    }
}
