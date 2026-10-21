package rw.terimbere.csams.modules.subscription.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingPlanQuote {

    private SubscriptionBillingCycle billingCycle;
    private BigDecimal listPrice;
    private BigDecimal amount;
    private BigDecimal discountPercent;
    private BigDecimal savings;
    private int periodMonths;
}
