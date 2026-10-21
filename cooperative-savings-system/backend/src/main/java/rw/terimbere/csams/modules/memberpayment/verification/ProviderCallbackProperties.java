package rw.terimbere.csams.modules.memberpayment.verification;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Per-channel shared secrets for signed provider callbacks ({@code app.payments.provider-callback.secrets.<CHANNEL>}).
 * No secret is shipped or invented: a channel without one rejects callbacks, and automatic provider confirmation is
 * reported as unavailable for it.
 */
@Component
@ConfigurationProperties(prefix = "app.payments.provider-callback")
public class ProviderCallbackProperties {

    private Map<String, String> secrets = new HashMap<>();

    /** How old a signed timestamp may be before the request is refused as a replay. */
    private long replayWindowSeconds = 300;

    /** How far in the future a signed timestamp may be (clock skew between the sender and this server). */
    private long maxFutureSkewSeconds = 60;

    /** Requests per minute from one remote address (all requests, valid or not). Zero disables. */
    private int ratePerAddressPerMinute = 60;

    /** Authenticated requests per minute for one channel. Zero disables. */
    private int ratePerChannelPerMinute = 600;

    public long getReplayWindowSeconds() {
        return replayWindowSeconds;
    }

    public void setReplayWindowSeconds(long replayWindowSeconds) {
        this.replayWindowSeconds = Math.max(1, replayWindowSeconds);
    }

    public long getMaxFutureSkewSeconds() {
        return maxFutureSkewSeconds;
    }

    public void setMaxFutureSkewSeconds(long maxFutureSkewSeconds) {
        this.maxFutureSkewSeconds = Math.max(0, maxFutureSkewSeconds);
    }

    public int getRatePerAddressPerMinute() {
        return ratePerAddressPerMinute;
    }

    public void setRatePerAddressPerMinute(int ratePerAddressPerMinute) {
        this.ratePerAddressPerMinute = ratePerAddressPerMinute;
    }

    public int getRatePerChannelPerMinute() {
        return ratePerChannelPerMinute;
    }

    public void setRatePerChannelPerMinute(int ratePerChannelPerMinute) {
        this.ratePerChannelPerMinute = ratePerChannelPerMinute;
    }

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets == null ? new HashMap<>() : secrets;
    }

    /** The secret of a channel (for example MTN_MOMO), or null when none is configured. */
    public String secretFor(String channel) {
        if (channel == null) {
            return null;
        }
        String wanted = channel.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            if (e.getKey() != null && e.getKey().toUpperCase(Locale.ROOT).equals(wanted) && StringUtils.hasText(e.getValue())) {
                return e.getValue();
            }
        }
        return null;
    }

    public boolean anyConfigured() {
        return secrets.values().stream().anyMatch(StringUtils::hasText);
    }
}
