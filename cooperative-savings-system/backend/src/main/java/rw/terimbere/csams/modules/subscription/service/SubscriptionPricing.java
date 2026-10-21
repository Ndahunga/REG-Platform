package rw.terimbere.csams.modules.subscription.service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Single source of truth for OuWealth subscription pricing. Callers must not
 * hardcode 2000 / 18000 / 24000 / 25%.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionPricing {

    private final SubscriptionProperties properties;

    @PostConstruct
    public void validateCatalog() {
        BigDecimal expectedAnnualCharge = MoneyUtils.subtract(
                MoneyUtils.scaleForStorage(properties.getAnnualListPrice()),
                MoneyUtils.scaleForStorage(
                        MoneyUtils.percentage(properties.getAnnualListPrice(), properties.getAnnualDiscountPercent())));
        if (expectedAnnualCharge.compareTo(MoneyUtils.scaleForStorage(properties.getAnnualCharge())) != 0) {
            throw new IllegalStateException(
                    "Annual subscription charge must equal list price minus discount percent");
        }
        ZoneId.of(properties.getTimezone());
        if (properties.getTrialMonths() <= 0) {
            throw new IllegalStateException("Subscription trial months must be positive");
        }
    }

    public String currency() {
        return properties.getCurrency();
    }

    public ZoneId zoneId() {
        return ZoneId.of(properties.getTimezone());
    }

    public int trialMonths() {
        return properties.getTrialMonths();
    }

    public PlanQuote monthly() {
        return new PlanQuote(
                SubscriptionBillingCycle.MONTHLY,
                MoneyUtils.scaleForStorage(properties.getMonthlyCharge()),
                MoneyUtils.scaleForStorage(properties.getMonthlyCharge()),
                BigDecimal.ZERO,
                1);
    }

    public PlanQuote annual() {
        return new PlanQuote(
                SubscriptionBillingCycle.ANNUAL,
                MoneyUtils.scaleForStorage(properties.getAnnualListPrice()),
                MoneyUtils.scaleForStorage(properties.getAnnualCharge()),
                MoneyUtils.scale(properties.getAnnualDiscountPercent()),
                12);
    }

    public PlanQuote quote(SubscriptionBillingCycle cycle) {
        Objects.requireNonNull(cycle, "billing cycle is required");
        return cycle == SubscriptionBillingCycle.ANNUAL ? annual() : monthly();
    }

    public record PlanQuote(
            SubscriptionBillingCycle billingCycle,
            BigDecimal listPrice,
            BigDecimal charge,
            BigDecimal discountPercent,
            int periodMonths) {}
}
