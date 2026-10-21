package rw.terimbere.csams.modules.memberpayment.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.verification.TrustedTransactionService.Candidate;
import rw.terimbere.csams.modules.memberpayment.verification.TrustedTransactionService.Outcome;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * A generic, signed inbound confirmation contract. It is NOT an integration with any particular provider: no
 * provider's real callback format or credentials are known here. A sender that can produce this payload and sign
 * the raw body with a secret the scheme operator configured can deliver confirmations; without a configured secret the
 * endpoint refuses everything (503), so nothing can be confirmed by an unauthenticated caller.
 *
 * <p>The body is JSON: {@code cooperativeId, eventId, reference, amount, currency, status, transactedAt?, sender?,
 * recipient?}. {@code X-Timestamp} is the send time in epoch seconds and {@code X-Signature} is the hex HMAC-SHA256
 * (optionally prefixed {@code sha256=}) of the ASCII timestamp, a dot, and the exact raw body bytes, so a captured
 * request cannot be re-sent after the replay window and the timestamp cannot be altered without the secret. Inside the
 * window, {@code eventId} makes a redelivery idempotent: it can never confirm a payment twice.
 */
@RestController
@RequestMapping("/api/v1/public/payment-callbacks")
@RequiredArgsConstructor
@Tag(name = "Payment provider callbacks")
public class ProviderCallbackController {

    private static final int MAX_BODY = 16 * 1024;

    private final ProviderCallbackProperties properties;
    private final TrustedTransactionService transactionService;
    private final ObjectMapper objectMapper;
    private final CallbackRateLimiter rateLimiter;
    private final Clock clock;

    @PostMapping("/{channel}")
    @Operation(summary = "Signed payment confirmation callback (requires a configured channel secret)")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String channel,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            HttpServletRequest request) {
        PaymentMethod method = channelOf(channel);
        if (method == null) {
            return reply(HttpStatus.NOT_FOUND, "UNKNOWN_CHANNEL", null);
        }
        String secret = properties.secretFor(method.name());
        if (secret == null) {
            return reply(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_NOT_CONFIGURED", null);
        }
        // abuse control first: every request from an address counts, so floods of bad requests are cut off cheaply
        if (!rateLimiter.tryAcquire("addr:" + request.getRemoteAddr(), properties.getRatePerAddressPerMinute())) {
            return tooMany();
        }
        // read at most one byte more than the limit: an unauthenticated caller can never make the server buffer a huge body
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_BODY + 1);
        } catch (java.io.IOException e) {
            return reply(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", null);
        }
        if (!StringUtils.hasText(timestamp)) {
            return reply(HttpStatus.UNAUTHORIZED, "MISSING_TIMESTAMP", null);
        }
        if (body.length == 0 || body.length > MAX_BODY || !validSignature(secret, timestamp.trim(), body, signature)) {
            return reply(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", null);
        }
        // authenticated from here: only now may the sender learn why a timestamp was refused
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return reply(HttpStatus.UNAUTHORIZED, "INVALID_TIMESTAMP", null);
        }
        long now = clock.instant().getEpochSecond();
        if (sentAt > now + properties.getMaxFutureSkewSeconds()) {
            return reply(HttpStatus.UNAUTHORIZED, "FUTURE_TIMESTAMP", null);
        }
        if (now - sentAt > properties.getReplayWindowSeconds()) {
            return reply(HttpStatus.UNAUTHORIZED, "STALE_TIMESTAMP", null);
        }
        if (!rateLimiter.tryAcquire("channel:" + method.name(), properties.getRatePerChannelPerMinute())) {
            return tooMany();
        }
        Candidate candidate;
        UUID cooperativeId;
        try {
            JsonNode json = objectMapper.readTree(body);
            cooperativeId = UUID.fromString(text(json, "cooperativeId"));
            candidate = new Candidate(
                    VerificationSource.PROVIDER_CALLBACK,
                    method,
                    text(json, "reference"),
                    new BigDecimal(json.path("amount").asText()),
                    text(json, "currency"),
                    ProviderStatus.valueOf(text(json, "status").toUpperCase(Locale.ROOT)),
                    json.hasNonNull("transactedAt") ? Instant.parse(json.get("transactedAt").asText()) : null,
                    optional(json, "sender"),
                    optional(json, "recipient"),
                    text(json, "eventId"),
                    null,
                    null);
        } catch (Exception e) {
            return reply(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", null);
        }
        try {
            Outcome outcome = transactionService.recordCallback(cooperativeId, candidate);
            return reply(HttpStatus.OK, outcome.result().name(), outcome.reason());
        } catch (ResourceNotFoundException e) {
            return reply(HttpStatus.NOT_FOUND, "UNKNOWN_COOPERATIVE", null);
        } catch (BusinessException e) {
            return reply(HttpStatus.BAD_REQUEST, e.getErrorCode(), null);
        }
    }

    private static PaymentMethod channelOf(String channel) {
        try {
            PaymentMethod method = PaymentMethod.valueOf(channel.toUpperCase(Locale.ROOT));
            return method == PaymentMethod.CASH ? null : method;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Constant-time comparison of the hex HMAC-SHA256 of {@code timestamp + "." + rawBody}. */
    static boolean validSignature(String secret, String timestamp, byte[] body, String header) {
        if (!StringUtils.hasText(header)) {
            return false;
        }
        String provided = header.trim().toLowerCase(Locale.ROOT);
        if (provided.startsWith("sha256=")) {
            provided = provided.substring("sha256=".length());
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull() || !StringUtils.hasText(node.asText())) {
            throw new IllegalArgumentException(field);
        }
        return node.asText();
    }

    private static String optional(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static ResponseEntity<Map<String, Object>> tooMany() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").body(body);
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, String code, String reason) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", status.is2xxSuccessful());
        body.put("code", code);
        if (reason != null) {
            body.put("reason", reason);
        }
        return ResponseEntity.status(status).body(body);
    }
}
