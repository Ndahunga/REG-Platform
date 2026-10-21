package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = ClientIpResolver.withDefaults();

    private static MockHttpServletRequest request(String peer, String... forwardedHeaderLines) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/public/contact");
        request.setRemoteAddr(peer);
        for (String line : forwardedHeaderLines) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    // ------------------------------------------------------------------ a client that is not a trusted proxy

    @Test
    void aDirectClientCannotChooseItsAddress_everyForwardingHeaderIsIgnored() {
        assertThat(resolver.resolve(request("203.0.113.9", "198.51.100.77"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("203.0.113.9", "1.1.1.1, 2.2.2.2, 10.0.0.1"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("203.0.113.9", "garbage"))).isEqualTo("203.0.113.9");
    }

    @Test
    void aDirectClientRotatingTheHeaderStaysOnOneAddress() {
        String first = resolver.resolve(request("203.0.113.9", "9.9.9.1"));
        String second = resolver.resolve(request("203.0.113.9", "9.9.9.2"));
        assertThat(first).isEqualTo(second).isEqualTo("203.0.113.9");
    }

    // ------------------------------------------------------------------ behind a trusted proxy (Render)

    @Test
    void behindAProxyTheAddressTheProxyAppendedIsUsed() {
        // the proxy (10.1.2.3) saw the real client 203.0.113.50 and appended it
        assertThat(resolver.resolve(request("10.1.2.3", "203.0.113.50"))).isEqualTo("203.0.113.50");
    }

    @Test
    void whatTheClientTypedToTheLeftOfTheProxysEntryIsNeverReached() {
        assertThat(resolver.resolve(request("10.1.2.3", "6.6.6.6, 7.7.7.7, 203.0.113.50"))).isEqualTo("203.0.113.50");
        // many spoofed values, one real client: always the same key
        for (String spoof : new String[] {"1.1.1.1", "2.2.2.2", "3.3.3.3, 4.4.4.4", "::1", "192.168.0.1"}) {
            assertThat(resolver.resolve(request("10.1.2.3", spoof + ", 203.0.113.50"))).isEqualTo("203.0.113.50");
        }
    }

    @Test
    void chainedTrustedProxiesAreSkippedFromTheRight() {
        // client -> edge proxy (172.16.0.5) -> platform proxy (10.0.0.9) -> app
        assertThat(resolver.resolve(request("10.0.0.9", "5.5.5.5, 203.0.113.50, 172.16.0.5")))
                .isEqualTo("203.0.113.50");
    }

    @Test
    void severalHeaderLinesAreReadAsOneChain() {
        assertThat(resolver.resolve(request("10.1.2.3", "6.6.6.6", "203.0.113.50"))).isEqualTo("203.0.113.50");
    }

    @Test
    void withNoUsableHeaderTheProxyAddressIsUsed() {
        assertThat(resolver.resolve(request("10.1.2.3"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", "  "))).isEqualTo("10.1.2.3");
        // everything in the chain is itself a trusted proxy
        assertThat(resolver.resolve(request("10.1.2.3", "10.5.5.5, 192.168.1.1"))).isEqualTo("10.1.2.3");
    }

    @Test
    void anUnreadableEntryNextToTheProxyIsNotTrustedPastItsPosition() {
        assertThat(resolver.resolve(request("10.1.2.3", "203.0.113.50, not-an-ip"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", "203.0.113.50, 300.1.1.1"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", "203.0.113.50, 010.0.0.1"))).isEqualTo("10.1.2.3");
    }

    // ------------------------------------------------------------------ normalisation

    @Test
    void theSameAddressWrittenDifferentlyIsOneKey() {
        String expected = "203.0.113.50";
        for (String written : new String[] {
            "203.0.113.50", " 203.0.113.50 ", "203.0.113.50:51234", "::ffff:203.0.113.50", "[::ffff:203.0.113.50]:443",
        }) {
            assertThat(resolver.resolve(request("10.1.2.3", written))).as(written).isEqualTo(expected);
        }
    }

    @Test
    void ipv6AddressesAreCanonicalised() {
        String a = resolver.resolve(request("10.1.2.3", "2001:db8::1"));
        String b = resolver.resolve(request("10.1.2.3", "2001:0DB8:0:0:0:0:0:1"));
        String c = resolver.resolve(request("10.1.2.3", "[2001:db8::1]:8080"));
        String d = resolver.resolve(request("10.1.2.3", "2001:db8::1%eth0"));
        assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo(d);
        assertThat(a).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void thePeerAddressItselfIsNormalised() {
        assertThat(resolver.resolve(request("::ffff:203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("0:0:0:0:0:0:0:1"))).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void anUnreadablePeerSharesOneBucketInsteadOfFailing() {
        assertThat(resolver.resolve(request("not-an-address", "1.1.1.1"))).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(resolver.resolve(request(""))).isEqualTo(ClientIpResolver.UNKNOWN);
    }

    @Test
    void hostnamesAreNeverResolvedOrAccepted() {
        // would require a DNS lookup if it were handed to the JDK; it is rejected as a non-literal
        assertThat(resolver.resolve(request("10.1.2.3", "example.com"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", "localhost"))).isEqualTo("10.1.2.3");
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void theTrustedProxyListCanBeConfigured() {
        ClientIpResolver custom = new ClientIpResolver("198.51.100.0/24, 203.0.113.7");
        // the default private ranges are no longer trusted once a list is configured
        assertThat(custom.resolve(request("10.1.2.3", "9.9.9.9"))).isEqualTo("10.1.2.3");
        // the configured ones are
        assertThat(custom.resolve(request("198.51.100.20", "9.9.9.9"))).isEqualTo("9.9.9.9");
        assertThat(custom.resolve(request("203.0.113.7", "8.8.8.8"))).isEqualTo("8.8.8.8");
        assertThat(custom.resolve(request("203.0.113.8", "8.8.8.8"))).isEqualTo("203.0.113.8");
    }

    @Test
    void invalidConfiguredEntriesAreIgnored() {
        ClientIpResolver custom = new ClientIpResolver("nonsense, 10.0.0.0/99, 192.0.2.0/24");
        assertThat(custom.resolve(request("192.0.2.5", "9.9.9.9"))).isEqualTo("9.9.9.9");
        assertThat(custom.resolve(request("10.1.2.3", "9.9.9.9"))).isEqualTo("10.1.2.3");
    }
}
