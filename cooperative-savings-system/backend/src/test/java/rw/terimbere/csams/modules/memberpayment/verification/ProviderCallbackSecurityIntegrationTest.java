package rw.terimbere.csams.modules.memberpayment.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * The signed provider callback: the timestamp is part of what is signed, so a captured request cannot be replayed after
 * the window or altered, and the endpoint's own abuse limits cut floods off. Redelivery inside the window stays
 * idempotent by event id (the concurrent case is in the concurrency tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProviderCallbackSecurityIntegrationTest {

    private static final String SECRET = "callback-security-secret";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProviderCallbackProperties properties;

    private Scheme scheme;

    @BeforeEach
    void setUp() throws Exception {
        scheme = new SchemeFixtures(mockMvc, objectMapper, jdbc).scheme("ACCUMULATING");
        properties.setSecrets(Map.of("MTN_MOMO", SECRET, "AIRTEL_MONEY", SECRET));
    }

    @AfterEach
    void tearDown() {
        properties.setSecrets(Map.of());
        properties.setRatePerAddressPerMinute(0);
        properties.setRatePerChannelPerMinute(0);
        properties.setReplayWindowSeconds(300);
        properties.setMaxFutureSkewSeconds(60);
    }

    @Test
    void aFreshlySignedCallbackIsAcceptedOnceAndItsRedeliveryIsADuplicate() throws Exception {
        String body = body("evt-fresh", "FRESH-REF-001");
        String ts = now();
        MvcResult first = send("MTN_MOMO", body, ts, sign(ts, body));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(code(first)).isEqualTo("CREATED");

        // the same signed request again, inside the window: one confirmation, never two
        MvcResult again = send("MTN_MOMO", body, ts, sign(ts, body));
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(code(again)).isEqualTo("DUPLICATE");
        assertThat(transactions()).isEqualTo(1);
    }

    @Test
    void aValidlySignedButStaleOrFutureDatedRequestIsRefusedAndRecordsNothing() throws Exception {
        String body = body("evt-stale", "STALE-REF-001");
        long now = Instant.now().getEpochSecond();

        String old = String.valueOf(now - 3600);
        MvcResult stale = send("MTN_MOMO", body, old, sign(old, body));
        assertThat(stale.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(stale)).isEqualTo("STALE_TIMESTAMP");

        String future = String.valueOf(now + 3600);
        MvcResult early = send("MTN_MOMO", body, future, sign(future, body));
        assertThat(early.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(early)).isEqualTo("FUTURE_TIMESTAMP");

        // a small skew into the future is tolerated, and the window is configurable
        String slightly = String.valueOf(now + 20);
        assertThat(send("MTN_MOMO", body("evt-skew", "SKEW-REF-001"), slightly, sign(slightly, body("evt-skew", "SKEW-REF-001")))
                .getResponse().getStatus()).isEqualTo(200);
        properties.setReplayWindowSeconds(7200);
        String hourOld = String.valueOf(now - 3600);
        String lateBody = body("evt-late", "LATE-REF-001");
        assertThat(send("MTN_MOMO", lateBody, hourOld, sign(hourOld, lateBody)).getResponse().getStatus())
                .as("a longer configured window accepts it").isEqualTo(200);
        assertThat(transactions()).isEqualTo(2);
    }

    @Test
    void theTimestampCannotBeAlteredAndTheBodyAloneIsNoLongerEnough() throws Exception {
        String body = body("evt-tamper", "TAMPER-REF-001");
        String ts = now();
        // a captured signature with a fresher timestamp swapped in
        String fresher = String.valueOf(Long.parseLong(ts) + 1);
        assertThat(code(send("MTN_MOMO", body, fresher, sign(ts, body)))).isEqualTo("INVALID_SIGNATURE");
        // the earlier contract (signature over the body only) is refused
        assertThat(code(send("MTN_MOMO", body, ts, hmac(body.getBytes(StandardCharsets.UTF_8))))).isEqualTo("INVALID_SIGNATURE");
        // the body changed after signing
        String changed = body.replace("1500", "9999");
        assertThat(code(send("MTN_MOMO", changed, ts, sign(ts, body)))).isEqualTo("INVALID_SIGNATURE");
        // the wrong secret
        assertThat(code(send("MTN_MOMO", body, ts, "deadbeef"))).isEqualTo("INVALID_SIGNATURE");
        // no timestamp at all
        MvcResult missing = mockMvc.perform(post("/api/v1/public/payment-callbacks/MTN_MOMO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", sign(ts, body))
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(missing)).isEqualTo("MISSING_TIMESTAMP");
        // correctly signed, but not a number: refused only after authentication, with a clear reason
        assertThat(code(send("MTN_MOMO", body, "yesterday", sign("yesterday", body)))).isEqualTo("INVALID_TIMESTAMP");
        assertThat(transactions()).isZero();
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsProcessedEvenWhenSignedCorrectly() throws Exception {
        byte[] huge = new byte[256 * 1024];
        Arrays.fill(huge, (byte) 'a');
        String ts = now();
        MvcResult result = mockMvc.perform(post("/api/v1/public/payment-callbacks/MTN_MOMO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Timestamp", ts)
                        .header("X-Signature", hmac((ts + ".").getBytes(StandardCharsets.UTF_8), huge))
                        .content(huge))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(transactions()).isZero();
    }

    @Test
    void floodsFromOneAddressAreCutOffWithoutLockingOutAuthenticatedTraffic() throws Exception {
        properties.setRatePerAddressPerMinute(3);
        String body = body("evt-flood", "FLOOD-REF-001");
        String ts = now();
        int tooMany = 0;
        for (int i = 0; i < 6; i++) {
            MvcResult r = send("MTN_MOMO", body, ts, "bad");
            if (r.getResponse().getStatus() == 429) {
                tooMany++;
                assertThat(code(r)).isEqualTo("RATE_LIMITED");
                assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("60");
            }
        }
        assertThat(tooMany).as("the address limit cut the flood off after three requests").isEqualTo(3);
        assertThat(transactions()).isZero();
    }

    @Test
    void theChannelLimitCountsOnlyAuthenticatedRequestsSoAnonymousNoiseCannotStarveTheProvider() throws Exception {
        properties.setRatePerAddressPerMinute(0);
        properties.setRatePerChannelPerMinute(2);
        String ts = now();
        // anonymous noise on the channel does not use the channel's quota
        for (int i = 0; i < 5; i++) {
            assertThat(send("AIRTEL_MONEY", body("noise-" + i, "NOISE-REF-00" + i), ts, "bad").getResponse().getStatus()).isEqualTo(401);
        }
        String one = body("evt-a", "CHAN-REF-0001");
        String two = body("evt-b", "CHAN-REF-0002");
        String three = body("evt-c", "CHAN-REF-0003");
        assertThat(send("AIRTEL_MONEY", one, ts, sign(ts, one)).getResponse().getStatus()).isEqualTo(200);
        assertThat(send("AIRTEL_MONEY", two, ts, sign(ts, two)).getResponse().getStatus()).isEqualTo(200);
        MvcResult limited = send("AIRTEL_MONEY", three, ts, sign(ts, three));
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("RATE_LIMITED");
        assertThat(transactions()).isEqualTo(2);
    }

    @Test
    void withoutAConfiguredSecretNothingIsAcceptedWhateverIsSigned() throws Exception {
        properties.setSecrets(Map.of());
        String body = body("evt-off", "OFF-REF-0001");
        String ts = now();
        MvcResult result = send("MTN_MOMO", body, ts, sign(ts, body));
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(code(result)).isEqualTo("PROVIDER_NOT_CONFIGURED");
        assertThat(transactions()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String body(String eventId, String reference) {
        return "{\"cooperativeId\":\"%s\",\"eventId\":\"%s\",\"reference\":\"%s\",\"amount\":1500,\"currency\":\"RWF\",\"status\":\"SUCCESS\"}"
                .formatted(scheme.id(), eventId, reference);
    }

    private MvcResult send(String channel, String body, String timestamp, String signature) throws Exception {
        return mockMvc.perform(post("/api/v1/public/payment-callbacks/" + channel)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", signature)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andReturn();
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static String sign(String timestamp, String body) throws Exception {
        return hmac((timestamp + ".").getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(byte[]... parts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        for (byte[] part : parts) {
            mac.update(part);
        }
        return HexFormat.of().formatHex(mac.doFinal());
    }

    private String code(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private long transactions() {
        Long n = jdbc.queryForObject("select count(*) from trusted_transactions where cooperative_id = ?", Long.class, scheme.id());
        return n == null ? 0 : n;
    }
}
