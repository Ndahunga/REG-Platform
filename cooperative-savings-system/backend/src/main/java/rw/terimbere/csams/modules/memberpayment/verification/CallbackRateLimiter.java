package rw.terimbere.csams.modules.memberpayment.verification;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A small in-process fixed-window limiter for the public callback endpoint. It bounds abuse of one application
 * instance: per remote address for every request (so unauthenticated floods cost the sender, not the provider), and per
 * channel for authenticated requests only (so an anonymous flood can never lock the real provider out). It is not a
 * replacement for rate limiting at the reverse proxy or gateway, which a deployment still needs (it also sees the
 * original client address and protects every instance together). The key table is bounded.
 */
@Component
public class CallbackRateLimiter {

    static final int MAX_KEYS = 10_000;
    private static final long WINDOW_MILLIS = 60_000;
    private static final String OVERFLOW_KEY = "~overflow";

    private final Clock clock;
    private final Map<String, long[]> windows = new HashMap<>(); // key -> {windowStartMillis, count}

    public CallbackRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts one request against {@code key}; false when it exceeds {@code limitPerMinute} in the current minute. */
    public synchronized boolean tryAcquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true; // limiting disabled for this scope
        }
        long now = clock.millis();
        String bucket = key;
        if (windows.size() >= MAX_KEYS && !windows.containsKey(bucket)) {
            purgeExpired(now);
            if (windows.size() >= MAX_KEYS) {
                bucket = OVERFLOW_KEY; // a flood of distinct keys shares one bucket instead of growing the table
            }
        }
        long[] window = windows.computeIfAbsent(bucket, k -> new long[] {now, 0});
        if (now - window[0] >= WINDOW_MILLIS) {
            window[0] = now;
            window[1] = 0;
        }
        window[1]++;
        return window[1] <= limitPerMinute;
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<String, long[]>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue()[0] >= WINDOW_MILLIS) {
                it.remove();
            }
        }
    }
}
