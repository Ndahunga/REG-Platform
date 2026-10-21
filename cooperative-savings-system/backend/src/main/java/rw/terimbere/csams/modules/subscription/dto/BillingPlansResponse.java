package rw.terimbere.csams.modules.subscription.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingPlansResponse {

    private String currency;
    private int trialMonths;
    /** True when MTN MoMo Collection is enabled and configured (no secrets exposed). */
    private boolean mtnCheckoutAvailable;
    /** True when Flutterwave hosted checkout is enabled and configured (no secrets exposed). */
    private boolean cardCheckoutAvailable;
    private List<BillingPlanQuote> plans;
}
