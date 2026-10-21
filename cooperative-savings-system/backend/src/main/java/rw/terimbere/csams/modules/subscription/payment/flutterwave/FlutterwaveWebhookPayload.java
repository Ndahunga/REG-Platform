package rw.terimbere.csams.modules.subscription.payment.flutterwave;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlutterwaveWebhookPayload(String event, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            Long id,
            @JsonProperty("tx_ref") String txRef,
            String status,
            String currency,
            BigDecimal amount) {}
}
