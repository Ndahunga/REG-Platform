package rw.terimbere.csams.modules.subscription.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingCheckoutResponse {

    private UUID paymentId;
    private SubscriptionPaymentStatus status;
    private SubscriptionBillingCycle billingCycle;
    private SubscriptionPaymentChannel paymentChannel;
    private BigDecimal amount;
    private String currency;
    private String checkoutUrl;
    private String message;
}
