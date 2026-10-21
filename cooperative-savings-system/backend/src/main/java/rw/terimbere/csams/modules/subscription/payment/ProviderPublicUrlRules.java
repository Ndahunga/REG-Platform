package rw.terimbere.csams.modules.subscription.payment;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Production safety checks for provider-facing callback, webhook, and redirect URLs.
 * Domains are never hard-coded; callers pass configured values from environment.
 */
public final class ProviderPublicUrlRules {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "[::1]");

    private ProviderPublicUrlRules() {}

    public static boolean isSafeProductionUrl(String raw) {
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            if (BLOCKED_HOSTS.contains(normalized)) {
                return false;
            }
            if (normalized.endsWith(".local")
                    || normalized.endsWith(".localhost")
                    || normalized.endsWith(".internal")
                    || normalized.endsWith(".lan")) {
                return false;
            }
            if (isPrivateOrLoopbackIp(normalized)) {
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean isPrivateOrLoopbackIp(String host) {
        if (host.startsWith("10.")
                || host.startsWith("192.168.")
                || host.startsWith("169.254.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }
}
