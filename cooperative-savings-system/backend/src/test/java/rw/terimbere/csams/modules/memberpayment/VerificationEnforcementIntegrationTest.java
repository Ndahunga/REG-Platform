package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService;
import rw.terimbere.csams.modules.memberpayment.verification.ProviderCallbackProperties;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * Phase 4 hardening: posting needs credible, independent confirmation by default; refused attempts are audited in their
 * own transaction without sensitive evidence; the deployment's receipt and provider capabilities are stated honestly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerificationEnforcementIntegrationTest {

    private static final YearMonth NOW = YearMonth.now(ZoneId.of("Africa/Kigali"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentVerificationCheckService checkService;
    @Autowired private ProviderCallbackProperties callbackProperties;

    private SchemeFixtures fixtures;
    private Scheme scheme;
    private Actor reviewer;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
        scheme = fixtures.scheme("ACCUMULATING");
        reviewer = fixtures.register(scheme.id(), "ACCOUNTANT");
        jdbc.update("""
                insert into contributions (id, cooperative_id, member_user_id, "year", "month", expected_amount, paid_amount,
                  outstanding_amount, status, ledger_revision, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, 5000, 0, 5000, 'PENDING', 0, now(), now(), 0)
                """, UUID.randomUUID(), scheme.id(), scheme.memberA().id(), NOW.getYear(), NOW.getMonthValue());
    }

    @AfterEach
    void tearDown() {
        strict(false);
        callbackProperties.setSecrets(Map.of());
    }

    // ============================================================ the secure default

    @Test
    void strictVerificationIsOnByDefaultInCodeAndConfigurationAndOffOnlyWhereExplicitlyConfigured() throws Exception {
        Value value = PaymentVerificationCheckService.class.getDeclaredField("requireTrustedConfirmation").getAnnotation(Value.class);
        assertThat(value.value()).as("a missing setting must mean strict").endsWith(":true}");

        assertThat(yaml("application.yml")).containsEntry(
                "app.payments.verification.require-trusted-confirmation", "${APP_PAYMENTS_REQUIRE_TRUSTED_CONFIRMATION:true}");
        // production and staging do not override it
        assertThat(yaml("application-production.yml")).doesNotContainKey("app.payments.verification.require-trusted-confirmation");
        assertThat(yaml("application-staging.yml")).doesNotContainKey("app.payments.verification.require-trusted-confirmation");
        // only the test profile switches it off, explicitly and with a stated reason
        assertThat(String.valueOf(yaml("application-test.yml").get("app.payments.verification.require-trusted-confirmation"))).isEqualTo("false");
        assertThat(yaml("application.yml").get("app.payments.provider-callback.secrets.MTN_MOMO").toString())
                .as("a callback secret is never shipped").isEqualTo("${PAYMENT_CALLBACK_SECRET_MTN_MOMO:}");
    }

    @Test
    void aPaymentVerifiedWithoutConfirmationCannotBePostedUnlessItIsAFlaggedLegacyException() throws Exception {
        UUID payment = submit(scheme.memberA(), "5000", "ENF-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(status(act(reviewer, payment, "/start-review", null))).isEqualTo(200);
        // verified while verification was still permissive (the legacy path)
        assertThat(status(act(scheme.accountantB(), payment, "/verify", evidence()))).isEqualTo(200);
        assertThat(paymentStatus(payment)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select legacy_verification_exception from member_payments where id = ?", Boolean.class, payment))
                .as("only payments that existed at V39 carry the flag").isFalse();

        strict(true);
        MvcResult blocked = act(scheme.accountantA(), payment, "/post", null);
        assertThat(status(blocked)).isEqualTo(422);
        assertThat(code(blocked)).isEqualTo("TRUSTED_CONFIRMATION_REQUIRED");
        assertThat(paymentStatus(payment)).isEqualTo("VERIFIED");
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isZero();
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", scheme.id())).isZero();

        // the refusal is on record, with who and why, committed independently of the refused request
        assertThat(count("select count(*) from audit_logs where action = 'PAYMENT_VERIFICATION_BLOCKED' and entity_id = ? and user_id = ?",
                payment, scheme.accountantA().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select new_values from audit_logs where action = 'PAYMENT_VERIFICATION_BLOCKED' and entity_id = ?",
                String.class, payment)).contains("POST").contains("TRUSTED_CONFIRMATION_REQUIRED");

        // an explicitly flagged legacy payment keeps its earlier path; the exception is visible in the data
        jdbc.update("update member_payments set legacy_verification_exception = true where id = ?", payment);
        MvcResult posted = act(scheme.accountantA(), payment, "/post", null);
        assertThat(status(posted)).as(posted.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(paymentStatus(payment)).isEqualTo("POSTED");
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isEqualTo(1);
    }

    @Test
    void thePosterStillMustDifferFromTheVerifierAndAConflictedPaymentIsNeverPostedEvenForALegacyException() throws Exception {
        UUID payment = submit(scheme.memberA(), "5000", "SEP-" + UUID.randomUUID().toString().substring(0, 8));
        act(reviewer, payment, "/start-review", null);
        assertThat(status(act(scheme.accountantB(), payment, "/verify", evidence()))).isEqualTo(200);
        jdbc.update("update member_payments set legacy_verification_exception = true where id = ?", payment);
        assertThat(status(act(scheme.accountantB(), payment, "/post", null))).as("the verifier cannot post").isEqualTo(403);

        // a recorded conflict blocks posting whatever the flag says
        jdbc.update("""
                insert into payment_verification_checks (id, cooperative_id, payment_id, method, outcome, source, reason_codes, created_at)
                values (?, ?, ?, 'MOBILE_MONEY_RECONCILED', 'CONFLICTED', 'REFERENCE_MATCH', 'AMOUNT_MISMATCH', now())
                """, UUID.randomUUID(), scheme.id(), payment);
        MvcResult blocked = act(scheme.accountantA(), payment, "/post", null);
        assertThat(code(blocked)).isEqualTo("VERIFICATION_BLOCKED_BY_CHECK");
        assertThat(paymentStatus(payment)).isEqualTo("VERIFIED");
    }

    // ============================================================ audit of refused attempts

    @Test
    void blockedVerificationIsAuditedInItsOwnTransactionWithoutSensitiveEvidenceAndWithoutChangingTheLifecycle() throws Exception {
        strict(true);
        UUID payment = submit(scheme.memberA(), "5000", "AUD-" + UUID.randomUUID().toString().substring(0, 8));
        act(reviewer, payment, "/start-review", null);
        String statusBefore = paymentStatus(payment);

        MvcResult blocked = act(scheme.accountantB(), payment, "/verify",
                "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"SECRET-BASIS-TEXT\",\"reference\":\"SECRET-REF-123\"}");
        assertThat(status(blocked)).isEqualTo(422);
        assertThat(code(blocked)).isEqualTo("TRUSTED_CONFIRMATION_REQUIRED");

        assertThat(paymentStatus(payment)).as("the lifecycle is unchanged").isEqualTo(statusBefore);
        assertThat(jdbc.queryForObject("select verification_method from member_payments where id = ?", String.class, payment)).isNull();
        String audit = jdbc.queryForObject(
                "select new_values from audit_logs where action = 'PAYMENT_VERIFICATION_BLOCKED' and entity_id = ? and user_id = ?",
                String.class, payment, scheme.accountantB().id());
        assertThat(audit).contains("VERIFY").contains("TRUSTED_CONFIRMATION_REQUIRED");
        assertThat(audit).as("no evidence is recorded in the audit").doesNotContain("SECRET-BASIS-TEXT").doesNotContain("SECRET-REF-123");
        assertThat(count("select count(*) from payment_verification_checks where payment_id = ? and outcome = 'CONFIRMED'", payment)).isZero();
    }

    // ============================================================ receipt and provider readiness

    @Test
    void theDeploymentStatesWhatItCanReadAndConfirmAndNeverExposesASecret() throws Exception {
        strict(true);
        JsonNode capabilities = data(mockMvc.perform(get(base() + "/verification-capabilities")
                .header("Authorization", bearer(scheme.accountantA()))).andReturn());
        // Tesseract is not installed on the test machine: the state is explicit, not hidden
        assertThat(capabilities.path("ocrAvailable").asBoolean()).isFalse();
        assertThat(capabilities.path("pdfSupported").asBoolean()).as("PDFs are read").isTrue();
        assertThat(capabilities.path("supportedReceiptTypes")).extracting(JsonNode::asText).containsExactly("image/png", "image/jpeg", "application/pdf");
        assertThat(capabilities.path("maxReceiptBytes").asLong()).isPositive();
        assertThat(capabilities.path("ocrTimeoutSeconds").asLong()).isPositive();
        assertThat(capabilities.path("strictVerification").asBoolean()).isTrue();
        assertThat(capabilities.path("providerCallbackConfigured").asBoolean()).as("no credentials, no automatic confirmation").isFalse();

        callbackProperties.setSecrets(Map.of("MTN_MOMO", "super-secret-value"));
        MvcResult configured = mockMvc.perform(get(base() + "/verification-capabilities")
                .header("Authorization", bearer(scheme.accountantA()))).andReturn();
        assertThat(data(configured).path("providerCallbackConfigured").asBoolean()).isTrue();
        assertThat(configured.getResponse().getContentAsString()).doesNotContain("super-secret-value");

        assertThat(status(mockMvc.perform(get(base() + "/verification-capabilities")
                .header("Authorization", bearer(scheme.memberA()))).andReturn())).isEqualTo(403);
    }

    // ============================================================ helpers

    private void strict(boolean value) {
        Object target = AopTestUtils.getUltimateTargetObject(checkService);
        ReflectionTestUtils.setField(target, "requireTrustedConfirmation", value);
    }

    private UUID submit(Actor payer, String amount, String reference) throws Exception {
        return submitBody(payer, null, amount, reference);
    }

    private UUID submitBody(Actor submitter, UUID memberUserId, String amount, String reference) throws Exception {
        String body = "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\",\"transactionReference\":\"%s\",\"allocations\":[{\"type\":\"CONTRIBUTION\",\"amount\":%s,\"periodYear\":%d,\"periodMonth\":%d}]}"
                .formatted(amount, LocalDate.now(ZoneId.of("Africa/Kigali")), reference, amount, NOW.getYear(), NOW.getMonthValue());
        MvcResult result = mockMvc.perform(post(paymentsBase())
                        .header("Authorization", bearer(submitter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(data(result).path("id").asText());
    }

    private MvcResult act(Actor who, UUID payment, String suffix, String json) throws Exception {
        var request = post(paymentsBase() + "/" + payment + suffix).header("Authorization", bearer(who));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String evidence() {
        return "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"Provider confirmation seen\",\"reference\":\"PROV-1\"}";
    }

    private String paymentsBase() {
        return "/api/v1/cooperatives/" + scheme.id() + "/payments";
    }

    private String base() {
        return paymentsBase();
    }

    private Map<String, Object> yaml(String resource) throws IOException {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        var properties = factory.getObject();
        Map<String, Object> flat = new java.util.HashMap<>();
        properties.forEach((k, v) -> flat.put(k.toString(), v));
        return flat;
    }

    private String paymentStatus(UUID payment) {
        return jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String bearer(Actor who) {
        return "Bearer " + who.token();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String code(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
