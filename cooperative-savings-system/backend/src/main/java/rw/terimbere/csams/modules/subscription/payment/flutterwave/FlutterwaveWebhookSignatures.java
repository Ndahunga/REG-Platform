package rw.terimbere.csams.modules.subscription.payment.flutterwave;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.util.StringUtils;

/**
 * Flutterwave dashboard {@code verif-hash} comparison. Constant-time; never logs the secret.
 */
public final class FlutterwaveWebhookSignatures {

    private FlutterwaveWebhookSignatures() {}

    public static boolean matches(String configuredSecretHash, String headerValue) {
        if (!StringUtils.hasText(configuredSecretHash) || !StringUtils.hasText(headerValue)) {
            return false;
        }
        byte[] expected = configuredSecretHash.trim().getBytes(StandardCharsets.UTF_8);
        byte[] actual = headerValue.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
