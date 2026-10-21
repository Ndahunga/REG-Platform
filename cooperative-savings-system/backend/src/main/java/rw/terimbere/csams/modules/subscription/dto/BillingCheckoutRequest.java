package rw.terimbere.csams.modules.subscription.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionBillingCycle;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPaymentChannel;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BillingCheckoutRequest {

    @NotNull
    private SubscriptionBillingCycle billingCycle;

    @NotNull
    private SubscriptionPaymentChannel paymentChannel;

    /**
     * Required only for {@code MTN_MOMO}. Ignored for {@code CARD}.
     * Client-supplied amount/price/discount fields are ignored; pricing is server-side.
     */
    private String payerPhoneNumber;
}
