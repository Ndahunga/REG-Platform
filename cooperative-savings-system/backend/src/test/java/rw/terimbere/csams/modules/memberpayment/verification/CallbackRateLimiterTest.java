package rw.terimbere.csams.modules.memberpayment.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CallbackRateLimiterTest {

    /** A clock the test moves by hand. */
    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsTheLimitWithinAMinuteThenRefusesAndRecoversInTheNextMinute() {
        MovingClock clock = new MovingClock();
        CallbackRateLimiter limiter = new CallbackRateLimiter(clock);
        assertThat(limiter.tryAcquire("a", 2)).isTrue();
        assertThat(limiter.tryAcquire("a", 2)).isTrue();
        assertThat(limiter.tryAcquire("a", 2)).isFalse();
        assertThat(limiter.tryAcquire("b", 2)).as("keys are independent").isTrue();
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("a", 2)).as("a new window").isTrue();
    }

    @Test
    void aLimitOfZeroDisablesLimiting() {
        CallbackRateLimiter limiter = new CallbackRateLimiter(new MovingClock());
        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire("a", 0)).isTrue();
        }
    }

    @Test
    void aFloodOfDistinctKeysCannotGrowTheTableWithoutBound() {
        CallbackRateLimiter limiter = new CallbackRateLimiter(new MovingClock());
        for (int i = 0; i < CallbackRateLimiter.MAX_KEYS + 500; i++) {
            limiter.tryAcquire("k" + i, 5);
        }
        // past the bound, new keys share one bucket, which fills and refuses
        boolean refused = false;
        for (int i = 0; i < 20; i++) {
            refused |= !limiter.tryAcquire("fresh" + i, 5);
        }
        assertThat(refused).isTrue();
    }
}
