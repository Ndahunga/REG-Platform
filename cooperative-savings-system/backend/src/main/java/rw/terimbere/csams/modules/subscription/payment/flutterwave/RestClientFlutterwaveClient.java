package rw.terimbere.csams.modules.subscription.payment.flutterwave;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import rw.terimbere.csams.modules.subscription.config.SubscriptionProperties;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Component
public class RestClientFlutterwaveClient implements FlutterwaveClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientFlutterwaveClient.class);

    private final SubscriptionProperties.Payment.Flutterwave properties;

    public RestClientFlutterwaveClient(SubscriptionProperties subscriptionProperties) {
        this.properties = subscriptionProperties.getPayment().getFlutterwave();
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public HostedCheckoutSession createHostedCheckout(HostedCheckoutRequest request) {
        requireConfigured();
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("email", request.customerEmail());
        if (StringUtils.hasText(request.customerName())) {
            customer.put("name", request.customerName());
        }
        if (StringUtils.hasText(request.customerPhone())) {
            customer.put("phonenumber", request.customerPhone());
        }
        Map<String, Object> customizations = new LinkedHashMap<>();
        customizations.put("title", "OuWealth");
        customizations.put("description", "Saving Scheme subscription");
        Map<String, Object> configurations = new LinkedHashMap<>();
        configurations.put("session_duration", Math.max(1, request.sessionDurationMinutes()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tx_ref", request.txRef());
        body.put("amount", amountValue(request.amount()));
        body.put("currency", request.currency());
        body.put("redirect_url", request.redirectUrl());
        body.put("payment_options", "card");
        body.put("customer", customer);
        body.put("customizations", customizations);
        body.put("configurations", configurations);
        try {
            CheckoutResponse response = apiClient()
                    .post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getSecretKey().trim())
                    .body(body)
                    .retrieve()
                    .body(CheckoutResponse.class);
            if (response == null
                    || response.data() == null
                    || !StringUtils.hasText(response.data().link())) {
                throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Card checkout could not be started");
            }
            return new HostedCheckoutSession(response.data().link());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            log.warn("Flutterwave checkout HTTP {} (secret not logged)", ex.getStatusCode().value());
            throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Card checkout could not be started");
        } catch (RuntimeException ex) {
            log.warn("Flutterwave checkout failed: {}", ex.getClass().getSimpleName());
            throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Card checkout could not be started");
        }
    }

    @Override
    public VerifiedTransaction verifyByReference(String txRef) {
        requireConfigured();
        try {
            VerifyResponse response = apiClient()
                    .get()
                    .uri("/transactions/verify_by_reference?tx_ref={txRef}", txRef)
                    .header("Authorization", "Bearer " + properties.getSecretKey().trim())
                    .retrieve()
                    .body(VerifyResponse.class);
            return toVerified(response);
        } catch (RestClientResponseException ex) {
            log.warn("Flutterwave verify-by-reference HTTP {} (secret not logged)", ex.getStatusCode().value());
            return null;
        } catch (RuntimeException ex) {
            log.warn("Flutterwave verify-by-reference failed: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public VerifiedTransaction verifyByTransactionId(String transactionId) {
        requireConfigured();
        try {
            VerifyResponse response = apiClient()
                    .get()
                    .uri("/transactions/{id}/verify", transactionId)
                    .header("Authorization", "Bearer " + properties.getSecretKey().trim())
                    .retrieve()
                    .body(VerifyResponse.class);
            return toVerified(response);
        } catch (RestClientResponseException ex) {
            log.warn("Flutterwave verify HTTP {} (secret not logged)", ex.getStatusCode().value());
            return null;
        } catch (RuntimeException ex) {
            log.warn("Flutterwave verify failed: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private VerifiedTransaction toVerified(VerifyResponse response) {
        if (response == null || response.data() == null) {
            return null;
        }
        VerifyData data = response.data();
        String id = data.id() == null ? null : String.valueOf(data.id());
        BigDecimal amount = data.amount() == null ? null : MoneyUtils.scale(data.amount());
        return new VerifiedTransaction(data.status(), data.txRef(), data.currency(), amount, id);
    }

    private RestClient apiClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return RestClient.builder().baseUrl(base).requestFactory(factory).build();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Card payment is not configured");
        }
    }

    static Object amountValue(BigDecimal amount) {
        BigDecimal scaled = MoneyUtils.scaleForStorage(amount).stripTrailingZeros();
        if (scaled.scale() <= 0) {
            return scaled.intValueExact();
        }
        return scaled;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CheckoutResponse(String status, CheckoutData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CheckoutData(String link) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VerifyResponse(String status, VerifyData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VerifyData(
            Long id,
            @JsonProperty("tx_ref") String txRef,
            String currency,
            BigDecimal amount,
            String status) {}
}
