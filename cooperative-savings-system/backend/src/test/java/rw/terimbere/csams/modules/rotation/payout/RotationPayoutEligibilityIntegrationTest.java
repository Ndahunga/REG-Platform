package rw.terimbere.csams.modules.rotation.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.NEXT;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * Whether a payout can be requested, and why not, is decided by the backend from the very rules that enforce the
 * request. The screen only shows what it is told, so it can never hide a legal action or offer an illegal one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RotationPayoutEligibilityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LedgerService ledgerService;

    /** Four members of 30,000 a month; the first is the recipient of the first period. */
    private PaymentScenario scheme(boolean withOtherCash) throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ROTATING", 30);
        for (int i = 1; i < 4; i++) {
            p.addMember(30);
        }
        p.configureRotation(NEXT, 10000, 0);
        p.activateCycle(NEXT, p.allMembers());
        p.rows(NEXT, p.allMembers());
        if (withOtherCash) {
            ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                    .cooperativeId(p.id())
                    .transactionType(LedgerTransactionType.OTHER_INCOME)
                    .debitAmount(BigDecimal.ZERO)
                    .creditAmount(new BigDecimal("100000"))
                    .currency("RWF")
                    .transactionDate(java.time.LocalDate.now())
                    .reference("SEED")
                    .sourceEntityType(LedgerService.SOURCE_INCOME_EXPENSE)
                    .sourceEntityId(UUID.randomUUID())
                    .description("Cash that is not owed to any recipient")
                    .recordedBy(p.scheme.president().id())
                    .approvedBy(p.scheme.president().id())
                    .idempotencyKey("SEED:" + UUID.randomUUID())
                    .build());
        }
        return p;
    }

    private void everyonePays(PaymentScenario p) throws Exception {
        for (Actor member : p.allMembers()) {
            p.postedFor(member, "30000", contribution(NEXT, "30000"));
        }
    }

    @Test
    void collectedFundsMakeThePeriodRequestableUpToWhatWasCollected() throws Exception {
        PaymentScenario p = scheme(false);
        everyonePays(p);
        JsonNode first = eligibilityOf(p, p.reviewer, NEXT);
        assertThat(first.path("canRequestPayout").asBoolean()).isTrue();
        assertThat(first.path("payoutBlockedReason").isNull()).isTrue();
        assertThat(first.path("maxRequestable").decimalValue()).isEqualByComparingTo("120000");
        assertThat(status(p.requestPayout(p.reviewer, p.period(NEXT), "120000"))).isEqualTo(200);
    }

    @Test
    void noCollectionYetButAPoolReserveStillAllowsARequestUpToTheReserve() throws Exception {
        PaymentScenario p = scheme(true);
        assertThat(status(poolEntry(p, "OPENING", "30000"))).isEqualTo(200);

        JsonNode first = eligibilityOf(p, p.reviewer, NEXT);
        assertThat(first.path("canRequestPayout").asBoolean()).as("the backend allows it, so the screen must not hide it").isTrue();
        assertThat(first.path("maxRequestable").decimalValue()).isEqualByComparingTo("30000");

        // and the backend really does accept exactly that, and nothing more
        assertThat(p.code(p.requestPayout(p.reviewer, p.period(NEXT), "30001"))).isEqualTo("PAYOUT_EXCEEDS_ENTITLEMENT");
        MvcResult ok = p.requestPayout(p.reviewer, p.period(NEXT), "30000");
        assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    void neitherCollectionNorReserveMeansNoRequestAndAStableReason() throws Exception {
        PaymentScenario p = scheme(true);
        JsonNode first = eligibilityOf(p, p.reviewer, NEXT);
        assertThat(first.path("canRequestPayout").asBoolean()).isFalse();
        assertThat(first.path("payoutBlockedReason").asText()).isEqualTo("NO_COLLECTED_FUNDS");
        assertThat(first.path("maxRequestable").decimalValue()).isEqualByComparingTo("0");
        assertThat(status(p.requestPayout(p.reviewer, p.period(NEXT), null))).isEqualTo(422);
    }

    @Test
    void aPeriodThatWasPaidOutInFullSaysSoAndAnOpenPayoutBlocksANewRequest() throws Exception {
        PaymentScenario p = scheme(false);
        everyonePays(p);
        MvcResult requested = p.requestPayout(p.reviewer, p.period(NEXT), "120000");
        UUID payout = UUID.fromString(p.data(requested).path("id").asText());
        assertThat(eligibilityOf(p, p.reviewer, NEXT).path("payoutBlockedReason").asText()).isEqualTo("PAYOUT_ALREADY_OPEN");

        // what each person may do with the open payout
        JsonNode asRequester = payoutActions(p, p.reviewer, payout);
        assertThat(asRequester.path("canApprove").asBoolean()).isFalse();
        assertThat(asRequester.path("approveBlockedReason").asText()).isEqualTo("NOT_AUTHORIZED");
        JsonNode asPresident = payoutActions(p, p.scheme.president(), payout);
        assertThat(asPresident.path("canApprove").asBoolean()).isTrue();
        assertThat(asPresident.path("canConfirmDisbursement").asBoolean()).isFalse();
        assertThat(asPresident.path("confirmBlockedReason").asText()).isEqualTo("NOT_APPROVED");
        JsonNode asRecipient = payoutActions(p, p.allMembers().get(0), payout);
        assertThat(asRecipient.path("canApprove").asBoolean()).isFalse();
        assertThat(asRecipient.path("canCancel").asBoolean()).isFalse();

        p.approveAndDisburse(payout, "BK-FULL");
        JsonNode done = eligibilityOf(p, p.reviewer, NEXT);
        assertThat(done.path("canRequestPayout").asBoolean()).isFalse();
        assertThat(done.path("payoutBlockedReason").asText()).isEqualTo("PAYOUT_ALREADY_COMPLETED");
        JsonNode after = payoutActions(p, p.scheme.president(), payout);
        assertThat(after.path("canApprove").asBoolean()).isFalse();
        assertThat(after.path("canCancel").asBoolean()).isFalse();
        assertThat(after.path("cancelBlockedReason").asText()).isEqualTo("ALREADY_DISBURSED");
    }

    @Test
    void anUnauthorisedUserIsToldNotAuthorisedAndAnotherSchemeIsRefused() throws Exception {
        PaymentScenario p = scheme(false);
        PaymentScenario other = scheme(false);
        everyonePays(p);
        JsonNode asMember = eligibilityOf(p, p.allMembers().get(1), NEXT);
        assertThat(asMember.path("canRequestPayout").asBoolean()).isFalse();
        assertThat(asMember.path("payoutBlockedReason").asText()).isEqualTo("NOT_AUTHORIZED");
        JsonNode asRecipient = eligibilityOf(p, p.allMembers().get(0), NEXT);
        assertThat(asRecipient.path("canRequestPayout").asBoolean()).isFalse();
        assertThat(status(p.getAs(other.scheme.president(), "/rotation/payouts/eligibility"))).isEqualTo(403);
        assertThat(status(p.requestPayout(p.allMembers().get(1), p.period(NEXT), null))).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode eligibilityOf(PaymentScenario p, Actor who, java.time.YearMonth month) throws Exception {
        MvcResult result = p.getAs(who, "/rotation/payouts/eligibility");
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        UUID period = p.period(month);
        for (JsonNode row : p.data(result)) {
            if (period.toString().equals(row.path("periodId").asText())) {
                return row;
            }
        }
        throw new AssertionError("period missing from eligibility");
    }

    private JsonNode payoutActions(PaymentScenario p, Actor who, UUID payout) throws Exception {
        MvcResult result = p.getAs(who, "/rotation/payouts/" + payout);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return p.data(result).path("actions");
    }

    private MvcResult poolEntry(PaymentScenario p, String type, String amount) throws Exception {
        return mockMvc.perform(post(p.base() + "/rotation/pool/entries")
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"amount\":" + amount + ",\"reason\":\"Opening pool from the old books\"}"))
                .andReturn();
    }
}
