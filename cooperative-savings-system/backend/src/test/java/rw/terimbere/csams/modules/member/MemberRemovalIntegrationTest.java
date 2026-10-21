package rw.terimbere.csams.modules.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.NEXT;
import static rw.terimbere.csams.testsupport.PaymentScenario.NOW;
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
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * Removing a member never loses financial history: a member with no history at all can be deleted; everyone else is
 * deactivated, which keeps every record, loan, obligation and report exactly as it was. The backend decides which,
 * for the right people only, and only inside the member's own scheme.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberRemovalIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    private PaymentScenario scheme(String model) throws Exception {
        return new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, model, 30);
    }

    /** A member created by an officer who has never signed in: no activity, nothing on record about them. */
    private UUID createUnusedMember(PaymentScenario p) throws Exception {
        String username = "unused_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post(p.base() + "/members")
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Never\",\"lastName\":\"Active\",\"username\":\"%s\",\"email\":\"%s@test.local\",\"roleInCooperative\":\"MEMBER\"}"
                                .formatted(username, username)))
                .andReturn();
        assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(p.data(created).path("userId").asText());
    }

    private MvcResult eligibilityOf(PaymentScenario p, Actor who, UUID member) throws Exception {
        return mockMvc.perform(get(p.base() + "/members/" + member + "/removal-eligibility")
                        .header("Authorization", PaymentScenario.bearer(who)))
                .andReturn();
    }

    private MvcResult deactivate(PaymentScenario p, Actor who, UUID member, String reason) throws Exception {
        return mockMvc.perform(post(p.base() + "/members/" + member + "/deactivate")
                        .header("Authorization", PaymentScenario.bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn();
    }

    private MvcResult reactivate(PaymentScenario p, Actor who, UUID member) throws Exception {
        return mockMvc.perform(post(p.base() + "/members/" + member + "/reactivate").header("Authorization", PaymentScenario.bearer(who))).andReturn();
    }

    private MvcResult hardDelete(PaymentScenario p, Actor who, UUID member) throws Exception {
        return mockMvc.perform(delete(p.base() + "/members/" + member).header("Authorization", PaymentScenario.bearer(who))).andReturn();
    }

    private String membershipStatus(PaymentScenario p, UUID member) {
        return jdbc.queryForObject("select membership_status from cooperative_memberships where cooperative_id = ? and user_id = ?",
                String.class, p.id(), member);
    }

    // ============================================================ no history: permanent deletion

    @Test
    void aMemberWithNoHistoryCanBePermanentlyDeletedByLeadershipAndTheDeletionIsAudited() throws Exception {
        PaymentScenario p = scheme("ACCUMULATING");
        UUID member = createUnusedMember(p);

        JsonNode e = p.data(eligibilityOf(p, p.scheme.president(), member));
        assertThat(e.path("canDelete").asBoolean()).isTrue();
        assertThat(e.path("deletionBlockedReason").isNull()).isTrue();
        assertThat(e.path("historyBlockers")).isEmpty();

        MvcResult deleted = hardDelete(p, p.scheme.president(), member);
        assertThat(status(deleted)).as(deleted.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.count("select count(*) from users where id = ?", member)).isZero();
        assertThat(p.count("select count(*) from cooperative_memberships where user_id = ?", member)).isZero();
        assertThat(p.count("select count(*) from audit_logs where cooperative_id = ? and action = 'MEMBER_DELETE' and entity_id = ?", p.id(), member))
                .as("the deletion itself stays on record, with no personal data").isEqualTo(1);
        assertThat(status(eligibilityOf(p, p.scheme.president(), member))).as("gone: indistinguishable from unknown").isEqualTo(404);
    }

    @Test
    void anOfficerWhoReviewedAPaymentLeavesRecordsAndIsDeactivatedNotDeletedSoHistoryStillNamesThem() throws Exception {
        PaymentScenario p = scheme("ACCUMULATING");
        p.row(NOW);
        p.posted("30000", contribution(NOW, "30000"));
        Actor officer = p.reviewer;

        JsonNode e = p.data(eligibilityOf(p, p.scheme.president(), officer.id()));
        assertThat(e.path("canDelete").asBoolean()).isFalse();
        assertThat(e.path("deletionBlockedReason").asText()).isEqualTo("MEMBER_HAS_ACTIVITY_RECORDS");
        assertThat(e.path("historyBlockers").toString()).contains("member_payments");
        assertThat(e.path("canDeactivate").asBoolean()).isTrue();
        assertThat(status(hardDelete(p, p.scheme.president(), officer.id()))).isEqualTo(409);
        assertThat(p.count("select count(*) from users where id = ?", officer.id())).isEqualTo(1);

        assertThat(status(deactivate(p, p.scheme.president(), officer.id(), "Left the committee"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from member_payments where review_started_by = ?", Long.class, officer.id()))
                .as("the payment still says who reviewed it").isEqualTo(1);
        // a deactivated officer no longer has access to the scheme
        assertThat(status(p.getAs(officer, "/payments/review-queue"))).isEqualTo(403);
    }

    @Test
    void aMemberWhoOnlySignedInLeftNothingBehindAndMayBeDeleted() throws Exception {
        PaymentScenario p = scheme("ACCUMULATING");
        Actor signedIn = p.addMember(0);
        JsonNode e = p.data(eligibilityOf(p, p.scheme.president(), signedIn.id()));
        assertThat(e.path("canDelete").asBoolean()).as("signing in alone is not history").isTrue();
        assertThat(status(hardDelete(p, p.scheme.president(), signedIn.id()))).isEqualTo(200);
        assertThat(p.count("select count(*) from users where id = ?", signedIn.id())).isZero();
    }

    // ============================================================ with history: deactivation only

    @Test
    void aMemberWithAPostedContributionCannotBeDeletedButCanBeDeactivatedAndAllHistoryStays() throws Exception {
        PaymentScenario p = scheme("ACCUMULATING");
        p.row(NOW);
        p.posted("30000", contribution(NOW, "30000"));
        BigDecimal funds = p.availableFunds();
        long ledger = p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id());

        JsonNode e = p.data(eligibilityOf(p, p.scheme.president(), p.payer.id()));
        assertThat(e.path("canDelete").asBoolean()).isFalse();
        assertThat(e.path("deletionBlockedReason").asText()).isEqualTo("MEMBER_HAS_FINANCIAL_HISTORY");
        assertThat(e.path("historyBlockers").toString()).contains("contributions").contains("member_payments").contains("financial_ledger");

        MvcResult refused = hardDelete(p, p.scheme.president(), p.payer.id());
        assertThat(status(refused)).isEqualTo(409);
        assertThat(p.code(refused)).isEqualTo("MEMBER_HAS_FINANCIAL_HISTORY");
        assertThat(p.count("select count(*) from users where id = ?", p.payer.id())).isEqualTo(1);

        MvcResult done = deactivate(p, p.scheme.president(), p.payer.id(), "Left the group");
        assertThat(status(done)).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(membershipStatus(p, p.payer.id())).isEqualTo("INACTIVE");
        // every record is exactly as it was
        assertThat(p.paid(NOW)).isEqualByComparingTo("30000");
        assertThat(jdbc.queryForObject("select count(*) from member_payments where member_user_id = ? and status = 'POSTED'", Long.class, p.payer.id())).isEqualTo(1);
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ?", p.id())).isEqualTo(ledger);
        assertThat(p.availableFunds()).isEqualByComparingTo(funds);
        assertThat(p.count("select count(*) from audit_logs where cooperative_id = ? and action = 'MEMBER_DEACTIVATE' and entity_id = ?", p.id(), p.payer.id())).isEqualTo(1);
        // historical reports still include them
        JsonNode summary = p.data(p.getAs(p.scheme.president(), "/members/" + p.payer.id() + "/financial-summary"));
        assertThat(summary.path("regularContributions").decimalValue()).isEqualByComparingTo("30000");
        assertThat(p.data(p.getAs(p.reviewer, "/reports/model-summary")).path("postedCollections").decimalValue()).isEqualByComparingTo("30000");

        // they can no longer start new things
        MvcResult submit = p.submitRaw(p.payer, PaymentScenario.body("1000", contribution(NEXT, "1000")));
        assertThat(status(submit)).isIn(403, 422);
        // and can be brought back
        assertThat(status(reactivate(p, p.scheme.president(), p.payer.id()))).isEqualTo(200);
        assertThat(membershipStatus(p, p.payer.id())).isEqualTo("ACTIVE");
        assertThat(p.code(deactivate(p, p.scheme.president(), p.payer.id(), ""))).isEqualTo("REASON_REQUIRED");
    }

    @Test
    void deactivatingAMemberWithAnOutstandingLoanForgivesNothingAndTheLoanStaysRepayable() throws Exception {
        PaymentScenario p = scheme("ACCUMULATING");
        Loan loan = p.loan("100000", "5000");

        JsonNode e = p.data(eligibilityOf(p, p.scheme.president(), p.payer.id()));
        assertThat(e.path("canDelete").asBoolean()).isFalse();
        assertThat(e.path("impact").path("outstandingLoanPrincipal").decimalValue()).isEqualByComparingTo("100000");
        assertThat(e.path("impact").path("openLoans").asInt()).isEqualTo(1);

        assertThat(status(deactivate(p, p.scheme.president(), p.payer.id(), "Moved away"))).isEqualTo(200);
        Loan after = p.reload(loan);
        assertThat(after.getStatus().name()).isIn("ACTIVE", "OVERDUE");
        assertThat(after.getOutstandingPrincipal()).as("the debt did not disappear").isEqualByComparingTo("100000");
        assertThat(after.getOutstandingInterest()).isEqualByComparingTo("5000");

        // an officer can still record what the former member repays
        MvcResult repayment = mockMvc.perform(post(p.base() + "/loans/" + loan.getId() + "/repayments")
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 20000, \"paymentDate\": \"" + java.time.LocalDate.now() + "\"}"))
                .andReturn();
        assertThat(status(repayment)).as(repayment.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.reload(loan).getOutstandingPrincipal()).isEqualByComparingTo("85000");
        assertThat(p.data(eligibilityOf(p, p.scheme.president(), p.payer.id())).path("impact").path("outstandingLoanPrincipal").decimalValue())
                .isEqualByComparingTo("85000");
    }

    @Test
    void deactivatingAMemberKeepsTheirRotationObligationsAndABlockedPayoutRecipientCannotBeDeactivated() throws Exception {
        PaymentScenario p = scheme("ROTATING");
        for (int i = 1; i < 4; i++) {
            p.addMember(30);
        }
        p.configureRotation(NEXT, 10000, 0);
        p.activateCycle(NEXT, p.allMembers());
        p.rows(NEXT, p.allMembers());
        p.rows(NEXT.plusMonths(1), p.allMembers());
        Actor contributor = p.allMembers().get(2);

        JsonNode before = p.data(eligibilityOf(p, p.scheme.president(), contributor.id())).path("impact");
        assertThat(before.path("unpaidContributions").decimalValue()).isEqualByComparingTo("60000");
        assertThat(before.path("upcomingRecipientPeriods").asInt()).isEqualTo(1);

        assertThat(status(deactivate(p, p.scheme.president(), contributor.id(), "Left the group"))).isEqualTo(200);
        // the obligations are still on the books, and the member is still in the rotation view
        assertThat(p.count("select count(*) from contributions where cooperative_id = ? and member_user_id = ? and outstanding_amount > 0",
                p.id(), contributor.id())).isEqualTo(2);
        assertThat(p.data(eligibilityOf(p, p.scheme.president(), contributor.id())).path("impact").path("unpaidContributions").decimalValue())
                .isEqualByComparingTo("60000");
        JsonNode obligations = p.data(p.getAs(p.reviewer, "/rotation/obligations/members/" + contributor.id()));
        assertThat(obligations.path("remainingScheduled").decimalValue().add(obligations.path("overdue").decimalValue()))
                .isEqualByComparingTo("60000");
        assertThat(p.count("select count(*) from rotation_periods where cooperative_id = ? and scheduled_recipient_user_id = ?", p.id(), contributor.id()))
                .as("their position in the schedule is not deleted").isEqualTo(1);

        // a recipient whose payout is in flight cannot be deactivated until it is settled
        for (Actor member : p.allMembers()) {
            if (!member.id().equals(contributor.id())) {
                p.postedFor(member, "30000", contribution(NEXT, "30000"));
            }
        }
        Actor firstRecipient = p.allMembers().get(0);
        assertThat(status(p.requestPayout(p.reviewer, p.period(NEXT), "90000"))).isEqualTo(200);
        JsonNode inFlight = p.data(eligibilityOf(p, p.scheme.president(), firstRecipient.id()));
        assertThat(inFlight.path("canDeactivate").asBoolean()).isFalse();
        assertThat(inFlight.path("deactivationBlockedReason").asText()).isEqualTo("MEMBER_HAS_OPEN_PAYOUT");
        MvcResult blocked = deactivate(p, p.scheme.president(), firstRecipient.id(), "Left the group");
        assertThat(status(blocked)).isEqualTo(409);
        assertThat(p.code(blocked)).isEqualTo("MEMBER_HAS_OPEN_PAYOUT");
        assertThat(membershipStatus(p, firstRecipient.id())).isEqualTo("ACTIVE");
    }

    @Test
    void theGenericStatusEndpointCannotBeUsedToBypassTheDeactivationRules() throws Exception {
        PaymentScenario p = scheme("ACCUMULATING");
        MvcResult viaPatch = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(p.base() + "/members/" + p.payer.id() + "/status")
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipStatus\":\"INACTIVE\"}"))
                .andReturn();
        assertThat(status(viaPatch)).isEqualTo(400);
        assertThat(p.code(viaPatch)).isEqualTo("USE_MEMBER_DEACTIVATE");
        assertThat(membershipStatus(p, p.payer.id())).isEqualTo("ACTIVE");
        // the other transitions the endpoint always offered still work
        MvcResult suspend = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(p.base() + "/members/" + p.payer.id() + "/status")
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipStatus\":\"SUSPENDED\"}"))
                .andReturn();
        assertThat(status(suspend)).isEqualTo(200);
    }

    // ============================================================ who may, and where

    @Test
    void onlyLeadershipMayRemoveMembersNotAnAccountantNotAMemberAndNobodyMayRemoveThemselves() throws Exception {
        PaymentScenario p = scheme("ACCUMULATING");
        UUID member = createUnusedMember(p);
        Actor other = p.addMember(30);

        for (Actor who : new Actor[] {p.reviewer, p.verifier, other, p.payer}) {
            assertThat(status(eligibilityOf(p, who, member))).isEqualTo(403);
            assertThat(status(deactivate(p, who, member, "Not allowed to"))).isEqualTo(403);
            assertThat(status(hardDelete(p, who, member))).isEqualTo(403);
        }
        assertThat(p.count("select count(*) from users where id = ?", member)).isEqualTo(1);
        assertThat(membershipStatus(p, member)).isEqualTo("ACTIVE");

        MvcResult self = deactivate(p, p.scheme.president(), p.scheme.president().id(), "Stepping away");
        assertThat(status(self)).isEqualTo(409);
        assertThat(p.code(self)).isEqualTo("CANNOT_REMOVE_SELF");
        assertThat(p.code(hardDelete(p, p.scheme.president(), p.scheme.president().id()))).isEqualTo("CANNOT_REMOVE_SELF");
    }

    @Test
    void anOfficerOfAnotherSchemeCannotRemoveOrEvenSeeAMemberAndNothingRevealsWhetherTheyExist() throws Exception {
        PaymentScenario a = scheme("ACCUMULATING");
        PaymentScenario b = scheme("ACCUMULATING");
        UUID memberOfB = createUnusedMember(b);

        // A's president aims at B's member through A's own scheme: not found, as if the member did not exist
        MvcResult viaOwnScheme = hardDelete(a, a.scheme.president(), memberOfB);
        assertThat(status(viaOwnScheme)).isEqualTo(404);
        assertThat(status(deactivate(a, a.scheme.president(), memberOfB, "Not theirs to remove"))).isEqualTo(404);
        assertThat(status(eligibilityOf(a, a.scheme.president(), memberOfB))).isEqualTo(404);
        // ...and the same answer for an id that does not exist anywhere
        assertThat(status(hardDelete(a, a.scheme.president(), UUID.randomUUID()))).isEqualTo(404);

        // through B's scheme, A's president is not a member of it at all
        assertThat(status(hardDelete(b, a.scheme.president(), memberOfB))).isEqualTo(403);
        assertThat(status(deactivate(b, a.scheme.president(), memberOfB, "Not theirs to remove"))).isEqualTo(403);

        assertThat(b.count("select count(*) from users where id = ?", memberOfB)).isEqualTo(1);
        assertThat(membershipStatus(b, memberOfB)).isEqualTo("ACTIVE");
    }
}
