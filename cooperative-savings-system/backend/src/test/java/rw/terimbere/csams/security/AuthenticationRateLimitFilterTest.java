package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationRateLimitFilterTest {

    private static final String CONTACT = "/api/v1/public/contact";
    private static final String LOGIN = "/api/v1/auth/login";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private AuthenticationRateLimitFilter filter(boolean enabled, int authMax, int contactMax) {
        return new AuthenticationRateLimitFilter(mapper, enabled, authMax, contactMax, ClientIpResolver.withDefaults());
    }

    private int hit(AuthenticationRateLimitFilter filter, String path, String peer, String... forwarded)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(peer);
        for (String line : forwarded) {
            request.addHeader("X-Forwarded-For", line);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    @Test
    void theContactFormHasItsOwnStricterLimit() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 20, 2);

        assertThat(hit(filter, CONTACT, "10.0.0.1")).isEqualTo(200);
        assertThat(hit(filter, CONTACT, "10.0.0.1")).isEqualTo(200);
        assertThat(hit(filter, CONTACT, "10.0.0.1")).isEqualTo(429);
    }

    @Test
    void theLimitIsPerVisitor() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 20, 1);

        assertThat(hit(filter, CONTACT, "10.0.0.1")).isEqualTo(200);
        assertThat(hit(filter, CONTACT, "10.0.0.1")).isEqualTo(429);
        assertThat(hit(filter, CONTACT, "10.0.0.2")).isEqualTo(200);
    }

    @Test
    void contactMessagesAndSignInsDoNotShareAnAllowance() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 1, 1);

        assertThat(hit(filter, CONTACT, "10.0.0.1")).isEqualTo(200);
        assertThat(hit(filter, CONTACT, "10.0.0.1")).isEqualTo(429);
        // sign-in from the same address is unaffected by the exhausted contact bucket
        assertThat(hit(filter, LOGIN, "10.0.0.1")).isEqualTo(200);
        assertThat(hit(filter, LOGIN, "10.0.0.1")).isEqualTo(429);
    }

    @Test
    void theContactRejectionHasItsOwnFriendlyMessage() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 20, 1);
        hit(filter, CONTACT, "10.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", CONTACT);
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("Too many messages").doesNotContain("authentication");
    }

    @Test
    void whenDisabledNothingIsLimited() throws Exception {
        AuthenticationRateLimitFilter filter = filter(false, 1, 1);

        for (int i = 0; i < 5; i++) {
            assertThat(hit(filter, CONTACT, "10.0.0.1")).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ spoofed X-Forwarded-For

    @Test
    void aDirectClientCannotEscapeTheLimitByRotatingXForwardedFor() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 3, 2);

        assertThat(hit(filter, CONTACT, "203.0.113.9", "1.1.1.1")).isEqualTo(200);
        assertThat(hit(filter, CONTACT, "203.0.113.9", "2.2.2.2")).isEqualTo(200);
        // a fresh spoofed value each time still lands in the same bucket
        assertThat(hit(filter, CONTACT, "203.0.113.9", "3.3.3.3")).isEqualTo(429);
        assertThat(hit(filter, CONTACT, "203.0.113.9", "4.4.4.4, 5.5.5.5")).isEqualTo(429);

        // and the sign-in limiter is closed to the same trick
        for (int i = 1; i <= 3; i++) {
            assertThat(hit(filter, LOGIN, "203.0.113.9", "9.9.9." + i)).isEqualTo(200);
        }
        assertThat(hit(filter, LOGIN, "203.0.113.9", "9.9.9.99")).isEqualTo(429);
    }

    @Test
    void aDirectClientCannotPoisonAnotherVisitorsBucketByClaimingTheirAddress() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 20, 1);

        // an attacker claims to be 198.51.100.77 to burn that visitor's allowance
        assertThat(hit(filter, CONTACT, "203.0.113.9", "198.51.100.77")).isEqualTo(200);
        assertThat(hit(filter, CONTACT, "203.0.113.9", "198.51.100.77")).isEqualTo(429);
        // the real 198.51.100.77, connecting directly, is unaffected
        assertThat(hit(filter, CONTACT, "198.51.100.77")).isEqualTo(200);
    }

    @Test
    void behindTheProxyEachRealClientHasItsOwnBucketWhateverTheyPrependToTheHeader() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 20, 1);
        String proxy = "10.8.0.4";

        assertThat(hit(filter, CONTACT, proxy, "203.0.113.50")).isEqualTo(200);
        // same real client, different junk typed in front: same bucket, so limited
        assertThat(hit(filter, CONTACT, proxy, "6.6.6.6, 203.0.113.50")).isEqualTo(429);
        assertThat(hit(filter, CONTACT, proxy, "7.7.7.7, 8.8.8.8, 203.0.113.50")).isEqualTo(429);
        // a different real client behind the same proxy is not affected
        assertThat(hit(filter, CONTACT, proxy, "203.0.113.51")).isEqualTo(200);
    }

    @Test
    void multipleHeaderLinesAndEquivalentWritingsOfOneAddressShareABucket() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 20, 1);
        String proxy = "10.8.0.4";

        assertThat(hit(filter, CONTACT, proxy, "203.0.113.50")).isEqualTo(200);
        assertThat(hit(filter, CONTACT, proxy, "1.2.3.4", "203.0.113.50")).isEqualTo(429);
        assertThat(hit(filter, CONTACT, proxy, "::ffff:203.0.113.50")).isEqualTo(429);
        assertThat(hit(filter, CONTACT, proxy, "203.0.113.50:60000")).isEqualTo(429);
    }

    @Test
    void anyoneBehindTheProxyIsStillLimitedWhenTheHeaderIsMissingOrGarbage() throws Exception {
        AuthenticationRateLimitFilter filter = filter(true, 20, 1);
        String proxy = "10.8.0.4";

        assertThat(hit(filter, CONTACT, proxy)).isEqualTo(200);
        assertThat(hit(filter, CONTACT, proxy, "not-an-ip")).isEqualTo(429);
        assertThat(hit(filter, CONTACT, proxy, "300.300.300.300")).isEqualTo(429);
    }
}
