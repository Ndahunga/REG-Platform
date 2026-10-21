package rw.terimbere.csams.modules.subscription.payment.mtn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * RestClient adapter for MTN MoMo Collection. Follows the WhatsApp Cloud client pattern:
 * credentials stay in config, HTTP errors are translated, tokens are never logged.
 */
@Component
public class RestClientMtnMomoClient implements MtnMomoClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientMtnMomoClient.class);
    private static final long TOKEN_SKEW_SECONDS = 60;

    private final SubscriptionProperties.Payment.Mtn properties;
    private final AtomicReference<CachedToken> token = new AtomicReference<>();

    public RestClientMtnMomoClient(SubscriptionProperties subscriptionProperties) {
        this.properties = subscriptionProperties.getPayment().getMtn();
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public void requestToPay(
            UUID referenceId, String msisdn, BigDecimal amount, String currency, String externalId) {
        requireConfigured();
        String accessToken = accessToken();
        RestClient client = collectionClient();
        Map<String, Object> payer = new LinkedHashMap<>();
        payer.put("partyIdType", "MSISDN");
        payer.put("partyId", msisdn);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountString(amount));
        body.put("currency", currency);
        body.put("externalId", externalId);
        body.put("payer", payer);
        body.put("payerMessage", "OuWealth");
        body.put("payeeNote", "OuWealth");
        try {
            var request = client.post()
                    .uri("/collection/v1_0/requesttopay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-Reference-Id", referenceId.toString())
                    .header("X-Target-Environment", properties.getTargetEnvironment().trim())
                    .header("Ocp-Apim-Subscription-Key", properties.getSubscriptionKey().trim());
            if (StringUtils.hasText(properties.getCallbackUrl())) {
                request = request.header("X-Callback-Url", properties.getCallbackUrl().trim());
            }
            request.body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                log.info("MTN MoMo requestToPay already accepted for reference {}", referenceId);
                return;
            }
            log.warn("MTN MoMo requestToPay HTTP {} (credentials not logged)", ex.getStatusCode().value());
            throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Payment request could not be sent to MTN Mobile Money");
        } catch (RuntimeException ex) {
            log.warn("MTN MoMo requestToPay failed: {}", ex.getClass().getSimpleName());
            throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Payment request could not be sent to MTN Mobile Money");
        }
    }

    @Override
    public MtnCollectionStatus getRequestToPayStatus(UUID referenceId) {
        requireConfigured();
        String accessToken = accessToken();
        try {
            StatusResponse response = collectionClient()
                    .get()
                    .uri("/collection/v1_0/requesttopay/{referenceId}", referenceId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-Target-Environment", properties.getTargetEnvironment().trim())
                    .header("Ocp-Apim-Subscription-Key", properties.getSubscriptionKey().trim())
                    .retrieve()
                    .body(StatusResponse.class);
            return mapStatus(response == null ? null : response.status());
        } catch (RestClientResponseException ex) {
            log.warn("MTN MoMo status HTTP {} (credentials not logged)", ex.getStatusCode().value());
            if (ex.getStatusCode().value() == 404) {
                return MtnCollectionStatus.UNKNOWN;
            }
            throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Could not verify the MTN Mobile Money payment");
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("MTN MoMo status failed: {}", ex.getClass().getSimpleName());
            throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Could not verify the MTN Mobile Money payment");
        }
    }

    private String accessToken() {
        CachedToken cached = token.get();
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        synchronized (this) {
            cached = token.get();
            if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
                return cached.value();
            }
            TokenResponse response;
            try {
                String basic = Base64.getEncoder()
                        .encodeToString((properties.getApiUser().trim() + ":" + properties.getApiKey().trim())
                                .getBytes(StandardCharsets.UTF_8));
                response = collectionClient()
                        .post()
                        .uri("/collection/token/")
                        .header("Authorization", "Basic " + basic)
                        .header("Ocp-Apim-Subscription-Key", properties.getSubscriptionKey().trim())
                        .contentType(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(TokenResponse.class);
            } catch (RestClientResponseException ex) {
                log.warn("MTN MoMo token HTTP {} (token not logged)", ex.getStatusCode().value());
                throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Payment request could not be sent to MTN Mobile Money");
            } catch (RuntimeException ex) {
                log.warn("MTN MoMo token failed: {}", ex.getClass().getSimpleName());
                throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Payment request could not be sent to MTN Mobile Money");
            }
            if (response == null || !StringUtils.hasText(response.accessToken())) {
                throw new BusinessException("PAYMENT_PROVIDER_ERROR", "Payment request could not be sent to MTN Mobile Money");
            }
            long expiresIn = response.expiresIn() == null || response.expiresIn() <= 0 ? 3600 : response.expiresIn();
            Instant expiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn - TOKEN_SKEW_SECONDS));
            token.set(new CachedToken(response.accessToken(), expiresAt));
            return response.accessToken();
        }
    }

    private RestClient collectionClient() {
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
            throw new BusinessException("PAYMENT_PROVIDER_ERROR", "MTN Mobile Money is not configured");
        }
    }

    static String amountString(BigDecimal amount) {
        return MoneyUtils.scaleForStorage(amount).stripTrailingZeros().toPlainString();
    }

    static MtnCollectionStatus mapStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return MtnCollectionStatus.UNKNOWN;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING" -> MtnCollectionStatus.PENDING;
            case "SUCCESSFUL" -> MtnCollectionStatus.SUCCESSFUL;
            case "FAILED" -> MtnCollectionStatus.FAILED;
            case "TIMEOUT" -> MtnCollectionStatus.TIMEOUT;
            default -> MtnCollectionStatus.UNKNOWN;
        };
    }

    private record CachedToken(String value, Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatusResponse(String status) {}
}
