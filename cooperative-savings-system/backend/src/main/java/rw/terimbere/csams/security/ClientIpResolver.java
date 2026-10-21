package rw.terimbere.csams.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The one place that decides which IP address a request really came from, for security decisions such as rate limits.
 *
 * <p>{@code X-Forwarded-For} is only a claim. A client can send any value, and a reverse proxy (Render) appends the
 * address it actually saw to the end of it. So:
 *
 * <ul>
 *   <li>If the TCP peer is not a trusted proxy, every forwarding header is ignored and the peer address is used.
 *   <li>If the peer is a trusted proxy, the header is read from the RIGHT, skipping addresses that belong to trusted
 *       proxies; the first address that is not a trusted proxy is the real client. Anything a client typed sits to the
 *       left of that and is never reached.
 *   <li>The result is normalised (no port, brackets or zone; IPv4-mapped IPv6 becomes IPv4; IPv6 in canonical form) so
 *       one visitor cannot get several buckets by writing the same address differently.
 * </ul>
 *
 * <p>Trusted proxies default to loopback and private/link-local ranges (what a platform proxy connects from). Set
 * {@code app.security.trusted-proxies} (comma-separated addresses or CIDRs) to override.
 */
@Component
public class ClientIpResolver {

    /** Returned only when the peer address itself cannot be read; all such requests share one bucket. */
    public static final String UNKNOWN = "unknown";

    private static final List<String> DEFAULT_TRUSTED = List.of(
            "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10");

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final List<Cidr> trusted;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:}") String configured) {
        List<String> entries = StringUtils.hasText(configured)
                ? List.of(StringUtils.tokenizeToStringArray(configured, ","))
                : DEFAULT_TRUSTED;
        List<Cidr> parsed = new ArrayList<>();
        for (String entry : entries) {
            Cidr cidr = Cidr.parse(entry.trim());
            if (cidr != null) {
                parsed.add(cidr);
            }
        }
        this.trusted = List.copyOf(parsed);
    }

    /** Resolver with the default trusted proxy ranges. */
    public static ClientIpResolver withDefaults() {
        return new ClientIpResolver("");
    }

    public String resolve(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());
        if (peer == null) {
            return UNKNOWN;
        }
        if (!isTrusted(peer)) {
            return peer;
        }
        List<String> forwarded = forwardedChain(request);
        for (int i = forwarded.size() - 1; i >= 0; i--) {
            String candidate = normalize(forwarded.get(i));
            if (candidate == null) {
                // an unreadable entry next to our own proxy means we cannot vouch for anything to its left
                return peer;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return peer;
    }

    private boolean isTrusted(String normalizedIp) {
        byte[] address = toBytes(normalizedIp);
        if (address == null) {
            return false;
        }
        for (Cidr cidr : trusted) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** All X-Forwarded-For header lines, flattened left to right (a proxy may add its own header line). */
    private static List<String> forwardedChain(HttpServletRequest request) {
        List<String> chain = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        if (headers == null) {
            return chain;
        }
        for (String line : Collections.list(headers)) {
            if (line == null) {
                continue;
            }
            for (String part : line.split(",", -1)) {
                chain.add(part);
            }
        }
        return chain;
    }

    /**
     * Canonical text form of an IP literal, or null when it is not one. Never performs a DNS lookup: only strings that
     * already look like IPv4 or IPv6 literals are handed to the JDK parser.
     */
    static String normalize(String raw) {
        byte[] bytes = parse(raw);
        if (bytes == null) {
            return null;
        }
        try {
            // IPv4-mapped IPv6 (::ffff:1.2.3.4) comes back as an Inet4Address, i.e. dotted IPv4
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static byte[] toBytes(String normalizedIp) {
        return parse(normalizedIp);
    }

    private static byte[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 64) {
            return null;
        }
        // [v6]:port
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            value = value.substring(1, close);
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        // v4:port (exactly one colon and a dot); a bare v6 has several colons
        if (value.indexOf(':') == value.lastIndexOf(':') && value.indexOf(':') > 0 && value.contains(".")) {
            value = value.substring(0, value.indexOf(':'));
        }
        var v4 = IPV4.matcher(value);
        if (v4.matches()) {
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                String octet = v4.group(i + 1);
                if (octet.length() > 1 && octet.startsWith("0")) {
                    return null; // 010.0.0.1 is ambiguous (octal in some parsers): refuse it
                }
                int number = Integer.parseInt(octet);
                if (number > 255) {
                    return null;
                }
                out[i] = (byte) number;
            }
            return out;
        }
        if (value.indexOf(':') >= 0 && IPV6_CHARS.matcher(value).matches()) {
            try {
                byte[] address = InetAddress.getByName(value).getAddress(); // a literal: no DNS
                return address;
            } catch (UnknownHostException ex) {
                return null;
            }
        }
        return null;
    }

    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String text) {
            if (!StringUtils.hasText(text)) {
                return null;
            }
            String[] parts = text.split("/", 2);
            byte[] address = ClientIpResolver.parse(parts[0]);
            if (address == null) {
                return null;
            }
            int prefix = address.length * 8;
            if (parts.length == 2) {
                try {
                    prefix = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (prefix < 0 || prefix > address.length * 8) {
                    return null;
                }
            }
            return new Cidr(address, prefix);
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remaining = prefix % 8;
            if (remaining == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remaining)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
