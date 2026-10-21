package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;

/**
 * HTTP-level tests of the member payment API with real tokens, so {@code @PreAuthorize}, the cooperative scope
 * filter, the subscription interceptor and the service rules are all exercised together.
 *
 * <p>Runs on H2 in PostgreSQL mode: PostgreSQL row-lock behavior is NOT verified by these tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberPaymentApiIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    /** Confirmation evidence for the MTN payments used in this class. */
    private static final String EVIDENCE =
            "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"Provider confirmation seen in the merchant portal\",\"reference\":\"PROVIDER-REF-1\"}";

    private record Actor(UUID id, String token) {}

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MemberPaymentRepository paymentRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LoanRepaymentRepository loanRepaymentRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private CooperativeMembershipRepository membershipRepository;
    @Autowired private CooperativeSubscriptionRepository subscriptionRepository;

    private String superAdminToken;
    private UUID coopA;
    private UUID coopB;
    private Actor member1;
    private Actor member2;
    private Actor accountant;
    private Actor accountant2;
    private Actor secretary;
    private Actor loanOfficer;
    private Actor memberB;
    private Actor accountantB;
    private Loan loanA1;
    private Loan loanA2;
    private Loan loanB1;

    @BeforeAll
    void fixtures() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        coopA = createCooperative("Pay API A");
        coopB = createCooperative("Pay API B");
        member1 = register(coopA, "MEMBER");
        member2 = register(coopA, "MEMBER");
        accountant = register(coopA, "ACCOUNTANT");
        accountant2 = register(coopA, "ACCOUNTANT");
        secretary = register(coopA, "SECRETARY");
        loanOfficer = register(coopA, "LOAN_OFFICER");
        memberB = register(coopB, "MEMBER");
        accountantB = register(coopB, "ACCOUNTANT");
        loanA1 = saveLoan(coopA, member1.id());
        loanA2 = saveLoan(coopA, member2.id());
        loanB1 = saveLoan(coopB, memberB.id());
    }

    // ------------------------------------------------------------ submission

    @Test
    void aMemberSubmitsTheirOwnPaymentAndSeesOnlyTheMemberView() throws Exception {
        MvcResult result = submit(member1, coopA, body("12000", "MTN_MOMO", ref(), contribution(2026, 9, "12000")), null);
        assertThat(status(result)).isEqualTo(200);
        JsonNode data = data(result);
        assertThat(data.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(data.path("memberUserId").asText()).isEqualTo(member1.id().toString());
        assertThat(data.path("allocations")).hasSize(1);
        assertThat(data.path("allocations").get(0).path("type").asText()).isEqualTo("CONTRIBUTION");
        // Internal and staff-only fields are never shown to the payer.
        for (String hidden : List.of("verifiedBy", "postedBy", "extractedAmount", "extractedReference", "receiptStatus", "receiptConfidence")) {
            assertThat(absent(data, hidden)).as(hidden).isTrue();
        }
    }

    @Test
    void submissionRequiresTheMandatoryFieldsAndSaneAmounts() throws Exception {
        assertThat(status(submit(member1, coopA, "{}", null))).isEqualTo(400);
        for (String amount : List.of("0", "-5", "10.00001")) {
            String bad = body(amount, "MTN_MOMO", ref(), contribution(2026, 9, "1000"));
            assertThat(status(submit(member1, coopA, bad, null))).as(amount).isEqualTo(400);
        }
        // No allocations at all.
        String noAllocations = """
                {"amount":1000,"paymentDate":"%s","paymentMethod":"MTN_MOMO","transactionReference":"%s","allocations":[]}
                """.formatted(today(), ref());
        assertThat(status(submit(member1, coopA, noAllocations, null))).isEqualTo(400);
        // Electronic payment without a reference is a business error with a stable code.
        MvcResult noRef = submit(member1, coopA, body("1000", "MTN_MOMO", null, contribution(2026, 9, "1000")), null);
        assertThat(status(noRef)).isEqualTo(422);
        assertThat(error(noRef).path("code").asText()).isEqualTo("PAYMENT_REFERENCE_REQUIRED");
        // Cash needs none.
        assertThat(status(submit(member1, coopA, body("1000", "CASH", null, contribution(2026, 10, "1000")), null))).isEqualTo(200);
        // A future payment date is refused.
        String future = """
                {"amount":1000,"paymentDate":"%s","paymentMethod":"CASH","allocations":[%s]}
                """.formatted(LocalDate.now(KIGALI).plusDays(2), contribution(2026, 11, "1000"));
        assertThat(error(submit(member1, coopA, future, null)).path("code").asText()).isEqualTo("PAYMENT_DATE_IN_FUTURE");
    }

    @Test
    void unsupportedPaymentMethodAndUnknownFieldsAreRejectedAsBadRequests() throws Exception {
        MvcResult method = submit(member1, coopA, body("1000", "BITCOIN", ref(), contribution(2026, 9, "1000")), null);
        assertThat(status(method)).isEqualTo(400);
        assertThat(error(method).path("code").asText()).isEqualTo("INVALID_REQUEST_BODY");
        assertThat(method.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("rw.terimbere");

        MvcResult malformed = submit(member1, coopA, "{not json", null);
        assertThat(status(malformed)).isEqualTo(400);
        assertThat(error(malformed).path("code").asText()).isEqualTo("INVALID_REQUEST_BODY");
    }

    @Test
    void aMemberCannotSubmitForAnotherMember() throws Exception {
        long before = paymentRepository.count();
        // The API has no member field at all; naming one is rejected rather than silently ignored.
        String forOther = """
                {"memberUserId":"%s","amount":1000,"paymentDate":"%s","paymentMethod":"CASH","allocations":[%s]}
                """.formatted(member2.id(), today(), contribution(2026, 9, "1000"));
        MvcResult result = submit(member1, coopA, forOther, null);
        assertThat(status(result)).isEqualTo(400);
        assertThat(paymentRepository.count()).isEqualTo(before);

        // A loan of another member cannot be allocated either.
        MvcResult otherLoan = submit(member1, coopA, body("1000", "MTN_MOMO", ref(), loan(loanA2.getId(), "1000")), null);
        assertThat(status(otherLoan)).isEqualTo(404);
    }

    @Test
    void allocationsMustReconcileAndBeValid() throws Exception {
        MvcResult mismatch = submit(member1, coopA, body("3000", "MTN_MOMO", ref(),
                contribution(2026, 9, "1000") + "," + loan(loanA1.getId(), "1500")), null);
        assertThat(status(mismatch)).isEqualTo(422);
        assertThat(error(mismatch).path("code").asText()).isEqualTo("ALLOCATION_TOTAL_MISMATCH");

        MvcResult duplicate = submit(member1, coopA, body("2000", "MTN_MOMO", ref(),
                contribution(2026, 9, "1000") + "," + contribution(2026, 9, "1000")), null);
        assertThat(error(duplicate).path("code").asText()).isEqualTo("INVALID_ALLOCATION");

        MvcResult badMonth = submit(member1, coopA, body("1000", "MTN_MOMO", ref(), contribution(2026, 13, "1000")), null);
        assertThat(status(badMonth)).isEqualTo(400);

        MvcResult badBreakdown = submit(member1, coopA, body("1000", "MTN_MOMO", ref(),
                """
                {"type":"LOAN_REPAYMENT","amount":1000,"loanId":"%s","principalAmount":500,"interestAmount":400,"penaltyAmount":50}
                """.formatted(loanA1.getId())), null);
        assertThat(error(badBreakdown).path("code").asText()).isEqualTo("INVALID_BREAKDOWN");
    }

    @Test
    void aCombinedPaymentIsAcceptedAsOneClaim() throws Exception {
        MvcResult result = submit(member1, coopA, body("21000", "BANK_TRANSFER", ref(),
                contribution(2027, 1, "10000") + "," + loan(loanA1.getId(), "11000")), null);
        assertThat(status(result)).isEqualTo(200);
        assertThat(data(result).path("allocations")).hasSize(2);
        assertThat(data(result).path("allocatedAmount").decimalValue()).isEqualByComparingTo("21000");
    }

    @Test
    void duplicateReferencesAreRefusedWithAStableCodeAndNoDetailsAboutTheOtherPayment() throws Exception {
        String reference = ref();
        assertThat(status(submit(member1, coopA, body("1000", "MTN_MOMO", reference, contribution(2027, 2, "1000")), null)))
                .isEqualTo(200);
        MvcResult duplicate = submit(member2, coopA,
                body("1000", "MTN_MOMO", " " + reference.toLowerCase() + "- ", contribution(2027, 2, "1000")), null);
        assertThat(status(duplicate)).isEqualTo(409);
        assertThat(error(duplicate).path("code").asText()).isEqualTo("DUPLICATE_PAYMENT_REFERENCE");
        assertThat(duplicate.getResponse().getContentAsString()).doesNotContain("SUBMITTED").doesNotContain(member1.id().toString());
        // Same text under another method is a different transaction.
        assertThat(status(submit(member2, coopA, body("1000", "BANK_TRANSFER", reference, contribution(2027, 2, "1000")), null)))
                .isEqualTo(200);
    }

    @Test
    void idempotentRetriesReturnTheOriginalAndConflictingKeysAreRefused() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        String payload = body("2500", "MTN_MOMO", ref(), contribution(2027, 3, "2500"));
        UUID first = UUID.fromString(data(submit(member1, coopA, payload, key)).path("id").asText());
        MvcResult retry = submit(member1, coopA, payload, key);
        assertThat(status(retry)).isEqualTo(200);
        assertThat(data(retry).path("id").asText()).isEqualTo(first.toString());
        assertThat(eventsOf(member1, coopA, first)).hasSize(1);

        MvcResult conflicting = submit(member1, coopA, body("9000", "MTN_MOMO", ref(), contribution(2027, 4, "9000")), key);
        assertThat(status(conflicting)).isEqualTo(409);
        assertThat(error(conflicting).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        MvcResult tooLong = submit(member1, coopA, payload, "k".repeat(129));
        assertThat(status(tooLong)).isEqualTo(400);
    }

    @Test
    void evidenceMustBeAFileOfTheSchemeUploadedByTheSubmitter() throws Exception {
        String key = upload(member1, coopA);
        String withEvidence = """
                {"amount":1000,"paymentDate":"%s","paymentMethod":"MTN_MOMO","transactionReference":"%s","evidenceFileKey":"%s","allocations":[%s]}
                """.formatted(today(), ref(), key, contribution(2027, 5, "1000"));
        MvcResult ok = submit(member1, coopA, withEvidence, null);
        assertThat(status(ok)).isEqualTo(200);
        assertThat(data(ok).path("evidenceFileKey").asText()).isEqualTo(key);

        // Another member of the same scheme cannot attach it.
        String stolen = withEvidence.replace(ref0(withEvidence), ref());
        assertThat(status(submit(member2, coopA, stolen, null))).isEqualTo(403);
        // A key that does not exist.
        String missing = withEvidence.replace(key, "cooperatives/" + coopA + "/contribution_evidence/nope.png").replace(ref0(withEvidence), ref());
        assertThat(status(submit(member1, coopA, missing, null))).isEqualTo(404);
    }

    // ----------------------------------------------------------------- reads

    @Test
    void membersSeeOnlyTheirOwnPaymentsAndStaffSeeTheScheme() throws Exception {
        UUID id = submittedId(member1, "5000", 2027, 6);
        assertThat(status(doGet(member1, coopA, "/" + id))).isEqualTo(200);
        assertThat(status(doGet(member2, coopA, "/" + id))).isEqualTo(404);
        assertThat(status(doGet(accountant, coopA, "/" + id))).isEqualTo(200);
        // Read-only officers are not payment staff.
        assertThat(status(doGet(secretary, coopA, "/" + id))).isEqualTo(404);
        assertThat(status(doGet(loanOfficer, coopA, "/" + id))).isEqualTo(404);
        // Allocations and events follow the same visibility.
        assertThat(status(doGet(member1, coopA, "/" + id + "/allocations"))).isEqualTo(200);
        assertThat(status(doGet(member2, coopA, "/" + id + "/allocations"))).isEqualTo(404);
        assertThat(status(doGet(member2, coopA, "/" + id + "/events"))).isEqualTo(404);
    }

    @Test
    void myPaymentsAreScopedToTheCallerAndPagingIsBounded() throws Exception {
        UUID mine = submittedId(member1, "1000", 2027, 7);
        submittedId(member2, "1000", 2027, 7);
        MvcResult list = doGet(member1, coopA, "/mine?size=100");
        assertThat(status(list)).isEqualTo(200);
        JsonNode content = data(list).path("content");
        assertThat(content.size()).isGreaterThanOrEqualTo(1);
        for (JsonNode row : content) {
            assertThat(row.path("memberUserId").asText()).isEqualTo(member1.id().toString());
            assertThat(absent(row, "memberName")).isTrue();
        }
        assertThat(ids(content)).contains(mine.toString());
        assertThat(data(list).path("size").asInt()).isEqualTo(100);

        assertThat(status(doGet(member1, coopA, "/mine?size=101"))).isEqualTo(400);
        assertThat(status(doGet(member1, coopA, "/mine?size=0"))).isEqualTo(400);
        assertThat(status(doGet(member1, coopA, "/mine?page=-1"))).isEqualTo(400);
        assertThat(status(doGet(member1, coopA, "/mine?size=abc"))).isEqualTo(400);
        assertThat(status(doGet(member1, coopA, "/mine?status=NOT_A_STATUS"))).isEqualTo(400);
        // Default page size and a client sort parameter that is ignored (never reaches the query).
        MvcResult defaults = doGet(member1, coopA, "/mine?sort=notAProperty,desc");
        assertThat(status(defaults)).isEqualTo(200);
        assertThat(data(defaults).path("size").asInt()).isEqualTo(20);
    }

    @Test
    void staffQueuesAreClosedToMembersAndUnauthorizedStaff() throws Exception {
        for (Actor outsider : List.of(member1, secretary, loanOfficer)) {
            assertThat(status(doGet(outsider, coopA, "/review-queue"))).as("queue").isEqualTo(403);
            assertThat(status(doGet(outsider, coopA, ""))).as("list").isEqualTo(403);
        }
        assertThat(status(doGet(accountant, coopA, "/review-queue"))).isEqualTo(200);
        assertThat(status(doGet(accountant, coopA, "?size=101"))).isEqualTo(400);
    }

    @Test
    void theReviewQueueHoldsSubmittedAndUnderReviewOldestFirstAndVerifiedIsASeparateFilter() throws Exception {
        UUID submitted = submittedId(member1, "1000", 2027, 8);
        UUID underReview = submittedId(member2, "1000", 2027, 8);
        assertThat(status(doPost(accountant, coopA, "/" + underReview + "/start-review", null))).isEqualTo(200);
        UUID verified = verifiedContribution(member1, 2027, 9);

        List<String> queue = ids(data(doGet(accountant, coopA, "/review-queue?size=100")).path("content"));
        assertThat(queue).contains(submitted.toString(), underReview.toString()).doesNotContain(verified.toString());
        assertThat(queue.indexOf(submitted.toString())).isLessThan(queue.indexOf(underReview.toString()));

        JsonNode verifiedList = data(doGet(accountant, coopA, "?status=VERIFIED&size=100")).path("content");
        assertThat(ids(verifiedList)).contains(verified.toString());
        for (JsonNode row : verifiedList) {
            assertThat(row.path("status").asText()).isEqualTo("VERIFIED");
            assertThat(row.path("memberName").asText()).isNotBlank();
        }
        JsonNode byMember = data(doGet(accountant, coopA, "?memberUserId=" + member2.id() + "&size=100")).path("content");
        for (JsonNode row : byMember) {
            assertThat(row.path("memberUserId").asText()).isEqualTo(member2.id().toString());
        }
    }

    @Test
    void paymentsOfAnotherSchemeAreNeverExposed() throws Exception {
        UUID inA = submittedId(member1, "1000", 2027, 10);
        // Not a member of scheme A: refused at the scheme boundary.
        assertThat(status(doGet(accountantB, coopA, "/" + inA))).isEqualTo(403);
        assertThat(status(doGet(accountantB, coopA, "/review-queue"))).isEqualTo(403);
        assertThat(status(doPost(accountantB, coopA, "/" + inA + "/start-review", null))).isEqualTo(403);
        // Through the scheme they do belong to, the identifier simply does not exist.
        assertThat(status(doGet(accountantB, coopB, "/" + inA))).isEqualTo(404);
        assertThat(status(doPost(accountantB, coopB, "/" + inA + "/start-review", null))).isEqualTo(404);
        assertThat(status(doPost(accountantB, coopB, "/" + inA + "/reject", "{\"reason\":\"x\"}"))).isEqualTo(404);
        assertThat(ids(data(doGet(accountantB, coopB, "?size=100")).path("content"))).doesNotContain(inA.toString());
        assertThat(paymentRepository.findById(inA).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
    }

    @Test
    void eventsShowTheHistoryWithStaffIdentitiesOnlyToStaff() throws Exception {
        UUID id = verifiedContribution(member1, 2027, 11);
        JsonNode staffView = eventsOf(accountant, coopA, id);
        assertThat(staffView).hasSize(3);
        assertThat(staffView.get(0).path("toStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(staffView.get(2).path("toStatus").asText()).isEqualTo("VERIFIED");
        assertThat(staffView.get(2).path("actorUserId").asText()).isEqualTo(accountant2.id().toString());
        JsonNode memberView = eventsOf(member1, coopA, id);
        assertThat(memberView).hasSize(3);
        for (JsonNode e : memberView) {
            assertThat(absent(e, "actorUserId")).isTrue();
            assertThat(e.path("actorRole").asText()).isNotBlank();
        }
        // The payer's detail view hides verifier identity; staff still see it.
        assertThat(absent(data(doGet(member1, coopA, "/" + id)), "verifiedBy")).isTrue();
        assertThat(data(doGet(accountant, coopA, "/" + id)).path("verifiedBy").asText()).isEqualTo(accountant2.id().toString());
    }

    // ------------------------------------------------------ review lifecycle

    @Test
    void theLifecycleRunsSubmittedToUnderReviewToVerifiedAndPostingIsSeparate() throws Exception {
        UUID id = submittedId(member1, "4000", 2027, 12);
        assertThat(data(doPost(accountant, coopA, "/" + id + "/start-review", null)).path("status").asText()).isEqualTo("UNDER_REVIEW");
        MvcResult verified = doPost(accountant2, coopA, "/" + id + "/verify", EVIDENCE);
        assertThat(status(verified)).isEqualTo(200);
        assertThat(data(verified).path("status").asText()).isEqualTo("VERIFIED");

        // Verification never posts, and the verifier cannot be the poster.
        assertThat(doPost(accountant2, coopA, "/" + id + "/post", null).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(accountant2, coopA, "/" + id + "/approve", "{\"reason\":\"x\"}").getResponse().getStatus()).isIn(404, 405);
        // A status-only reversal does not exist, and a payment that was never posted cannot be reversed.
        assertThat(doPost(accountant, coopA, "/" + id + "/reverse", "{\"reason\":\"x\"}").getResponse().getStatus()).isIn(404, 405);
        MvcResult reversal = doPost(accountant, coopA, "/" + id + "/reversals", "{\"reason\":\"x\"}");
        assertThat(status(reversal)).isEqualTo(422);
        assertThat(error(reversal).path("code").asText()).isEqualTo("PAYMENT_NOT_POSTED");
        assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.VERIFIED);
    }

    @Test
    void invalidTransitionsAreRefusedWithAStableCode() throws Exception {
        UUID id = submittedId(member1, "3000", 2028, 1);
        MvcResult early = doPost(accountant, coopA, "/" + id + "/verify", EVIDENCE);
        assertThat(status(early)).isEqualTo(422);
        assertThat(error(early).path("code").asText()).isEqualTo("INVALID_PAYMENT_TRANSITION");
        doPost(accountant, coopA, "/" + id + "/start-review", null);
        MvcResult twice = doPost(accountant, coopA, "/" + id + "/start-review", null);
        assertThat(error(twice).path("code").asText()).isEqualTo("INVALID_PAYMENT_TRANSITION");

        assertThat(status(doPost(accountant, coopA, "/" + id + "/reject", "{\"reason\":\"Receipt unreadable\"}"))).isEqualTo(200);
        MvcResult afterReject = doPost(accountant2, coopA, "/" + id + "/verify", EVIDENCE);
        assertThat(error(afterReject).path("code").asText()).isEqualTo("INVALID_PAYMENT_TRANSITION");
        assertThat(error(doPost(accountant2, coopA, "/" + id + "/reject", "{\"reason\":\"again\"}")).path("code").asText())
                .isEqualTo("INVALID_PAYMENT_TRANSITION");
    }

    @Test
    void rejectionNeedsAReasonAndReleasesTheReferenceButAVerifiedPaymentCanNeverBeRejected() throws Exception {
        String reference = ref();
        UUID id = UUID.fromString(data(submit(member1, coopA,
                body("1000", "MTN_MOMO", reference, contribution(2028, 2, "1000")), null)).path("id").asText());
        assertThat(status(doPost(accountant, coopA, "/" + id + "/reject", "{\"reason\":\"  \"}"))).isEqualTo(400);
        assertThat(status(doPost(accountant, coopA, "/" + id + "/reject", "{}"))).isEqualTo(400);
        assertThat(status(doPost(accountant, coopA, "/" + id + "/reject", "{\"reason\":\"Wrong amount\",\"x\":1}"))).isEqualTo(400);
        MvcResult rejected = doPost(accountant, coopA, "/" + id + "/reject", "{\"reason\":\"Wrong amount\"}");
        assertThat(data(rejected).path("status").asText()).isEqualTo("REJECTED");
        assertThat(data(rejected).path("rejectionReason").asText()).isEqualTo("Wrong amount");
        // The rejected claim released its reference: a corrected resubmission is accepted.
        assertThat(status(submit(member1, coopA, body("1000", "MTN_MOMO", reference, contribution(2028, 2, "1000")), null)))
                .isEqualTo(200);

        // Once funds are confirmed, ordinary rejection is refused: the payment, its evidence and history stay as they are.
        UUID verified = verifiedContribution(member1, 2028, 3);
        MvcResult refused = doPost(accountant, coopA, "/" + verified + "/reject", "{\"reason\":\"Found a duplicate\"}");
        assertThat(status(refused)).isEqualTo(422);
        assertThat(error(refused).path("code").asText()).isEqualTo("VERIFIED_PAYMENT_CANNOT_BE_REJECTED");
        assertThat(paymentRepository.findById(verified).orElseThrow().getStatus()).isEqualTo(PaymentStatus.VERIFIED);
    }

    @Test
    void membersAndUnauthorizedStaffCannotReview() throws Exception {
        UUID id = submittedId(member1, "1000", 2028, 4);
        for (Actor actor : List.of(member1, member2, secretary, loanOfficer)) {
            assertThat(status(doPost(actor, coopA, "/" + id + "/start-review", null))).as("start-review").isEqualTo(403);
            assertThat(status(doPost(actor, coopA, "/" + id + "/verify", EVIDENCE))).as("verify").isEqualTo(403);
            assertThat(status(doPost(actor, coopA, "/" + id + "/reject", "{\"reason\":\"x\"}"))).as("reject").isEqualTo(403);
        }
        assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
    }

    @Test
    void nobodyReviewsOrVerifiesTheirOwnPayment() throws Exception {
        // An accountant is also a member: their own payment has to be reviewed by someone else.
        UUID own = submittedId(accountant, "1000", 2028, 5);
        assertThat(status(doPost(accountant, coopA, "/" + own + "/start-review", null))).isEqualTo(403);
        assertThat(status(doPost(accountant, coopA, "/" + own + "/verify", EVIDENCE))).isEqualTo(403);
        assertThat(status(doPost(accountant, coopA, "/" + own + "/reject", "{\"reason\":\"x\"}"))).isEqualTo(403);
        assertThat(status(doPost(accountant2, coopA, "/" + own + "/start-review", null))).isEqualTo(200);
        // Even after another reviewer started it, the submitter cannot verify.
        assertThat(status(doPost(accountant, coopA, "/" + own + "/verify", EVIDENCE))).isEqualTo(403);
        assertThat(status(doPost(accountant2, coopA, "/" + own + "/verify", EVIDENCE))).isEqualTo(200);
    }

    @Test
    void loanBreakdownMustBeRecordedAndReconcileBeforeVerification() throws Exception {
        UUID id = UUID.fromString(data(submit(member1, coopA, body("10000", "MTN_MOMO", ref(), loan(loanA1.getId(), "10000")), null))
                .path("id").asText());
        String allocationId = data(doGet(member1, coopA, "/" + id)).path("allocations").get(0).path("id").asText();
        String path = "/" + id + "/allocations/" + allocationId + "/loan-breakdown";
        doPost(accountant, coopA, "/" + id + "/start-review", null);

        // Not LOAN_WRITE, or the payer: refused. Bad numbers: refused.
        String good = "{\"principalAmount\":7000,\"interestAmount\":2500,\"penaltyAmount\":500}";
        assertThat(status(doPut(secretary, coopA, path, good))).isEqualTo(403);
        assertThat(status(doPut(member1, coopA, path, good))).isEqualTo(403);
        assertThat(status(doPut(accountant2, coopA, path, "{\"principalAmount\":-1,\"interestAmount\":9000,\"penaltyAmount\":2000}")))
                .isEqualTo(400);
        assertThat(status(doPut(accountant2, coopA, path, "{\"principalAmount\":1,\"interestAmount\":1}"))).isEqualTo(400);
        MvcResult mismatch = doPut(accountant2, coopA, path, "{\"principalAmount\":5000,\"interestAmount\":4000,\"penaltyAmount\":500}");
        assertThat(status(mismatch)).isEqualTo(422);
        assertThat(error(mismatch).path("code").asText()).isEqualTo("INVALID_BREAKDOWN");
        // An unrelated allocation id is simply not found.
        assertThat(status(doPut(accountant2, coopA, "/" + id + "/allocations/" + UUID.randomUUID() + "/loan-breakdown", good))).isEqualTo(404);

        MvcResult recorded = doPut(accountant2, coopA, path, good);
        assertThat(status(recorded)).isEqualTo(200);
        assertThat(data(recorded).path("allocations").get(0).path("interestAmount").decimalValue()).isEqualByComparingTo("2500");
        assertThat(data(doPost(accountant2, coopA, "/" + id + "/verify", EVIDENCE)).path("status").asText()).isEqualTo("VERIFIED");

        MvcResult frozen = doPut(accountant2, coopA, path, good);
        assertThat(error(frozen).path("code").asText()).isEqualTo("PAYMENT_NOT_EDITABLE");
    }

    @Test
    void breakdownCannotBeRecordedOnAContributionAllocation() throws Exception {
        UUID id = submittedId(member1, "1000", 2028, 6);
        String allocationId = data(doGet(member1, coopA, "/" + id)).path("allocations").get(0).path("id").asText();
        MvcResult result = doPut(accountant, coopA, "/" + id + "/allocations/" + allocationId + "/loan-breakdown",
                "{\"principalAmount\":1000,\"interestAmount\":0,\"penaltyAmount\":0}");
        assertThat(status(result)).isEqualTo(422);
        assertThat(error(result).path("code").asText()).isEqualTo("INVALID_BREAKDOWN");
    }

    @Test
    void concurrentReviewStartsAreSerialized() throws Exception {
        UUID id = submittedId(member1, "1000", 2028, 7);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return status(doPost(accountant, coopA, "/" + id + "/start-review", null));
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
            assertThat(statuses.stream().filter(s -> s == 422)).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(eventsOf(accountant, coopA, id)).hasSize(2);
    }

    // ------------------------------------------------- subscription enforcement

    @Test
    void anExpiredSchemeCannotSubmitOrModifyPaymentsButStillReadsThem() throws Exception {
        UUID coop = createCooperative("Pay API Expired");
        Actor payer = register(coop, "MEMBER");
        Actor staff = register(coop, "ACCOUNTANT");
        Actor staff2 = register(coop, "ACCOUNTANT");
        UUID untouched = UUID.fromString(data(submit(payer, coop,
                body("1000", "MTN_MOMO", ref(), contribution(2028, 8, "1000")), null)).path("id").asText());
        UUID inReview = UUID.fromString(data(submit(payer, coop,
                body("1000", "MTN_MOMO", ref(), contribution(2028, 9, "1000")), null)).path("id").asText());
        assertThat(status(doPost(staff, coop, "/" + inReview + "/start-review", null))).isEqualTo(200);

        expireTrial(coop);

        String reference = ref();
        MvcResult create = submit(payer, coop, body("1000", "MTN_MOMO", reference, contribution(2028, 10, "1000")), null);
        assertThat(status(create)).isEqualTo(402);
        assertThat(error(create).path("code").asText()).isEqualTo("SUBSCRIPTION_INACTIVE");
        assertThat(status(doPost(staff, coop, "/" + untouched + "/start-review", null))).isEqualTo(402);
        assertThat(status(doPost(staff2, coop, "/" + inReview + "/verify", EVIDENCE))).isEqualTo(402);
        assertThat(status(doPost(staff2, coop, "/" + inReview + "/reject", "{\"reason\":\"x\"}"))).isEqualTo(402);
        assertThat(status(doPut(staff2, coop, "/" + inReview + "/allocations/" + UUID.randomUUID() + "/loan-breakdown",
                "{\"principalAmount\":1,\"interestAmount\":0,\"penaltyAmount\":0}"))).isEqualTo(402);

        // Nothing changed, and the blocked submission left no payment behind.
        assertThat(paymentRepository.findById(untouched).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
        assertThat(paymentRepository.findById(inReview).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNDER_REVIEW);
        assertThat(paymentRepository.findAll().stream().anyMatch(p -> reference.equals(p.getTransactionReference()))).isFalse();

        // Reads stay available.
        assertThat(status(doGet(payer, coop, "/" + untouched))).isEqualTo(200);
        assertThat(status(doGet(payer, coop, "/mine"))).isEqualTo(200);
        assertThat(status(doGet(payer, coop, "/" + untouched + "/events"))).isEqualTo(200);
        assertThat(status(doGet(payer, coop, "/" + untouched + "/allocations"))).isEqualTo(200);
        assertThat(status(doGet(staff, coop, "/review-queue"))).isEqualTo(200);
        assertThat(status(doGet(staff, coop, ""))).isEqualTo(200);
    }

    // --------------------------------------------------- financial integrity

    @Test
    void theWholeApiLifecycleNeverChangesBalancesLoansSharesOrTheLedger() throws Exception {
        long ledger = ledgerEntryRepository.count();
        long contributions = contributionRepository.count();
        long repayments = loanRepaymentRepository.count();
        Loan loanBefore = loanRepository.findById(loanA1.getId()).orElseThrow();
        BigDecimal principal = loanBefore.getOutstandingPrincipal();
        BigDecimal interest = loanBefore.getOutstandingInterest();
        BigDecimal penalty = loanBefore.getOutstandingPenalty();
        LoanStatus loanStatus = loanBefore.getStatus();
        int shares = membershipRepository.findByCooperativeIdAndUserId(coopA, member1.id()).orElseThrow().getShareCount();

        // A combined payment, taken all the way to VERIFIED.
        UUID combined = UUID.fromString(data(submit(member1, coopA, body("21000", "BANK_TRANSFER", ref(),
                contribution(2028, 11, "10000") + "," + loan(loanA1.getId(), "11000")), null)).path("id").asText());
        String allocationId = null;
        for (JsonNode a : data(doGet(member1, coopA, "/" + combined)).path("allocations")) {
            if ("LOAN_REPAYMENT".equals(a.path("type").asText())) {
                allocationId = a.path("id").asText();
            }
        }
        doPost(accountant, coopA, "/" + combined + "/start-review", null);
        doPut(accountant2, coopA, "/" + combined + "/allocations/" + allocationId + "/loan-breakdown",
                "{\"principalAmount\":8000,\"interestAmount\":2500,\"penaltyAmount\":500}");
        assertThat(status(doPost(accountant2, coopA, "/" + combined + "/verify", EVIDENCE))).isEqualTo(200);
        // And another one rejected.
        UUID other = submittedId(member1, "1000", 2028, 12);
        doPost(accountant, coopA, "/" + other + "/reject", "{\"reason\":\"No\"}");

        assertThat(ledgerEntryRepository.count()).isEqualTo(ledger);
        assertThat(contributionRepository.count()).isEqualTo(contributions);
        assertThat(loanRepaymentRepository.count()).isEqualTo(repayments);
        Loan loanAfter = loanRepository.findById(loanA1.getId()).orElseThrow();
        assertThat(loanAfter.getOutstandingPrincipal()).isEqualByComparingTo(principal);
        assertThat(loanAfter.getOutstandingInterest()).isEqualByComparingTo(interest);
        assertThat(loanAfter.getOutstandingPenalty()).isEqualByComparingTo(penalty);
        assertThat(loanAfter.getStatus()).isEqualTo(loanStatus);
        assertThat(membershipRepository.findByCooperativeIdAndUserId(coopA, member1.id()).orElseThrow().getShareCount())
                .isEqualTo(shares);
        assertThat(paymentRepository.findById(combined).orElseThrow().getStatus()).isEqualTo(PaymentStatus.VERIFIED);
    }

    // ---------------------------------------------------------------- helpers

    private UUID submittedId(Actor who, String amount, int year, int month) throws Exception {
        MvcResult result = submit(who, coopA, body(amount, "MTN_MOMO", ref(), contribution(year, month, amount)), null);
        assertThat(status(result)).isEqualTo(200);
        return UUID.fromString(data(result).path("id").asText());
    }

    private UUID verifiedContribution(Actor payer, int year, int month) throws Exception {
        UUID id = submittedId(payer, "1000", year, month);
        assertThat(status(doPost(accountant, coopA, "/" + id + "/start-review", null))).isEqualTo(200);
        assertThat(status(doPost(accountant2, coopA, "/" + id + "/verify", EVIDENCE))).isEqualTo(200);
        return id;
    }

    private JsonNode eventsOf(Actor who, UUID coop, UUID paymentId) throws Exception {
        MvcResult result = doGet(who, coop, "/" + paymentId + "/events");
        assertThat(status(result)).isEqualTo(200);
        return data(result);
    }

    private static List<String> ids(JsonNode content) {
        List<String> ids = new ArrayList<>();
        content.forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    private static boolean absent(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode error(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String ref() {
        return "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** Extracts the reference used in a body built by this test (to swap it for a fresh one). */
    private static String ref0(String json) {
        int i = json.indexOf("\"transactionReference\":\"") + "\"transactionReference\":\"".length();
        return json.substring(i, json.indexOf('"', i));
    }

    private static String today() {
        return LocalDate.now(KIGALI).toString();
    }

    private static String contribution(int year, int month, String amount) {
        return "{\"type\":\"CONTRIBUTION\",\"amount\":%s,\"periodYear\":%d,\"periodMonth\":%d}".formatted(amount, year, month);
    }

    private static String loan(UUID loanId, String amount) {
        return "{\"type\":\"LOAN_REPAYMENT\",\"amount\":%s,\"loanId\":\"%s\"}".formatted(amount, loanId);
    }

    private static String body(String amount, String method, String reference, String... allocations) {
        String refPart = reference == null ? "" : ",\"transactionReference\":\"" + reference + "\"";
        return "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"%s\"%s,\"allocations\":[%s]}"
                .formatted(amount, today(), method, refPart, String.join(",", allocations));
    }

    private MvcResult submit(Actor who, UUID coop, String json, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/cooperatives/" + coop + "/payments")
                .header("Authorization", "Bearer " + who.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult doGet(Actor who, UUID coop, String suffix) throws Exception {
        return mockMvc.perform(get("/api/v1/cooperatives/" + coop + "/payments" + suffix)
                        .header("Authorization", "Bearer " + who.token()))
                .andReturn();
    }

    private MvcResult doPost(Actor who, UUID coop, String suffix, String json) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/cooperatives/" + coop + "/payments" + suffix)
                .header("Authorization", "Bearer " + who.token());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult doPut(Actor who, UUID coop, String suffix, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/cooperatives/" + coop + "/payments" + suffix)
                        .header("Authorization", "Bearer " + who.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private String upload(Actor who, UUID coop) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        MvcResult result = mockMvc.perform(multipart("/api/v1/cooperatives/" + coop + "/files")
                        .file(file)
                        .param("category", "CONTRIBUTION_EVIDENCE")
                        .header("Authorization", "Bearer " + who.token()))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        return data(result).path("storageKey").asText();
    }

    private void expireTrial(UUID coop) {
        CooperativeSubscription subscription = subscriptionRepository.findByCooperativeId(coop).orElseThrow();
        Instant ended = Instant.now().minusSeconds(60);
        subscription.setStatus(SubscriptionStatus.TRIAL);
        subscription.setTrialEndsAt(ended);
        if (subscription.getTrialStartedAt() == null) {
            subscription.setTrialStartedAt(ended.minusSeconds(86_400));
        }
        subscriptionRepository.saveAndFlush(subscription);
    }

    private Loan saveLoan(UUID cooperativeId, UUID memberId) {
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .requestedAmount(new BigDecimal("100000"))
                .approvedAmount(new BigDecimal("100000"))
                .principalAmount(new BigDecimal("100000"))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(new BigDecimal("0"))
                .outstandingPrincipal(new BigDecimal("100000"))
                .outstandingInterest(new BigDecimal("0"))
                .requestDate(today.minusMonths(1))
                .disbursementDate(today.minusMonths(1))
                .dueDate(today.plusMonths(5))
                .status(LoanStatus.ACTIVE)
                .build());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private UUID createCooperative(String prefix) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(prefix + " " + UUID.randomUUID().toString().substring(0, 8))))
                .andReturn();
        assertThat(status(result)).isEqualTo(200);
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    private Actor register(UUID coop, String role) throws Exception {
        String username = "papi_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + coop + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Pay","lastName":"Api","username":"%s","email":"%s@test.local","roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andReturn();
        assertThat(status(result)).as("register " + role).isEqualTo(200);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Actor(UUID.fromString(data.path("userId").asText()), login(username, data.path("temporaryPassword").asText()));
    }
}
