package rw.terimbere.csams.modules.rotation.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.memberpayment.receipt.ScriptedOcrEngine;
import rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService;
import rw.terimbere.csams.modules.memberpayment.verification.ProviderCallbackProperties;
import rw.terimbere.csams.modules.share.service.ShareValuationService;

/**
 * Phase 3: receipt reading, trusted verification, provider callbacks, legacy rotating reconciliation and the NAV
 * categories, at HTTP level on H2 in PostgreSQL mode. PostgreSQL behaviour is NOT verified by these tests.
 *
 * <p>The OCR engine is a scripted stand-in: these tests prove the workflow and its guarantees (advisory only, never
 * proof, never an automatic verification or posting), not text recognition quality. No provider is contacted: provider
 * confirmation here is the signed generic callback and the officer's statement import.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase3VerificationIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final YearMonth NOW = YearMonth.now(KIGALI);
    private static final YearMonth NEXT = NOW.plusMonths(1);
    private static final String SECRET = "unit-test-callback-secret";

    private record Actor(UUID id, String token) {}

    private record Fx(UUID scheme, Actor payer, Actor r1, Actor r2, Actor president, Actor accA, Actor accB, Actor accC) {}

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShareValuationService shareValuationService;
    @Autowired private PaymentVerificationCheckService checkService;
    @Autowired private ProviderCallbackProperties callbackProperties;

    private String superAdminToken;
    private UUID superAdminId;
    private int salt;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        superAdminId = jdbc.queryForObject("select id from users where username = 'superadmin'", UUID.class);
        ScriptedOcrEngine.clear();
    }

    @AfterEach
    void tearDown() {
        ScriptedOcrEngine.clear();
        requireTrusted(false);
        callbackProperties.setSecrets(Map.of());
    }

    // ============================================================ receipt reading

    @Test
    void receiptReadingIsAdvisoryReportsUnavailableWithoutAnEngineAndIsIdempotentPerReceipt() throws Exception {
        Fx fx = hybrid();
        String ref = ref();
        UUID payment = submit(fx, "MTN_MOMO", "1500", ref, upload(fx));

        // no engine installed and none scripted: honest "unavailable", nothing invented, nothing proven
        JsonNode unavailable = data(postAs(fx.accA(), fx, "/payments/" + payment + "/receipt/extract", null));
        assertThat(unavailable.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.path("failureCode").asText()).isEqualTo("OCR_ENGINE_UNAVAILABLE");
        assertThat(unavailable.path("proofOfPayment").asBoolean()).isFalse();

        ScriptedOcrEngine.script(receiptText(ref, "1,500", "0788123456"), 0.95);
        JsonNode read = data(postAs(fx.accA(), fx, "/payments/" + payment + "/receipt/extract", null));
        assertThat(read.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(read.path("attempts").asInt()).as("an unavailable extraction is retried in place").isEqualTo(2);
        assertThat(field(read, "amount").path("value").asText()).isEqualTo("1500.0000");
        assertThat(field(read, "reference").path("level").asText()).isIn("HIGH", "MEDIUM");
        assertThat(read.path("discrepancies")).isEmpty();
        assertThat(read.path("proofOfPayment").asBoolean()).isFalse();

        JsonNode again = data(postAs(fx.accB(), fx, "/payments/" + payment + "/receipt/extract", null));
        assertThat(again.path("id").asText()).as("the same receipt is never processed twice").isEqualTo(read.path("id").asText());
        assertThat(again.path("attempts").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from payment_receipt_extractions where payment_id = ?", payment)).isEqualTo(1);

        // reading a receipt verifies, confirms and posts nothing
        assertThat(paymentStatus(payment)).isEqualTo("SUBMITTED");
        assertThat(current(fx, payment).path("outcome").asText()).isEqualTo("UNMATCHED");
        assertThat(count("select count(*) from payment_verification_checks where payment_id = ? and outcome = 'CONFIRMED'", payment)).isZero();

        // staff only, and only for a payment that has a receipt
        assertThat(status(postAs(fx.payer(), fx, "/payments/" + payment + "/receipt/extract", null))).isEqualTo(403);
        UUID noReceipt = submit(fx, "MTN_MOMO", "100", ref(), null);
        MvcResult none = postAs(fx.accA(), fx, "/payments/" + noReceipt + "/receipt/extract", null);
        assertThat(status(none)).isEqualTo(422);
        assertThat(code(none)).isEqualTo("RECEIPT_NOT_UPLOADED");
    }

    @Test
    void forgedMismatchedAndReusedReceiptsAreFlaggedAndAnOfficerReviewIsRecordedOnce() throws Exception {
        Fx fx = hybrid();
        byte[] shared = png(7777);
        String refA = ref();
        String refB = ref();
        UUID a = submit(fx, "MTN_MOMO", "1500", refA, upload(fx, shared));
        UUID b = submit(fx, "MTN_MOMO", "1000", refB, upload(fx, shared));

        ScriptedOcrEngine.script(receiptText("FORGED9999", "9,999", "0788123456"), 0.9);
        JsonNode first = data(postAs(fx.accA(), fx, "/payments/" + a + "/receipt/extract", null));
        assertThat(first.path("duplicateReceipt").asBoolean()).isFalse();
        assertThat(first.path("discrepancies").toString()).contains("AMOUNT_MISMATCH", "REFERENCE_MISMATCH");

        JsonNode second = data(postAs(fx.accA(), fx, "/payments/" + b + "/receipt/extract", null));
        assertThat(second.path("duplicateReceipt").asBoolean()).as("the same image on another payment").isTrue();
        assertThat(second.path("duplicateOfPaymentId").asText()).isEqualTo(a.toString());
        assertThat(second.path("discrepancies").toString()).contains("DUPLICATE_RECEIPT");

        // OCR can only add doubt: a perfect-looking forged receipt still confirms nothing
        assertThat(current(fx, a).path("outcome").asText()).isNotEqualTo("CONFIRMED");

        String extractionId = first.path("id").asText();
        assertThat(status(postAs(fx.payer(), fx, "/payments/" + a + "/receipt/" + extractionId + "/review", "{\"note\":\"mine\"}"))).isEqualTo(403);
        JsonNode reviewed = data(postAs(fx.accB(), fx, "/payments/" + a + "/receipt/" + extractionId + "/review", "{\"note\":\"Looks edited\"}"));
        assertThat(reviewed.path("reviewedBy").asText()).isEqualTo(fx.accB().id().toString());
        JsonNode second2 = data(postAs(fx.accC(), fx, "/payments/" + a + "/receipt/" + extractionId + "/review", "{\"note\":\"overwrite\"}"));
        assertThat(second2.path("reviewedBy").asText()).as("a review is never overwritten").isEqualTo(fx.accB().id().toString());
        assertThat(second2.path("reviewNote").asText()).isEqualTo("Looks edited");
    }

    @Test
    void anEngineFailureAndAnUnsupportedFileAreRecordedHonestlyAndAFailedReadCanBeRetried() throws Exception {
        Fx fx = hybrid();
        String ref = ref();
        UUID payment = submit(fx, "MTN_MOMO", "1500", ref, upload(fx));

        ScriptedOcrEngine.fail("OCR_TIMEOUT");
        JsonNode failed = data(postAs(fx.accA(), fx, "/payments/" + payment + "/receipt/extract", null));
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("failureCode").asText()).isEqualTo("OCR_TIMEOUT");
        assertThat(failed.path("fields")).isEmpty();

        ScriptedOcrEngine.script(receiptText(ref, "1,500", "0788123456"), 0.9);
        JsonNode retried = data(postAs(fx.accA(), fx, "/payments/" + payment + "/receipt/extract", null));
        assertThat(retried.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(retried.path("id").asText()).isEqualTo(failed.path("id").asText());

        // a PDF is opened by the PDF reader, never by the image engine; a broken one is reported as such
        MockMultipartFile pdf = new MockMultipartFile("file", "receipt.pdf", "application/pdf", "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/cooperatives/" + fx.scheme() + "/files")
                        .file(pdf)
                        .param("category", "CONTRIBUTION_EVIDENCE")
                        .header("Authorization", bearer(fx.payer().token())))
                .andReturn();
        assertThat(status(uploaded)).isEqualTo(200);
        // a receipt the system cannot read is submitted for a manual review, never silently accepted as clear
        UUID pdfPayment = submit(fx, "MTN_MOMO", "200", ref(), data(uploaded).path("storageKey").asText(), true);
        JsonNode unsupported = data(postAs(fx.accA(), fx, "/payments/" + pdfPayment + "/receipt/extract", null));
        assertThat(unsupported.path("status").asText()).isEqualTo("FAILED");
        assertThat(unsupported.path("failureCode").asText()).as("a broken PDF is reported, never guessed at").isEqualTo("PDF_UNREADABLE");
    }

    // ============================================================ trusted verification

    @Test
    void anAuthoritativeStatementLineConfirmsOnlyAfterAMatchAndAnIndependentOfficerVerifiesAndAnotherPosts() throws Exception {
        requireTrusted(true);
        Fx fx = hybrid();
        UUID payment = submit(fx, "MTN_MOMO", "1500", "TX-REF-77", null);
        assertThat(status(payments(fx.accC(), fx, payment, "/start-review", null))).isEqualTo(200);

        // nothing authoritative yet: verification is refused
        MvcResult early = payments(fx.accB(), fx, payment, "/verify", evidence("MOBILE_MONEY_RECONCILED", "STMT-1"));
        assertThat(status(early)).isEqualTo(422);
        assertThat(code(early)).isEqualTo("TRUSTED_CONFIRMATION_REQUIRED");
        // (the refused attempt rolled back with the request; an officer's re-check records the outcome)
        assertThat(data(postAs(fx.accA(), fx, "/payments/" + payment + "/verification-checks/recheck", null)).path("outcome").asText())
                .isEqualTo("UNMATCHED");

        // an officer imports the statement line; the reference matches regardless of punctuation and case
        JsonNode imported = data(importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "tx ref 77", "1500", "RWF", "SUCCESS", null)));
        assertThat(imported.path("results").get(0).path("result").asText()).isEqualTo("CREATED");
        JsonNode confirmed = current(fx, payment);
        assertThat(confirmed.path("outcome").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("method").asText()).isEqualTo("MOBILE_MONEY_RECONCILED");
        assertThat(confirmed.path("authoritative").asBoolean()).isTrue();
        assertThat(paymentStatus(payment)).as("a confirmation is evidence, not a status change").isEqualTo("UNDER_REVIEW");
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isZero();

        // the officer who recorded the line cannot verify the payment it confirms
        MvcResult recorder = payments(fx.accA(), fx, payment, "/verify", evidence("MOBILE_MONEY_RECONCILED", "STMT-1"));
        assertThat(status(recorder)).isEqualTo(422);
        assertThat(code(recorder)).isEqualTo("VERIFIER_RECORDED_TRANSACTION");
        // the evidence method must be the one that was actually confirmed
        MvcResult wrong = payments(fx.accB(), fx, payment, "/verify", evidence("PROVIDER_CONFIRMED", "PROV-9"));
        assertThat(code(wrong)).isEqualTo("VERIFICATION_METHOD_NOT_CONFIRMED");

        MvcResult verified = payments(fx.accB(), fx, payment, "/verify", evidence("MOBILE_MONEY_RECONCILED", "STMT-1"));
        assertThat(status(verified)).as(verified.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(count("select count(*) from trusted_transactions where consumed_by_payment_id = ?", payment)).isEqualTo(1);
        // posting is still a separate, explicit act by a different officer (no automatic posting happened)
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isZero();
        assertThat(status(payments(fx.accB(), fx, payment, "/post", null))).as("verifier cannot post").isEqualTo(403);
        assertThat(status(payments(fx.accA(), fx, payment, "/post", null))).isEqualTo(200);
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isEqualTo(1);

        // a repeated import is the same transaction: one row, reported as a duplicate
        JsonNode repeat = data(importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "TXREF77", "1500", "RWF", "SUCCESS", null)));
        assertThat(repeat.path("results").get(0).path("result").asText()).isEqualTo("DUPLICATE");
        assertThat(count("select count(*) from trusted_transactions where cooperative_id = ?", fx.scheme())).isEqualTo(1);
    }

    @Test
    void conflictingFailedPendingAndCrossSchemeEvidenceNeverVerifiesEvenWithoutTheStrictSetting() throws Exception {
        Fx fx = hybrid();
        Fx other = hybrid();

        // amount mismatch
        UUID amount = submit(fx, "MTN_MOMO", "1500", "AMT1", null);
        importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "AMT1", "1400", "RWF", "SUCCESS", null));
        JsonNode amountCheck = current(fx, amount);
        assertThat(amountCheck.path("outcome").asText()).isEqualTo("CONFLICTED");
        assertThat(amountCheck.path("reasons").toString()).contains("AMOUNT_MISMATCH");
        assertThat(status(payments(fx.accC(), fx, amount, "/start-review", null))).isEqualTo(200);
        MvcResult blocked = payments(fx.accB(), fx, amount, "/verify", evidence("MOBILE_MONEY_RECONCILED", "S1"));
        assertThat(status(blocked)).isEqualTo(422);
        assertThat(code(blocked)).isEqualTo("VERIFICATION_BLOCKED_BY_CHECK");

        // currency mismatch
        UUID currency = submit(fx, "MTN_MOMO", "1500", "CUR1", null);
        importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "CUR1", "1500", "USD", "SUCCESS", null));
        assertThat(current(fx, currency).path("reasons").toString()).contains("CURRENCY_MISMATCH");

        // the provider says the transaction failed
        UUID failed = submit(fx, "MTN_MOMO", "1500", "FAIL1", null);
        importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "FAIL1", "1500", "RWF", "FAILED", null));
        assertThat(current(fx, failed).path("outcome").asText()).isEqualTo("FAILED");
        assertThat(status(payments(fx.accC(), fx, failed, "/start-review", null))).isEqualTo(200);
        assertThat(code(payments(fx.accB(), fx, failed, "/verify", evidence("MOBILE_MONEY_RECONCILED", "S2")))).isEqualTo("VERIFICATION_BLOCKED_BY_CHECK");

        // pending settles later: PENDING, then an update to SUCCESS confirms
        UUID pending = submit(fx, "MTN_MOMO", "1500", "PEND1", null);
        importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "PEND1", "1500", "RWF", "PENDING", null));
        assertThat(current(fx, pending).path("outcome").asText()).isEqualTo("PENDING");
        JsonNode settled = data(importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "PEND1", "1500", "RWF", "SUCCESS", null)));
        assertThat(settled.path("results").get(0).path("result").asText()).isEqualTo("UPDATED");
        assertThat(current(fx, pending).path("outcome").asText()).isEqualTo("CONFIRMED");

        // a later record that disagrees marks the transaction conflicted and is never auto-resolved
        JsonNode disagree = data(importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "PEND1", "1600", "RWF", "SUCCESS", null)));
        assertThat(disagree.path("results").get(0).path("result").asText()).isEqualTo("CONFLICT");
        assertThat(current(fx, pending).path("outcome").asText()).isEqualTo("CONFLICTED");
        assertThat(current(fx, pending).path("reasons").toString()).contains("CONFLICTING_EVIDENCE");
        importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "PEND1", "1500", "RWF", "SUCCESS", null));
        assertThat(data(postAs(fx.accA(), fx, "/payments/" + pending + "/verification-checks/recheck", null)).path("outcome").asText())
                .as("an identical re-import does not clear a conflict").isEqualTo("CONFLICTED");

        // another scheme's statement line cannot match: it is not visible to this scheme
        UUID cross = submit(fx, "MTN_MOMO", "1500", "CROSS1", null);
        importLines(other.accA(), other, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "CROSS1", "1500", "RWF", "SUCCESS", null));
        assertThat(data(postAs(fx.accA(), fx, "/payments/" + cross + "/verification-checks/recheck", null)).path("outcome").asText()).isEqualTo("UNMATCHED");
        assertThat(count("select count(*) from trusted_transactions where cooperative_id = ?", fx.scheme())).isEqualTo(4);
        assertThat(status(getAs(other.accA(), fx, "/payments/" + cross + "/verification-checks"))).isIn(403, 404);
        assertThat(status(importLines(fx.payer(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "ZZ1", "1", "RWF", "SUCCESS", null)))).isEqualTo(403);
    }

    @Test
    void aTransactionAlreadyUsedByAnotherPaymentConflictsAndTheDatabaseRefusesFakeConfirmations() throws Exception {
        Fx fx = hybrid();
        UUID p1 = submit(fx, "MTN_MOMO", "1500", "USED1", null);
        UUID p2 = submit(fx, "MTN_MOMO", "700", "USED2", null);
        importLines(fx.accA(), fx, line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "USED1", "1500", "RWF", "SUCCESS", null));
        assertThat(current(fx, p1).path("outcome").asText()).isEqualTo("CONFIRMED");
        // simulate a transaction consumed by a different payment
        jdbc.update("update trusted_transactions set consumed_by_payment_id = ?, consumed_at = now() where cooperative_id = ? and normalized_reference = 'USED1'", p2, fx.scheme());
        assertThat(data(postAs(fx.accA(), fx, "/payments/" + p1 + "/verification-checks/recheck", null)).path("reasons").toString())
                .contains("TRANSACTION_ALREADY_USED");

        // OCR or a reference lookalike can never be stored as a CONFIRMED outcome
        assertThatThrownBy(() -> jdbc.update(
                        "insert into payment_verification_checks (id, cooperative_id, payment_id, method, outcome, source, created_at)"
                                + " values (?, ?, ?, 'MOBILE_MONEY_RECONCILED', 'CONFIRMED', 'RECEIPT_OCR', now())",
                        UUID.randomUUID(), fx.scheme(), p1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into payment_verification_checks (id, cooperative_id, payment_id, method, outcome, source, created_at)"
                                + " values (?, ?, ?, 'MOBILE_MONEY_RECONCILED', 'CONFIRMED', 'REFERENCE_MATCH', now())",
                        UUID.randomUUID(), fx.scheme(), p1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aReceiptThatNamesAnotherRecipientConflictsWithTheTrustedRecordButNeverConfirms() throws Exception {
        Fx fx = hybrid();
        String ref = ref();
        UUID payment = submit(fx, "MTN_MOMO", "1500", ref, upload(fx));
        ScriptedOcrEngine.script(receiptText(ref, "1,500", "0788123456"), 0.95);
        postAs(fx.accA(), fx, "/payments/" + payment + "/receipt/extract", null);

        JsonNode item = line("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", ref, "1500", "RWF", "SUCCESS", "+250788999999");
        importLines(fx.accA(), fx, item);

        JsonNode check = current(fx, payment);
        assertThat(check.path("outcome").asText()).isEqualTo("CONFLICTED");
        assertThat(check.path("reasons").toString()).contains("WRONG_RECIPIENT");
        assertThat(check.path("authoritative").asBoolean()).isTrue(); // the trusted record is what conflicts
        assertThat(count("select count(*) from payment_verification_checks where payment_id = ? and outcome = 'CONFIRMED'", payment)).isZero();
    }

    @Test
    void cashIsConfirmedOnlyByAnAuthorizedOfficerWhoIsNotThePayerWithAnAuditTrail() throws Exception {
        requireTrusted(true);
        Fx fx = hybrid();
        UUID cash = submit(fx, "CASH", "1000", null, null);
        assertThat(data(postAs(fx.accA(), fx, "/payments/" + cash + "/verification-checks/recheck", null)).path("outcome").asText()).isEqualTo("PENDING");
        assertThat(status(payments(fx.accC(), fx, cash, "/start-review", null))).isEqualTo(200);
        assertThat(code(payments(fx.accB(), fx, cash, "/verify", cashEvidence()))).isEqualTo("TRUSTED_CONFIRMATION_REQUIRED");

        assertThat(status(postAs(fx.payer(), fx, "/payments/" + cash + "/verification-checks/cash-confirmation", "{\"note\":\"I paid\"}"))).isEqualTo(403);
        assertThat(status(postAs(fx.accB(), fx, "/payments/" + cash + "/verification-checks/cash-confirmation", "{\"note\":\"\"}"))).isEqualTo(400);
        long audits = count("select count(*) from audit_logs where action = 'CASH_RECEIPT_CONFIRMED' and entity_id = ?", cash);
        JsonNode confirmed = data(postAs(fx.accB(), fx, "/payments/" + cash + "/verification-checks/cash-confirmation", "{\"note\":\"Counted 1000 in the office\"}"));
        assertThat(confirmed.path("outcome").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("source").asText()).isEqualTo("MANUAL_CASH_CONFIRMATION");
        assertThat(confirmed.path("method").asText()).isEqualTo("CASH_RECEIPT_CONFIRMED");
        assertThat(confirmed.path("checkedBy").asText()).isEqualTo(fx.accB().id().toString());
        assertThat(count("select count(*) from audit_logs where action = 'CASH_RECEIPT_CONFIRMED' and entity_id = ?", cash)).isEqualTo(audits + 1);
        JsonNode repeat = data(postAs(fx.accB(), fx, "/payments/" + cash + "/verification-checks/cash-confirmation", "{\"note\":\"again\"}"));
        assertThat(repeat.path("id").asText()).as("confirming twice records nothing new").isEqualTo(confirmed.path("id").asText());

        assertThat(status(payments(fx.accB(), fx, cash, "/verify", cashEvidence()))).isEqualTo(200);
        assertThat(paymentStatus(cash)).isEqualTo("VERIFIED");
        // an electronic payment cannot be confirmed as cash
        UUID momo = submit(fx, "MTN_MOMO", "100", ref(), null);
        MvcResult notCash = postAs(fx.accB(), fx, "/payments/" + momo + "/verification-checks/cash-confirmation", "{\"note\":\"x\"}");
        assertThat(code(notCash)).isEqualTo("CASH_CONFIRMATION_NOT_APPLICABLE");
    }

    @Test
    void aSignedCallbackIsIdempotentRefusedWithoutASecretOrASignatureAndNeverRecordedByAPerson() throws Exception {
        Fx fx = hybrid();
        UUID payment = submit(fx, "MTN_MOMO", "1500", "CALLBACK1", null);
        String body = "{\"cooperativeId\":\"%s\",\"eventId\":\"evt-1\",\"reference\":\"CALLBACK1\",\"amount\":1500,\"currency\":\"RWF\",\"status\":\"SUCCESS\"}"
                .formatted(fx.scheme());

        // no secret configured: automatic confirmation is unavailable and everything is refused
        MvcResult noSecret = callback("MTN_MOMO", body, SIGNED);
        assertThat(status(noSecret)).isEqualTo(503);
        assertThat(objectMapper.readTree(noSecret.getResponse().getContentAsString()).path("code").asText()).isEqualTo("PROVIDER_NOT_CONFIGURED");
        assertThat(data(getAs(fx.accA(), fx, "/payments/" + payment + "/verification-checks")).path("automaticProviderConfirmation").asText()).isEqualTo("UNAVAILABLE");

        callbackProperties.setSecrets(Map.of("MTN_MOMO", SECRET));
        assertThat(status(callback("MTN_MOMO", body, "deadbeef"))).isEqualTo(401);
        assertThat(status(callback("MTN_MOMO", body, null))).isEqualTo(401);
        assertThat(status(callback("CASH", body, SIGNED))).isEqualTo(404);
        assertThat(count("select count(*) from trusted_transactions where cooperative_id = ?", fx.scheme())).isZero();

        MvcResult ok = callback("MTN_MOMO", body, SIGNED);
        assertThat(status(ok)).isEqualTo(200);
        assertThat(objectMapper.readTree(ok.getResponse().getContentAsString()).path("code").asText()).isEqualTo("CREATED");
        JsonNode check = current(fx, payment);
        assertThat(check.path("outcome").asText()).isEqualTo("CONFIRMED");
        assertThat(check.path("method").asText()).isEqualTo("PROVIDER_CONFIRMED");
        assertThat(jdbc.queryForObject("select recorded_by from trusted_transactions where cooperative_id = ?", UUID.class, fx.scheme()))
                .as("a callback is recorded by the system, never impersonating an officer").isNull();
        assertThat(paymentStatus(payment)).as("provider confirmation never moves the payment").isEqualTo("SUBMITTED");

        // redelivery: the same event is a duplicate, one transaction
        MvcResult replay = callback("MTN_MOMO", body, SIGNED);
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).path("code").asText()).isEqualTo("DUPLICATE");
        assertThat(count("select count(*) from trusted_transactions where cooperative_id = ?", fx.scheme())).isEqualTo(1);

        // a validly signed but malformed body is rejected
        String bad = "{\"cooperativeId\":\"%s\"}".formatted(fx.scheme());
        assertThat(status(callback("MTN_MOMO", bad, SIGNED))).isEqualTo(400);
        assertThat(status(callback("MTN_MOMO", "{\"cooperativeId\":\"%s\",\"eventId\":\"e\",\"reference\":\"R1234567\",\"amount\":5,\"currency\":\"RWF\",\"status\":\"SUCCESS\"}".formatted(UUID.randomUUID()), null))).isEqualTo(401);
    }

    @Test
    void anImportBatchReportsEachLineAndRejectsInvalidOnesWithoutStoppingTheRest() throws Exception {
        Fx fx = hybrid();
        List<Map<String, Object>> items = List.of(
                map("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "BATCH1", "100", "RWF", "SUCCESS"),
                map("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "BATCH1", "100", "RWF", "SUCCESS"),
                map("MOBILE_MONEY_STATEMENT_IMPORT", "MTN_MOMO", "BATCH2", "0", "RWF", "SUCCESS"),
                map("BANK_STATEMENT_IMPORT", "MTN_MOMO", "BATCH3", "100", "RWF", "SUCCESS"),
                map("MOBILE_MONEY_STATEMENT_IMPORT", "CASH", "BATCH4", "100", "RWF", "SUCCESS"));
        MvcResult result = mockMvc.perform(post(base(fx) + "/payments/trusted-transactions")
                        .header("Authorization", bearer(fx.accA().token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", items))))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        JsonNode results = data(result).path("results");
        assertThat(results.get(0).path("result").asText()).isEqualTo("CREATED");
        assertThat(results.get(1).path("result").asText()).isEqualTo("DUPLICATE");
        assertThat(results.get(2).path("result").asText()).isEqualTo("INVALID");
        assertThat(results.get(2).path("reason").asText()).isEqualTo("INVALID_AMOUNT");
        assertThat(results.get(3).path("reason").asText()).isEqualTo("SOURCE_CHANNEL_MISMATCH");
        assertThat(results.get(4).path("reason").asText()).isEqualTo("INVALID_CHANNEL");
        assertThat(count("select count(*) from trusted_transactions where cooperative_id = ?", fx.scheme())).isEqualTo(1);
        assertThat(data(getAs(fx.accA(), fx, "/payments/trusted-transactions"))).hasSize(1);
        assertThat(status(getAs(fx.payer(), fx, "/payments/trusted-transactions"))).isEqualTo(403);
        assertThat(data(getAs(fx.accA(), fx, "/payments/reconciliation/attention")).isArray()).isTrue();
    }

    // ============================================================ legacy rotating reconciliation

    @Test
    void reconcilingALegacyContributionAddsAVerifiedAmountOnceWithoutTouchingTheLedger() throws Exception {
        Fx fx = hybrid();
        UUID period = periodOf(fx, NEXT);
        assertThat(status(saveLine(fx, NEXT, "1000"))).isEqualTo(200);
        long ledger = count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme());
        BigDecimal cashBefore = availableFunds(fx);
        UUID contribution = contributionId(fx, NEXT);

        JsonNode candidates = data(getAs(fx.accA(), fx, "/rotation/reconciliation/candidates"));
        assertThat(candidates).hasSize(1);
        assertThat(dec(candidates.get(0), "unreconciledAmount")).isEqualByComparingTo("1000");
        assertThat(dec(candidates.get(0), "unreconciledRotating")).isEqualByComparingTo("500");
        assertThat(candidates.get(0).path("originalRecipientUserId").asText()).isEqualTo(fx.r1().id().toString());
        var before = shareValuationService.calculate(fx.scheme());
        assertThat(before.getUnreconciledRotatingFunds()).isEqualByComparingTo("500");
        assertThat(before.getVerifiedRotatingLiabilities()).isEqualByComparingTo("0");

        // evidence is required; the payer and unauthorized roles cannot request
        assertThat(code(requestReconciliation(fx.accA(), fx, contribution, "1000", "BANK_RECONCILED", "", "STMT-77"))).isEqualTo("RECONCILIATION_BASIS_REQUIRED");
        assertThat(code(requestReconciliation(fx.accA(), fx, contribution, "1000", "BANK_RECONCILED", "Statement line seen", null))).isEqualTo("RECONCILIATION_REFERENCE_REQUIRED");
        assertThat(status(requestReconciliation(fx.payer(), fx, contribution, "1000", "CASH_RECEIPT_CONFIRMED", "I paid", null))).isEqualTo(403);
        assertThat(code(requestReconciliation(fx.accA(), fx, contribution, "1500", "CASH_RECEIPT_CONFIRMED", "Counted", null))).isEqualTo("RECONCILIATION_AMOUNT_EXCEEDS_UNVERIFIED");

        MvcResult requested = requestReconciliation(fx.accA(), fx, contribution, "1000", "BANK_RECONCILED", "Statement line 77 seen", "STMT-77");
        assertThat(status(requested)).as(requested.getResponse().getContentAsString()).isEqualTo(200);
        UUID id = UUID.fromString(data(requested).path("id").asText());
        assertThat(data(requested).path("status").asText()).isEqualTo("REQUESTED");
        assertThat(data(requested).path("originalRecipientUserId").asText()).as("copied from the obligation, not supplied").isEqualTo(fx.r1().id().toString());
        assertThat(code(requestReconciliation(fx.accB(), fx, contribution, "100", "CASH_RECEIPT_CONFIRMED", "Counted", null))).isEqualTo("RECONCILIATION_ALREADY_OPEN");

        // requested is not yet verified: nothing changed for payouts or valuation
        assertThat(dec(entitlement(fx, period), "collectedRotating")).isEqualByComparingTo("0");

        // independence: the requester, an officer without fund authority, and a foreign scheme cannot decide
        assertThat(status(reconAction(fx.accA(), fx, id, "/approve", null))).isEqualTo(403);
        assertThat(status(reconAction(fx.accB(), fx, id, "/approve", null))).isEqualTo(403);
        assertThat(status(reconAction(fx.payer(), fx, id, "/approve", null))).isEqualTo(403);
        Fx other = hybrid();
        assertThat(status(mockMvc.perform(post(base(fx) + "/rotation/reconciliation/requests/" + id + "/approve")
                .header("Authorization", bearer(other.president().token()))).andReturn())).isIn(403, 404);
        assertThat(status(mockMvc.perform(post(base(other) + "/rotation/reconciliation/requests/" + id + "/approve")
                .header("Authorization", bearer(other.president().token()))).andReturn())).isEqualTo(404);

        MvcResult approved = reconAction(fx.president(), fx, id, "/approve", "{\"note\":\"Matches the statement\"}");
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(data(approved).path("status").asText()).isEqualTo("APPROVED");
        // approving again is a safe no-op: the verified amount is not added twice
        assertThat(status(reconAction(fx.president(), fx, id, "/approve", null))).isEqualTo(200);

        // no duplicate ledger credit, original record untouched, balance unchanged
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", fx.scheme())).isEqualTo(ledger);
        assertThat(availableFunds(fx)).isEqualByComparingTo(cashBefore);
        assertThat(paid(fx, NEXT)).isEqualByComparingTo("1000");
        JsonNode e = entitlement(fx, period);
        assertThat(dec(e, "collectedRotating")).isEqualByComparingTo("500");
        assertThat(dec(e, "unreconciledRotating")).isEqualByComparingTo("0");
        assertThat(dec(e, "outstandingEntitlement")).isEqualByComparingTo("500");
        assertThat(data(getAs(fx.accA(), fx, "/rotation/reconciliation/candidates"))).isEmpty();

        // NAV: the money moved from "unreconciled" to "verified owed": same liability, never counted twice
        var after = shareValuationService.calculate(fx.scheme());
        assertThat(after.getVerifiedRotatingLiabilities()).isEqualByComparingTo("500");
        assertThat(after.getUnreconciledRotatingFunds()).isEqualByComparingTo("0");
        assertThat(after.getLiabilities()).isEqualByComparingTo(before.getLiabilities());
        assertThat(after.getTotalIkiminaValue()).isEqualByComparingTo(before.getTotalIkiminaValue());

        // now payable: a payout of the reconciled amount, and the legacy edit route cannot undercut it
        JsonNode payout = data(requestPayout(fx, fx.accA(), period));
        assertThat(dec(payout, "amount")).isEqualByComparingTo("500");
        UUID payoutId = UUID.fromString(payout.path("id").asText());
        assertThat(status(payoutAction(fx.president(), fx, payoutId, "/approve"))).isEqualTo(200);
        assertThat(status(payoutAction(fx.accB(), fx, payoutId, "/confirm-disbursement",
                "{\"method\":\"BANK_TRANSFER\",\"reference\":\"BK-RECON\",\"note\":\"Transferred\"}"))).isEqualTo(200);
        MvcResult lower = patchPaid(fx, NEXT, "100");
        assertThat(status(lower)).isEqualTo(422);
        assertThat(code(lower)).isEqualTo("ROTATION_DISTRIBUTION_CONSISTENCY");

        // immutable history
        JsonNode events = data(getAs(fx.accA(), fx, "/rotation/reconciliation/requests/" + id + "/events"));
        assertThat(events).hasSize(2);
        assertThat(events.get(0).path("toStatus").asText()).isEqualTo("REQUESTED");
        assertThat(events.get(1).path("toStatus").asText()).isEqualTo("APPROVED");
        assertThat(count("select count(*) from audit_logs where action = 'LEGACY_RECONCILIATION_APPROVE' and entity_id = ?", id)).isEqualTo(1);
    }

    @Test
    void duplicateReferencesDeclinesWithdrawalsAndUnboundObligationsAreHandledSafely() throws Exception {
        Fx fx = hybrid();
        row(fx, NEXT.plusMonths(1));
        assertThat(status(saveLine(fx, NEXT, "1000"))).isEqualTo(200);
        assertThat(status(saveLine(fx, NEXT.plusMonths(1), "1000"))).isEqualTo(200);
        UUID c1 = contributionId(fx, NEXT);
        UUID c2 = contributionId(fx, NEXT.plusMonths(1));

        UUID first = UUID.fromString(data(requestReconciliation(fx.accA(), fx, c1, "400", "BANK_RECONCILED", "Seen", "REF-DUP")).path("id").asText());
        // the same external reference cannot back a second live reconciliation (formatting differences included)
        MvcResult duplicate = requestReconciliation(fx.accA(), fx, c2, "400", "BANK_RECONCILED", "Seen again", "ref dup");
        assertThat(status(duplicate)).isEqualTo(409);
        assertThat(code(duplicate)).isEqualTo("RECONCILIATION_REFERENCE_REUSED");

        // only the requester can withdraw; the reference is then released
        assertThat(status(reconAction(fx.accB(), fx, first, "/withdraw", null))).isEqualTo(403);
        assertThat(status(reconAction(fx.accA(), fx, first, "/withdraw", "{\"note\":\"Wrong line\"}"))).isEqualTo(200);
        assertThat(status(reconAction(fx.president(), fx, first, "/approve", null))).as("a withdrawn request cannot be approved").isEqualTo(409);
        UUID second = UUID.fromString(data(requestReconciliation(fx.accA(), fx, c2, "400", "BANK_RECONCILED", "Seen again", "REF-DUP")).path("id").asText());

        // a decline needs a reason, releases the reference and is recorded
        assertThat(status(reconAction(fx.president(), fx, second, "/decline", "{\"note\":\"\"}"))).isEqualTo(422);
        assertThat(status(reconAction(fx.president(), fx, second, "/decline", "{\"note\":\"Statement does not show it\"}"))).isEqualTo(200);
        assertThat(status(requestReconciliation(fx.accA(), fx, c1, "400", "BANK_RECONCILED", "Corrected", "REF-DUP"))).isEqualTo(200);
        assertThat(data(getAs(fx.president(), fx, "/rotation/reconciliation/requests"))).hasSize(3);
        assertThat(dec(entitlement(fx, periodOf(fx, NEXT)), "collectedRotating")).as("only approvals verify money").isEqualByComparingTo("0");

        // an obligation without a bound period or recipient is never reconciled: nothing is invented
        assertThat(status(saveLine(fx, NOW.minusMonths(3), "300"))).isEqualTo(200);
        UUID unbound = contributionId(fx, NOW.minusMonths(3));
        MvcResult none = requestReconciliation(fx.accA(), fx, unbound, "300", "CASH_RECEIPT_CONFIRMED", "Counted", null);
        assertThat(status(none)).isEqualTo(422);
        assertThat(code(none)).isIn("RECONCILIATION_NO_ROTATION_BINDING", "RECONCILIATION_NOT_ELIGIBLE");

        // a cancelled period's obligations cannot be reconciled either
        jdbc.update("update rotation_periods set status = 'CANCELLED', slot_key = null where id = ?", periodOf(fx, NEXT.plusMonths(1)));
        assertThat(code(requestReconciliation(fx.accB(), fx, c2, "100", "CASH_RECEIPT_CONFIRMED", "Counted", null))).isEqualTo("RECONCILIATION_NOT_ELIGIBLE");

        // a super admin keeps the separation rules: requesting and approving the same record is refused
        Fx solo = hybrid();
        assertThat(status(saveLine(solo, NEXT, "600"))).isEqualTo(200);
        MvcResult own = requestReconciliation(solo, superAdminToken, solo, contributionId(solo, NEXT), "600", "CASH_RECEIPT_CONFIRMED", "Counted", null);
        assertThat(status(own)).as(own.getResponse().getContentAsString()).isEqualTo(200);
        UUID ownId = UUID.fromString(data(own).path("id").asText());
        assertThat(status(mockMvc.perform(post(base(solo) + "/rotation/reconciliation/requests/" + ownId + "/approve")
                .header("Authorization", bearer(superAdminToken))).andReturn())).isEqualTo(403);
        assertThat(count("select count(*) from legacy_rotating_reconciliations where cooperative_id = ? and status = 'APPROVED'", solo.scheme())).isZero();
    }

    // ============================================================ NAV reconciliation across operating models

    @Test
    void anAccumulatingSchemeHasNoRotatingLiabilitiesAndItsRetainedCapitalIsUnchanged() throws Exception {
        Fx fx = fx("ACCUMULATING", "MEMBER", "MEMBER");
        assertThat(status(saveLine(fx, NOW, "1000"))).isEqualTo(200);

        var v = shareValuationService.calculate(fx.scheme());
        assertThat(v.getLiabilities()).isEqualByComparingTo("0");
        assertThat(v.getVerifiedRotatingLiabilities()).isEqualByComparingTo("0");
        assertThat(v.getUnreconciledRotatingFunds()).isEqualByComparingTo("0");
        assertThat(v.getTotalIkiminaValue()).isEqualByComparingTo(v.getAvailableFunds());

        JsonNode summary = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(summary, "postedCollections")).isEqualByComparingTo("1000");
        assertThat(dec(summary, "retainedCollections")).isEqualByComparingTo("1000");
        assertThat(dec(summary, "unclassifiedCollections")).isEqualByComparingTo("0");
        assertNavCategories(summary);
        assertThat(data(getAs(fx.accA(), fx, "/rotation/reconciliation/candidates"))).isEmpty();
    }

    @Test
    void aRotatingSchemeHoldsAllUnreconciledMoneyForItsRecipientAndMovesItToVerifiedOnApproval() throws Exception {
        Fx fx = rotationFx("ROTATING", 10000, 0);
        assertThat(status(saveLine(fx, NEXT, "1000"))).isEqualTo(200);

        var held = shareValuationService.calculate(fx.scheme());
        assertThat(held.getUnreconciledRotatingFunds()).isEqualByComparingTo("1000");
        assertThat(held.getVerifiedRotatingLiabilities()).isEqualByComparingTo("0");
        assertThat(held.getTotalIkiminaValue()).as("none of it is members' capital").isEqualByComparingTo(held.getAvailableFunds().subtract(new BigDecimal("1000")));
        JsonNode before = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(before, "retainedCollections")).isEqualByComparingTo("0");
        assertThat(dec(before, "rotatingRequiringReconciliation")).isEqualByComparingTo("1000");
        assertNavCategories(before);
        UUID period = periodOf(fx, NEXT);
        MvcResult cannotPay = requestPayout(fx, fx.accA(), period);
        assertThat(code(cannotPay)).as("unreconciled money is payout-ineligible").isEqualTo("NO_ENTITLEMENT");

        UUID id = UUID.fromString(data(requestReconciliation(fx.accA(), fx, contributionId(fx, NEXT), "1000", "CASH_RECEIPT_CONFIRMED", "Counted at the meeting", null)).path("id").asText());
        assertThat(status(reconAction(fx.president(), fx, id, "/approve", null))).isEqualTo(200);

        var settled = shareValuationService.calculate(fx.scheme());
        assertThat(settled.getVerifiedRotatingLiabilities()).isEqualByComparingTo("1000");
        assertThat(settled.getUnreconciledRotatingFunds()).isEqualByComparingTo("0");
        assertThat(settled.getTotalIkiminaValue()).isEqualByComparingTo(held.getTotalIkiminaValue());
        JsonNode after = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(after, "rotatingCollected")).isEqualByComparingTo("1000");
        assertThat(dec(after, "retainedCollections")).isEqualByComparingTo("0");
        assertThat(dec(after, "totalRotatingLiabilities")).isEqualByComparingTo("1000");
        assertNavCategories(after);
    }

    @Test
    void aHybridSchemeSplitsCollectionsIntoVerifiedUnreconciledAndRetainedWithoutDoubleCounting() throws Exception {
        Fx fx = hybrid();
        assertThat(status(saveLine(fx, NEXT, "1000"))).isEqualTo(200); // rotating 500, retained 500

        JsonNode legacy = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(legacy, "rotatingRequiringReconciliation")).isEqualByComparingTo("500");
        assertThat(dec(legacy, "retainedCollections")).isEqualByComparingTo("500");
        assertThat(dec(legacy, "totalRotatingLiabilities")).isEqualByComparingTo("500");
        assertNavCategories(legacy);

        // a partial reconciliation: 600 of the 1000 confirmed, i.e. rotating 300 verified and 200 still unreconciled
        UUID id = UUID.fromString(data(requestReconciliation(fx.accA(), fx, contributionId(fx, NEXT), "600", "MOBILE_MONEY_RECONCILED", "Statement seen", "STMT-600")).path("id").asText());
        assertThat(status(reconAction(fx.president(), fx, id, "/approve", null))).isEqualTo(200);
        var v = shareValuationService.calculate(fx.scheme());
        assertThat(v.getVerifiedRotatingLiabilities()).isEqualByComparingTo("300");
        assertThat(v.getUnreconciledRotatingFunds()).isEqualByComparingTo("200");
        assertThat(v.getLiabilities()).as("verified + unreconciled, once").isEqualByComparingTo("500");
        JsonNode partial = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(partial, "retainedCollections")).isEqualByComparingTo("500");
        assertNavCategories(partial);

        // the verified part is payable, the unreconciled part is not
        JsonNode payout = data(requestPayout(fx, fx.accA(), periodOf(fx, NEXT)));
        assertThat(dec(payout, "amount")).isEqualByComparingTo("300");
    }

    @Test
    void historyThatCannotBeClassifiedIsReportedExplicitlyAndNeverGuessedIntoRetainedOrRotatingMoney() throws Exception {
        Fx fx = hybrid();
        assertThat(status(saveLine(fx, NEXT, "1000"))).isEqualTo(200);
        // an old obligation recorded before any model snapshot existed
        jdbc.update("update contributions set operating_model_snapshot = null where cooperative_id = ?", fx.scheme());

        JsonNode summary = data(getAs(fx.accA(), fx, "/reports/model-summary"));
        assertThat(dec(summary, "unclassifiedCollections")).isEqualByComparingTo("1000");
        assertThat(dec(summary, "retainedCollections")).isEqualByComparingTo("0");
        assertThat(dec(summary, "rotatingRequiringReconciliation")).isEqualByComparingTo("0");
        assertThat(dec(summary, "totalRotatingLiabilities")).isEqualByComparingTo("0");
        assertNavCategories(summary);
        // and, having no snapshot, it cannot be reconciled as rotating money
        assertThat(data(getAs(fx.accA(), fx, "/rotation/reconciliation/candidates"))).isEmpty();
    }

    // ============================================================ helpers

    /** Every collected rupee is in exactly one category, and what is set aside is the sum of its two parts. */
    private void assertNavCategories(JsonNode s) {
        BigDecimal categories = dec(s, "retainedCollections")
                .add(dec(s, "unclassifiedCollections"))
                .add(dec(s, "rotatingCollected"))
                .add(dec(s, "rotatingRequiringReconciliation"));
        assertThat(categories).as("categories add up to what was collected").isEqualByComparingTo(dec(s, "postedCollections"));
        assertThat(dec(s, "totalRotatingLiabilities"))
                .isEqualByComparingTo(dec(s, "recipientEntitlementsOwed").add(dec(s, "rotatingRequiringReconciliation")));
        assertThat(dec(s, "availableAfterEntitlements"))
                .isEqualByComparingTo(dec(s, "availableFunds").subtract(dec(s, "totalRotatingLiabilities")));
    }

    private void requireTrusted(boolean value) {
        Object target = AopTestUtils.getUltimateTargetObject(checkService);
        ReflectionTestUtils.setField(target, "requireTrustedConfirmation", value);
    }

    private static String receiptText(String reference, String amount, String recipient) {
        return "MTN MoMo\nTransaction successful\nFinancial Transaction Id: " + reference + "\nAmount: RWF " + amount
                + "\nTo: " + recipient + "\nFrom: 0781234567\nDate: " + LocalDate.now(KIGALI) + " 10:15:00\n";
    }

    private String ref() {
        return "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private byte[] png(int seed) {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, (byte) (seed & 0xFF), (byte) (seed >> 8), (byte) ++salt};
    }

    private String upload(Fx fx) throws Exception {
        return upload(fx, png(salt + 1000));
    }

    private String upload(Fx fx, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "receipt.png", "image/png", bytes);
        MvcResult result = mockMvc.perform(multipart("/api/v1/cooperatives/" + fx.scheme() + "/files")
                        .file(file)
                        .param("category", "CONTRIBUTION_EVIDENCE")
                        .header("Authorization", bearer(fx.payer().token())))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        return data(result).path("storageKey").asText();
    }

    private UUID submit(Fx fx, String method, String amount, String reference, String evidenceKey) throws Exception {
        return submit(fx, method, amount, reference, evidenceKey, false);
    }

    private UUID submit(Fx fx, String method, String amount, String reference, String evidenceKey, boolean manualReview)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (manualReview) {
            body.put("manualReview", true);
        }
        body.put("amount", new BigDecimal(amount));
        body.put("paymentDate", LocalDate.now(KIGALI).toString());
        body.put("paymentMethod", method);
        if (reference != null) {
            body.put("transactionReference", reference);
        }
        if (evidenceKey != null) {
            body.put("evidenceFileKey", evidenceKey);
        }
        body.put("allocations", List.of(Map.of(
                "type", "CONTRIBUTION", "amount", new BigDecimal(amount),
                "periodYear", NEXT.getYear(), "periodMonth", NEXT.getMonthValue())));
        MvcResult result = mockMvc.perform(post(base(fx) + "/payments")
                        .header("Authorization", bearer(fx.payer().token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(data(result).path("id").asText());
    }

    private static String evidence(String method, String reference) {
        return "{\"method\":\"" + method + "\",\"basis\":\"Matched against the trusted record\",\"reference\":\"" + reference + "\"}";
    }

    private static String cashEvidence() {
        return "{\"method\":\"CASH_RECEIPT_CONFIRMED\",\"basis\":\"Cash received and counted\"}";
    }

    private JsonNode line(String source, String channel, String reference, String amount, String currency, String status, String recipient) {
        Map<String, Object> m = map(source, channel, reference, amount, currency, status);
        if (recipient != null) {
            m.put("recipient", recipient);
        }
        return objectMapper.valueToTree(m);
    }

    private static Map<String, Object> map(String source, String channel, String reference, String amount, String currency, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("channel", channel);
        m.put("reference", reference);
        m.put("amount", new BigDecimal(amount));
        m.put("currency", currency);
        m.put("status", status);
        return m;
    }

    private MvcResult importLines(Actor who, Fx fx, JsonNode... lines) throws Exception {
        return mockMvc.perform(post(base(fx) + "/payments/trusted-transactions")
                        .header("Authorization", bearer(who.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(lines)))))
                .andReturn();
    }

    private JsonNode current(Fx fx, UUID payment) throws Exception {
        MvcResult result = getAs(fx.accA(), fx, "/payments/" + payment + "/verification-checks");
        assertThat(status(result)).isEqualTo(200);
        return data(result).path("current");
    }

    /** Asks {@link #callback} to sign the request correctly with the current time. */
    private static final String SIGNED = "<signed>";

    private MvcResult callback(String channel, String body, String signature) throws Exception {
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        var request = post("/api/v1/public/payment-callbacks/" + channel)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Timestamp", timestamp)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            request.header("X-Signature", SIGNED.equals(signature) ? sign(timestamp, body) : signature);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String sign(String timestamp, String body) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private MvcResult requestReconciliation(
            Actor who, Fx fx, UUID contribution, String amount, String method, String basis, String reference) throws Exception {
        return requestReconciliation(fx, who.token(), fx, contribution, amount, method, basis, reference);
    }

    private MvcResult requestReconciliation(
            Fx scope, String token, Fx fx, UUID contribution, String amount, String method, String basis, String reference) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contributionId", contribution);
        body.put("amount", new BigDecimal(amount));
        body.put("method", method);
        body.put("basis", basis);
        if (reference != null) {
            body.put("reference", reference);
        }
        return mockMvc.perform(post(base(scope) + "/rotation/reconciliation/requests")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult reconAction(Actor who, Fx fx, UUID id, String suffix, String json) throws Exception {
        var request = post(base(fx) + "/rotation/reconciliation/requests/" + id + suffix).header("Authorization", bearer(who.token()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult requestPayout(Fx fx, Actor who, UUID period) throws Exception {
        return mockMvc.perform(post(base(fx) + "/rotation/payouts")
                        .header("Authorization", bearer(who.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rotationPeriodId\":\"" + period + "\"}"))
                .andReturn();
    }

    private MvcResult payoutAction(Actor who, Fx fx, UUID id, String suffix) throws Exception {
        return mockMvc.perform(post(base(fx) + "/rotation/payouts/" + id + suffix).header("Authorization", bearer(who.token()))).andReturn();
    }

    private MvcResult payoutAction(Actor who, Fx fx, UUID id, String suffix, String json) throws Exception {
        return mockMvc.perform(post(base(fx) + "/rotation/payouts/" + id + suffix)
                        .header("Authorization", bearer(who.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult payments(Actor who, Fx fx, UUID id, String suffix, String json) throws Exception {
        var request = post(base(fx) + "/payments/" + id + suffix).header("Authorization", bearer(who.token()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult postAs(Actor who, Fx fx, String suffix, String json) throws Exception {
        var request = post(base(fx) + suffix).header("Authorization", bearer(who.token()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult getAs(Actor who, Fx fx, String suffix) throws Exception {
        return mockMvc.perform(get(base(fx) + suffix).header("Authorization", bearer(who.token()))).andReturn();
    }

    private JsonNode entitlement(Fx fx, UUID period) throws Exception {
        for (JsonNode node : data(getAs(fx.accA(), fx, "/rotation/entitlements"))) {
            if (node.path("periodId").asText().equals(period.toString())) {
                return node;
            }
        }
        throw new AssertionError("No entitlement row for " + period);
    }

    private static JsonNode field(JsonNode extraction, String name) {
        for (JsonNode f : extraction.path("fields")) {
            if (f.path("name").asText().equals(name)) {
                return f;
            }
        }
        throw new AssertionError("No field " + name + " in " + extraction);
    }

    private String paymentStatus(UUID payment) {
        return jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment);
    }

    private UUID contributionId(Fx fx, YearMonth month) {
        return jdbc.queryForObject(
                "select id from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                UUID.class, fx.scheme(), fx.payer().id(), month.getYear(), month.getMonthValue());
    }

    private BigDecimal paid(Fx fx, YearMonth month) {
        return jdbc.queryForObject("select paid_amount from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, fx.scheme(), fx.payer().id(), month.getYear(), month.getMonthValue());
    }

    private BigDecimal availableFunds(Fx fx) {
        return jdbc.queryForObject("select coalesce(sum(credit_amount), 0) - coalesce(sum(debit_amount), 0) from financial_ledger"
                + " where cooperative_id = ? and status in ('APPROVED')", BigDecimal.class, fx.scheme());
    }

    private MvcResult patchPaid(Fx fx, YearMonth month, String paid) throws Exception {
        return mockMvc.perform(patch(base(fx) + "/contributions/" + contributionId(fx, month))
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidAmount\":" + paid + "}"))
                .andReturn();
    }

    private UUID periodOf(Fx fx, YearMonth month) {
        return jdbc.queryForObject(
                "select id from rotation_periods where cooperative_id = ? and period_year = ? and period_month = ?",
                UUID.class, fx.scheme(), month.getYear(), month.getMonthValue());
    }

    // ----- scheme fixture

    private Fx hybrid() throws Exception {
        return rotationFx("HYBRID", 5000, 5000);
    }

    private Fx rotationFx(String model, int rotating, int retained) throws Exception {
        Fx fx = fx(model, "MEMBER", "MEMBER");
        MvcResult configured = mockMvc.perform(post("/api/v1/cooperatives/" + fx.scheme() + "/rotation/configurations")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveYear", NEXT.getYear(), "effectiveMonth", NEXT.getMonthValue(),
                                "rotatingBps", rotating, "retainedBps", retained))))
                .andReturn();
        assertThat(status(configured)).isEqualTo(200);
        MvcResult cycle = mockMvc.perform(post("/api/v1/cooperatives/" + fx.scheme() + "/rotation/cycles")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startYear", NEXT.getYear(), "startMonth", NEXT.getMonthValue(),
                                "recipientUserIds", List.of(fx.r1().id(), fx.r2().id())))))
                .andReturn();
        assertThat(status(cycle)).isEqualTo(200);
        String cycleId = objectMapper.readTree(cycle.getResponse().getContentAsString()).path("data").path("id").asText();
        assertThat(status(mockMvc.perform(post("/api/v1/cooperatives/" + fx.scheme() + "/rotation/cycles/" + cycleId + "/activate")
                .header("Authorization", bearer(superAdminToken))).andReturn())).isEqualTo(200);
        row(fx, NEXT);
        return fx;
    }

    private Fx fx(String model, String r1Role, String r2Role) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "P3 " + UUID.randomUUID().toString().substring(0, 8), "1000.0000", 1, model)))
                .andReturn();
        assertThat(status(created)).isEqualTo(200);
        UUID scheme = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText());
        Actor payer = register(scheme, "MEMBER");
        jdbc.update("update cooperative_memberships set share_count = 2 where cooperative_id = ? and user_id = ?", scheme, payer.id());
        return new Fx(
                scheme,
                payer,
                register(scheme, r1Role),
                register(scheme, r2Role),
                register(scheme, "PRESIDENT"),
                register(scheme, "ACCOUNTANT"),
                register(scheme, "ACCOUNTANT"),
                register(scheme, "ACCOUNTANT"));
    }

    private Actor register(UUID scheme, String role) throws Exception {
        String username = "p3_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Phase","lastName":"Three","username":"%s","email":"%s@test.local","roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andReturn();
        assertThat(status(result)).as("register " + role).isEqualTo(200);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Actor(UUID.fromString(data.path("userId").asText()), login(username, data.path("temporaryPassword").asText()));
    }

    private void row(Fx fx, YearMonth month) throws Exception {
        assertThat(status(saveLine(fx, month, "0"))).isEqualTo(200);
    }

    private MvcResult saveLine(Fx fx, YearMonth month, String paid) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("memberUserId", fx.payer().id());
        line.put("paidAmount", new BigDecimal(paid));
        if (new BigDecimal(paid).signum() > 0) {
            line.put("paymentDate", LocalDate.now(KIGALI).toString());
        }
        return mockMvc.perform(put("/api/v1/cooperatives/" + fx.scheme() + "/contributions/period?year=" + month.getYear() + "&month=" + month.getMonthValue())
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", List.of(line)))))
                .andReturn();
    }

    private String base(Fx fx) {
        return "/api/v1/cooperatives/" + fx.scheme();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.path(field).decimalValue();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
}
