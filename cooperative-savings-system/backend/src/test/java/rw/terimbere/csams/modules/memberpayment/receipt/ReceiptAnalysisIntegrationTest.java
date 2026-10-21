package rw.terimbere.csams.modules.memberpayment.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;
import rw.terimbere.csams.testsupport.TestPdfs;

/**
 * The member uploads proof of payment BEFORE submitting: the receipt is read, classified and checked, a failed or pending
 * receipt stops the submission (server-side, whatever the client does), an unclear one needs an explicit manual-review
 * request, and in every case nothing is verified, confirmed or posted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceiptAnalysisIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final YearMonth NOW = YearMonth.now(KIGALI);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private SchemeFixtures fixtures;
    private Scheme s;
    private int salt;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
        s = fixtures.scheme("ACCUMULATING");
        ScriptedOcrEngine.clear();
        // two shares: the member owes 2,000 for the month, so several 1,000 payments can be submitted
        jdbc.update("update cooperative_memberships set share_count = 2 where cooperative_id = ? and user_id = ?",
                s.id(), s.memberA().id());
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("memberUserId", s.memberA().id());
        line.put("paidAmount", BigDecimal.ZERO);
        assertThat(status(mockMvc.perform(put(base() + "/contributions/period?year=" + NOW.getYear() + "&month=" + NOW.getMonthValue())
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", List.of(line)))))
                .andReturn())).isEqualTo(200);
    }

    @AfterEach
    void tearDown() {
        ScriptedOcrEngine.clear();
    }

    // ============================================================ the status of the receipt

    @Test
    void aSuccessfulReceiptLetsTheMemberContinueAndTheSubmittedPaymentWaitsForVerificationWithNothingPosted() throws Exception {
        String ref = ref();
        ScriptedOcrEngine.script(receipt("Transaction successful", ref, "1,000", "0788123456"), 0.95);
        String file = upload(s.memberA());

        JsonNode analysis = analyze(s.memberA(), file, null);
        assertThat(analysis.path("receiptStatus").asText()).isEqualTo("SUCCESS_INDICATED");
        assertThat(analysis.path("recommendedAction").asText()).isEqualTo("CONTINUE");
        assertThat(analysis.path("canSubmit").asBoolean()).isTrue();
        assertThat(analysis.path("manualReviewNeeded").asBoolean()).isFalse();
        assertThat(analysis.path("extractedAmount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(analysis.path("currency").asText()).isEqualTo("RWF");
        assertThat(analysis.path("extractedReference").asText()).isEqualTo(ref);
        assertThat(analysis.path("amountAmbiguous").asBoolean()).isFalse();
        assertThat(analysis.path("referenceAlreadyUsed").asBoolean()).isFalse();
        assertThat(analysis.path("apparentRecipient").asText()).as("masked").endsWith("456").contains("*");
        assertThat(analysis.has("rawText")).as("the receipt text is never exposed").isFalse();

        MvcResult submitted = submit(s.memberA(), file, "1000", ref, false);
        assertThat(status(submitted)).as(submitted.getResponse().getContentAsString()).isEqualTo(200);
        UUID payment = UUID.fromString(data(submitted).path("id").asText());
        assertThat(data(submitted).path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("select receipt_status from member_payments where id = ?", String.class, payment))
                .isEqualTo("SUCCESS_INDICATED");
        assertThat(jdbc.queryForObject("select receipt_analysis_id from member_payments where id = ?", UUID.class, payment)).isNotNull();
        assertThat(jdbc.queryForObject("select receipt_review_requested from member_payments where id = ?", Boolean.class, payment)).isFalse();

        // a receipt reading is no settlement: nothing verified, nothing posted, no balance moved
        assertNothingPosted(payment);
    }

    @Test
    void aFailedReceiptBlocksTheSubmissionEvenWhenTheClientSkipsTheAnalysisOrAsksForAReview() throws Exception {
        String ref = ref();
        ScriptedOcrEngine.script(receipt("Transaction failed", ref, "1,000", "0788123456"), 0.95);
        String failedFile = upload(s.memberA());

        JsonNode analysis = analyze(s.memberA(), failedFile, null);
        assertThat(analysis.path("receiptStatus").asText()).isEqualTo("FAILED_INDICATED");
        assertThat(analysis.path("recommendedAction").asText()).isEqualTo("REPLACE_RECEIPT");
        assertThat(analysis.path("canSubmit").asBoolean()).isFalse();

        // the server applies the rule itself: no analysis call is needed first, and a review request cannot override it
        MvcResult blocked = submit(s.memberA(), failedFile, "1000", ref, false);
        assertThat(status(blocked)).isEqualTo(422);
        assertThat(code(blocked)).isEqualTo("RECEIPT_INDICATES_FAILURE");
        MvcResult stillBlocked = submit(s.memberA(), failedFile, "1000", ref, true);
        assertThat(code(stillBlocked)).isEqualTo("RECEIPT_INDICATES_FAILURE");
        assertThat(count("select count(*) from member_payments where cooperative_id = ?", s.id())).isZero();
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isZero();

        // the rejected upload stays on record, and a replacement receipt is accepted
        assertThat(count("select count(*) from receipt_analyses where cooperative_id = ? and receipt_status = 'FAILED_INDICATED'", s.id())).isEqualTo(1);
        ScriptedOcrEngine.script(receipt("Transaction successful", ref(), "1,000", "0788123456"), 0.95);
        String replacement = upload(s.memberA());
        MvcResult accepted = submit(s.memberA(), replacement, "1000", extractedRef(replacement), false);
        assertThat(status(accepted)).as(accepted.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(count("select count(*) from receipt_analyses where cooperative_id = ?", s.id())).isEqualTo(2);
    }

    @Test
    void aPendingReceiptBlocksTheSubmission() throws Exception {
        ScriptedOcrEngine.script(receipt("Processing", ref(), "1,000", "0788123456"), 0.95);
        String file = upload(s.memberA());
        JsonNode analysis = analyze(s.memberA(), file, null);
        assertThat(analysis.path("receiptStatus").asText()).isEqualTo("PENDING_INDICATED");
        assertThat(analysis.path("recommendedAction").asText()).isEqualTo("WAIT_FOR_FINAL_RECEIPT");
        assertThat(analysis.path("canSubmit").asBoolean()).isFalse();

        MvcResult blocked = submit(s.memberA(), file, "1000", ref(), true);
        assertThat(status(blocked)).isEqualTo(422);
        assertThat(code(blocked)).isEqualTo("RECEIPT_INDICATES_PENDING");
        assertThat(count("select count(*) from member_payments where cooperative_id = ?", s.id())).isZero();
    }

    @Test
    void anUnknownReceiptIsNeverTreatedAsSuccessAndOnlyContinuesAsAnExplicitManualReview() throws Exception {
        String ref = ref();
        ScriptedOcrEngine.script("MTN MoMo\nFinancial Transaction Id: " + ref + "\nAmount: RWF 1,000\nTo: 0788123456\n", 0.9);
        String file = upload(s.memberA());

        JsonNode analysis = analyze(s.memberA(), file, null);
        assertThat(analysis.path("receiptStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(analysis.path("canSubmit").asBoolean()).isTrue();
        assertThat(analysis.path("manualReviewNeeded").asBoolean()).isTrue();
        assertThat(analysis.path("recommendedAction").asText()).isEqualTo("REPLACE_OR_REQUEST_REVIEW");
        assertThat(analysis.path("reviewReasons").toString()).contains("RECEIPT_UNCLEAR");

        MvcResult plain = submit(s.memberA(), file, "1000", ref, false);
        assertThat(status(plain)).isEqualTo(422);
        assertThat(code(plain)).isEqualTo("RECEIPT_NEEDS_REVIEW");
        assertThat(count("select count(*) from member_payments where cooperative_id = ?", s.id())).isZero();

        MvcResult review = submit(s.memberA(), file, "1000", ref, true);
        assertThat(status(review)).as(review.getResponse().getContentAsString()).isEqualTo(200);
        UUID payment = UUID.fromString(data(review).path("id").asText());
        assertThat(jdbc.queryForObject("select receipt_review_requested from member_payments where id = ?", Boolean.class, payment)).isTrue();
        assertThat(jdbc.queryForObject("select receipt_status from member_payments where id = ?", String.class, payment)).isEqualTo("UNKNOWN");
        assertThat(data(review).path("receiptReviewRequested").asBoolean()).as("visible to the member").isTrue();
        assertNothingPosted(payment);
    }

    @Test
    void aContradictoryReceiptNeedsAManualReview() throws Exception {
        ScriptedOcrEngine.script("Payment successful\nTransaction failed\nAmount: RWF 1,000\n", 0.9);
        String file = upload(s.memberA());
        JsonNode analysis = analyze(s.memberA(), file, null);
        assertThat(analysis.path("receiptStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(analysis.path("manualReviewNeeded").asBoolean()).isTrue();
        assertThat(code(submit(s.memberA(), file, "1000", ref(), false))).isEqualTo("RECEIPT_NEEDS_REVIEW");
    }

    @Test
    void anUnreadableReceiptNeedsAReviewAndADeploymentWithoutOcrIsNotBlocked() throws Exception {
        ScriptedOcrEngine.fail("OCR_FAILED");
        String unreadable = upload(s.memberA());
        JsonNode analysis = analyze(s.memberA(), unreadable, null);
        assertThat(analysis.path("reading").asText()).isEqualTo("FAILED");
        assertThat(analysis.path("manualReviewNeeded").asBoolean()).isTrue();
        assertThat(analysis.path("reviewReasons").toString()).contains("UNREADABLE");
        assertThat(code(submit(s.memberA(), unreadable, "1000", ref(), false))).isEqualTo("RECEIPT_NEEDS_REVIEW");

        // no OCR engine at all (the real one is not installed here): officers check the receipt by eye, as before
        ScriptedOcrEngine.clear();
        String plain = upload(s.memberA());
        JsonNode none = analyze(s.memberA(), plain, null);
        assertThat(none.path("reading").asText()).isEqualTo("UNAVAILABLE");
        assertThat(none.path("canSubmit").asBoolean()).isTrue();
        assertThat(none.path("manualReviewNeeded").asBoolean()).isFalse();
        MvcResult accepted = submit(s.memberA(), plain, "1000", ref(), false);
        assertThat(status(accepted)).isEqualTo(200);
        UUID payment = UUID.fromString(data(accepted).path("id").asText());
        assertThat(jdbc.queryForObject("select receipt_status from member_payments where id = ?", String.class, payment)).isNull();
    }

    // ============================================================ the amount and the claim

    @Test
    void thePaymentAmountIsChosenOverTheFeeAndTheBalance() throws Exception {
        String ref = ref();
        ScriptedOcrEngine.script("MTN MoMo\nTransaction successful\nFinancial Transaction Id: " + ref
                + "\nPayment: RWF 1,000\nFee: RWF 50\nBalance: RWF 350,000\nTo: 0788123456\n", 0.95);
        String file = upload(s.memberA());
        JsonNode analysis = analyze(s.memberA(), file, null);
        assertThat(analysis.path("extractedAmount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(analysis.path("amountAmbiguous").asBoolean()).isFalse();
        assertThat(analysis.path("recommendedAction").asText()).isEqualTo("CONTINUE");
    }

    @Test
    void competingAmountsAreFlaggedNothingIsPrefilledAndTheMemberMustAskForAReview() throws Exception {
        String ref = ref();
        ScriptedOcrEngine.script("Transaction successful\nFinancial Transaction Id: " + ref + "\nAmount: RWF 1,000\nAmount: RWF 900\n", 0.95);
        String file = upload(s.memberA());
        JsonNode analysis = analyze(s.memberA(), file, null);
        assertThat(analysis.path("amountAmbiguous").asBoolean()).isTrue();
        assertThat(analysis.path("extractedAmount").isNull()).isTrue();
        assertThat(analysis.path("amountCandidates")).hasSize(2);
        assertThat(analysis.path("reviewReasons").toString()).contains("AMOUNT_AMBIGUOUS");
        assertThat(code(submit(s.memberA(), file, "1000", ref, false))).isEqualTo("RECEIPT_NEEDS_REVIEW");
        MvcResult review = submit(s.memberA(), file, "1000", ref, true);
        assertThat(status(review)).isEqualTo(200);
        UUID payment = UUID.fromString(data(review).path("id").asText());
        assertThat(jdbc.queryForObject("select receipt_amount_ambiguous from member_payments where id = ?", Boolean.class, payment)).isTrue();
    }

    @Test
    void anAmountThatDiffersFromTheReceiptOrADifferentReferenceNeedsAReview() throws Exception {
        String ref = ref();
        ScriptedOcrEngine.script(receipt("Transaction successful", ref, "1,000", "0788123456"), 0.95);
        String file = upload(s.memberA());
        JsonNode withClaim = analyze(s.memberA(), file, new BigDecimal("900"));
        assertThat(withClaim.path("reviewReasons").toString()).contains("AMOUNT_MISMATCH");
        assertThat(code(submit(s.memberA(), file, "900", ref, false))).isEqualTo("RECEIPT_NEEDS_REVIEW");
        assertThat(code(submit(s.memberA(), file, "1000", "SOMEOTHERREF1", false))).isEqualTo("RECEIPT_NEEDS_REVIEW");
        assertThat(status(submit(s.memberA(), file, "1000", ref, false))).isEqualTo(200);
    }

    @Test
    void aReferenceAlreadyUsedInTheSchemeIsFlaggedAndTheSubmissionIsStillRefusedAsADuplicate() throws Exception {
        String ref = ref();
        ScriptedOcrEngine.script(receipt("Transaction successful", ref, "1,000", "0788123456"), 0.95);
        String first = upload(s.memberA());
        assertThat(status(submit(s.memberA(), first, "1000", ref, false))).isEqualTo(200);

        String second = upload(s.memberA());
        JsonNode analysis = analyze(s.memberA(), second, null);
        assertThat(analysis.path("referenceAlreadyUsed").asBoolean()).isTrue();
        MvcResult duplicate = submit(s.memberA(), second, "1000", ref, false);
        assertThat(status(duplicate)).isEqualTo(409);
    }

    // ============================================================ the recipient account

    @Test
    void theReceiptsRecipientIsComparedWithTheSchemesAccountsAndOnlyAWrongOneNeedsAReview() throws Exception {
        // no account configured: the recipient cannot be compared, which never blocks
        ScriptedOcrEngine.script(receipt("Transaction successful", ref(), "1,000", "0788123456"), 0.95);
        JsonNode unknown = analyze(s.memberA(), upload(s.memberA()), null);
        assertThat(unknown.path("recipientMatch").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.path("recommendedAction").asText()).isEqualTo("CONTINUE");

        assertThat(status(addAccount(s.president(), "MTN_MOMO", "0788000111", "Ikimina MoMo"))).isEqualTo(200);

        ScriptedOcrEngine.script(receipt("Transaction successful", ref(), "1,000", "0788123456"), 0.95);
        JsonNode wrong = analyze(s.memberA(), upload(s.memberA()), null);
        assertThat(wrong.path("recipientMatch").asText()).isEqualTo("MISMATCH");
        assertThat(wrong.path("manualReviewNeeded").asBoolean()).isTrue();
        assertThat(wrong.path("reviewReasons").toString()).contains("RECIPIENT_MISMATCH");

        ScriptedOcrEngine.script(receipt("Transaction successful", ref(), "1,000", "+250788000111"), 0.95);
        JsonNode right = analyze(s.memberA(), upload(s.memberA()), null);
        assertThat(right.path("recipientMatch").asText()).as("national and international spellings agree").isEqualTo("MATCH");
        assertThat(right.path("recommendedAction").asText()).isEqualTo("CONTINUE");

        // a receipt that names no recipient is not a mismatch
        ScriptedOcrEngine.script("Transaction successful\nFinancial Transaction Id: " + ref() + "\nAmount: RWF 1,000\n", 0.95);
        assertThat(analyze(s.memberA(), upload(s.memberA()), null).path("recipientMatch").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void accountsAreMaskedForMembersManagedByOfficersAndNeverVisibleAcrossSchemes() throws Exception {
        assertThat(status(addAccount(s.memberA(), "MTN_MOMO", "0788000222", "x"))).as("members cannot manage accounts").isEqualTo(403);
        MvcResult added = addAccount(s.president(), "MTN_MOMO", "0788000222", "Ikimina MoMo");
        assertThat(status(added)).isEqualTo(200);
        UUID accountId = UUID.fromString(data(added).path("id").asText());
        assertThat(status(addAccount(s.president(), "MTN_MOMO", "0788000222", "again"))).isEqualTo(409);

        JsonNode asMember = data(mockMvc.perform(get(base() + "/payments/accounts").header("Authorization", bearer(s.memberA()))).andReturn());
        assertThat(asMember.get(0).path("masked").asBoolean()).isTrue();
        assertThat(asMember.get(0).path("accountIdentifier").asText()).endsWith("222").contains("*").doesNotContain("0788000");
        JsonNode asPresident = data(mockMvc.perform(get(base() + "/payments/accounts").header("Authorization", bearer(s.president()))).andReturn());
        assertThat(asPresident.get(0).path("accountIdentifier").asText()).isEqualTo("0788000222");

        Scheme other = fixtures.scheme("ACCUMULATING");
        assertThat(status(mockMvc.perform(get("/api/v1/cooperatives/" + s.id() + "/payments/accounts")
                .header("Authorization", bearer(other.president()))).andReturn())).as("not a member of this scheme").isIn(403, 404);
        assertThat(status(mockMvc.perform(delete("/api/v1/cooperatives/" + other.id() + "/payments/accounts/" + accountId)
                .header("Authorization", bearer(other.president()))).andReturn())).as("another scheme's account").isEqualTo(404);

        assertThat(status(mockMvc.perform(delete(base() + "/payments/accounts/" + accountId)
                .header("Authorization", bearer(s.president()))).andReturn())).isEqualTo(200);
        assertThat(data(mockMvc.perform(get(base() + "/payments/accounts").header("Authorization", bearer(s.memberA()))).andReturn())).isEmpty();
    }

    // ============================================================ who may analyse what

    @Test
    void aMemberCanOnlyAnalyseTheirOwnUploadsInTheirOwnScheme() throws Exception {
        ScriptedOcrEngine.script(receipt("Transaction successful", ref(), "1,000", "0788123456"), 0.95);
        String file = upload(s.memberA());
        assertThat(status(analyzeRaw(s.memberB(), file, null))).as("someone else's upload").isEqualTo(403);

        Scheme other = fixtures.scheme("ACCUMULATING");
        MvcResult cross = analyzeRawIn(other.id(), other.memberA(), file);
        assertThat(status(cross)).as("another scheme").isIn(403, 404);
        assertThat(status(mockMvc.perform(post(base() + "/payments/receipt-analysis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileKey\":\"x\"}")).andReturn())).isEqualTo(401);
        assertThat(count("select count(*) from receipt_analyses where cooperative_id = ?", other.id())).isZero();
    }

    @Test
    void analysingTheSameReceiptTwiceReadsItOnce() throws Exception {
        ScriptedOcrEngine.script(receipt("Transaction successful", ref(), "1,000", "0788123456"), 0.95);
        String file = upload(s.memberA());
        JsonNode first = analyze(s.memberA(), file, null);
        ScriptedOcrEngine.script(receipt("Transaction failed", ref(), "1,000", "0788123456"), 0.95);
        JsonNode second = analyze(s.memberA(), file, null);
        assertThat(second.path("analysisId").asText()).isEqualTo(first.path("analysisId").asText());
        assertThat(second.path("receiptStatus").asText()).as("the stored reading, not a new one").isEqualTo("SUCCESS_INDICATED");
        assertThat(count("select count(*) from receipt_analyses where cooperative_id = ?", s.id())).isEqualTo(1);
    }

    // ============================================================ PDF receipts

    @Test
    void aPdfReceiptWithATextLayerIsReadWithoutAnyOcrEngine() throws Exception {
        String ref = ref();
        byte[] pdf = TestPdfs.withText(String.join("\n",
                "MTN MoMo",
                "Transaction successful",
                "Financial Transaction Id: " + ref,
                "Amount: RWF 1,000",
                "To: 0788123456",
                "Date: " + LocalDate.now(KIGALI) + " 10:15:00"));
        String file = uploadPdf(s.memberA(), pdf);
        JsonNode analysis = analyze(s.memberA(), file, null);
        assertThat(analysis.path("reading").asText()).isEqualTo("COMPLETED");
        assertThat(analysis.path("receiptStatus").asText()).isEqualTo("SUCCESS_INDICATED");
        assertThat(analysis.path("extractedAmount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(analysis.path("extractedReference").asText()).isEqualTo(ref);
        assertThat(analysis.path("recommendedAction").asText()).isEqualTo("CONTINUE");
        MvcResult submitted = submit(s.memberA(), file, "1000", ref, false);
        assertThat(status(submitted)).as(submitted.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from member_payments where cooperative_id = ?", String.class, s.id())).isEqualTo("SUBMITTED");
    }

    @Test
    void aFailedPdfReceiptBlocksTheSubmissionLikeAnImage() throws Exception {
        String file = uploadPdf(s.memberA(), TestPdfs.withText(String.join("\n",
                "Transaction failed", "Financial Transaction Id: " + ref(), "Amount: RWF 1,000")));
        assertThat(analyze(s.memberA(), file, null).path("recommendedAction").asText()).isEqualTo("REPLACE_RECEIPT");
        assertThat(code(submit(s.memberA(), file, "1000", ref(), true))).isEqualTo("RECEIPT_INDICATES_FAILURE");
        assertThat(count("select count(*) from member_payments where cooperative_id = ?", s.id())).isZero();
    }

    @Test
    void aPictureOnlyPdfGoesThroughOcrAndIsNotBlockedWhereThereIsNoEngine() throws Exception {
        String file = uploadPdf(s.memberA(), TestPdfs.imageOnly());
        ScriptedOcrEngine.script(receipt("Transaction successful", ref(), "1,000", "0788123456"), 0.9);
        JsonNode read = analyze(s.memberA(), file, null);
        assertThat(read.path("reading").asText()).isEqualTo("COMPLETED");
        assertThat(read.path("receiptStatus").asText()).isEqualTo("SUCCESS_INDICATED");

        // without an engine a picture-only PDF cannot be read: reported as such, and the member is not blocked
        ScriptedOcrEngine.clear();
        String second = uploadPdf(s.memberA(), TestPdfs.imageOnly());
        JsonNode none = analyze(s.memberA(), second, null);
        assertThat(none.path("reading").asText()).isEqualTo("UNAVAILABLE");
        assertThat(none.path("canSubmit").asBoolean()).isTrue();
        assertThat(none.path("manualReviewNeeded").asBoolean()).isFalse();
    }

    @Test
    void aBrokenPdfNeedsAManualReviewAndIsNeverTreatedAsClear() throws Exception {
        String file = uploadPdf(s.memberA(), "%PDF-1.4 not really a pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode analysis = analyze(s.memberA(), file, null);
        assertThat(analysis.path("reading").asText()).isEqualTo("FAILED");
        assertThat(analysis.path("manualReviewNeeded").asBoolean()).isTrue();
        assertThat(code(submit(s.memberA(), file, "1000", ref(), false))).isEqualTo("RECEIPT_NEEDS_REVIEW");
        assertThat(status(submit(s.memberA(), file, "1000", ref(), true))).isEqualTo(200);
    }

    // ============================================================ helpers

    private void assertNothingPosted(UUID payment) {
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("SUBMITTED");
        assertThat(count("select count(*) from payment_verification_checks where payment_id = ? and outcome = 'CONFIRMED'", payment)).isZero();
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isZero();
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(paid_amount),0) from contributions where cooperative_id = ?", BigDecimal.class, s.id()))
                .isEqualByComparingTo("0");
    }

    private String base() {
        return "/api/v1/cooperatives/" + s.id();
    }

    private static String bearer(Actor who) {
        return "Bearer " + who.token();
    }

    private static String receipt(String statusLine, String reference, String amount, String recipient) {
        return "MTN MoMo\n" + statusLine + "\nFinancial Transaction Id: " + reference + "\nAmount: RWF " + amount
                + "\nTo: " + recipient + "\nFrom: 0781234567\nDate: " + LocalDate.now(KIGALI) + " 10:15:00\n";
    }

    private String ref() {
        return "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** The reference the (scripted) receipt behind this stored file shows. */
    private String extractedRef(String fileKey) throws Exception {
        return data(analyzeRaw(s.memberA(), fileKey, null)).path("extractedReference").asText();
    }

    private byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, (byte) ++salt, (byte) (salt >> 8), (byte) 7};
    }

    private String upload(Actor who) throws Exception {
        MvcResult result = mockMvc.perform(multipart(base() + "/files")
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", png()))
                        .param("category", "CONTRIBUTION_EVIDENCE")
                        .header("Authorization", bearer(who)))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        return data(result).path("storageKey").asText();
    }

    private String uploadPdf(Actor who, byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart(base() + "/files")
                        .file(new MockMultipartFile("file", "receipt.pdf", "application/pdf", bytes))
                        .param("category", "CONTRIBUTION_EVIDENCE")
                        .header("Authorization", bearer(who)))
                .andReturn();
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return data(result).path("storageKey").asText();
    }

    private MvcResult analyzeRaw(Actor who, String fileKey, BigDecimal entered) throws Exception {
        return analyzeRawIn(s.id(), who, fileKey, entered);
    }

    private MvcResult analyzeRawIn(UUID scheme, Actor who, String fileKey) throws Exception {
        return analyzeRawIn(scheme, who, fileKey, null);
    }

    private MvcResult analyzeRawIn(UUID scheme, Actor who, String fileKey, BigDecimal entered) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileKey", fileKey);
        if (entered != null) {
            body.put("enteredAmount", entered);
        }
        return mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/payments/receipt-analysis")
                        .header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode analyze(Actor who, String fileKey, BigDecimal entered) throws Exception {
        MvcResult result = analyzeRaw(who, fileKey, entered);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return data(result);
    }

    private MvcResult submit(Actor who, String fileKey, String amount, String reference, boolean manualReview) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", new BigDecimal(amount));
        body.put("paymentDate", LocalDate.now(KIGALI).toString());
        body.put("paymentMethod", "MTN_MOMO");
        body.put("transactionReference", reference);
        body.put("evidenceFileKey", fileKey);
        if (manualReview) {
            body.put("manualReview", true);
        }
        body.put("allocations", List.of(Map.of(
                "type", "CONTRIBUTION", "amount", new BigDecimal(amount),
                "periodYear", NOW.getYear(), "periodMonth", NOW.getMonthValue())));
        return mockMvc.perform(post(base() + "/payments")
                        .header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult addAccount(Actor who, String channel, String identifier, String label) throws Exception {
        return mockMvc.perform(post(base() + "/payments/accounts")
                        .header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("channel", channel, "accountIdentifier", identifier, "label", label))))
                .andReturn();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
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
