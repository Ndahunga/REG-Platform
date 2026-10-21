package rw.terimbere.csams.modules.rotation.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.terimbere.csams.testsupport.PaymentScenario.NEXT;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * A member who is paid out early keeps contributing for the months that remain. The view shows what was paid in, what
 * was received, what is still to pay and what is overdue, and keeps the advance (received ahead of contributing) apart
 * without ever adding it to what is owed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RotationRecipientObligationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    /** Four members of 30 shares (30,000 each); rows for the first two months; the first member is paid out first. */
    private PaymentScenario rotating(String model, int rotatingBps) throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, model, 30);
        for (int i = 1; i < 4; i++) {
            p.addMember(30);
        }
        p.configureRotation(NEXT, rotatingBps, 10000 - rotatingBps);
        p.activateCycle(NEXT, p.allMembers());
        p.rows(NEXT, p.allMembers());
        p.rows(NEXT.plusMonths(1), p.allMembers());
        return p;
    }

    private void everyonePaysAndTheFirstRecipientIsPaid(PaymentScenario p) throws Exception {
        for (Actor member : p.allMembers()) {
            p.postedFor(member, "30000", contribution(NEXT, "30000"));
        }
        MvcResult requested = p.requestPayout(p.reviewer, p.period(NEXT), "120000");
        assertThat(status(requested)).as(requested.getResponse().getContentAsString()).isEqualTo(200);
        p.approveAndDisburse(UUID.fromString(p.data(requested).path("id").asText()), "BK-FIRST");
    }

    @Test
    void anEarlyRecipientShowsPaidReceivedRemainingNextDeadlineAndTheAdvanceApart() throws Exception {
        PaymentScenario p = rotating("ROTATING", 10000);
        everyonePaysAndTheFirstRecipientIsPaid(p);
        Actor first = p.allMembers().get(0);

        JsonNode me = obligation(p, first);
        assertThat(dec(me, "paidContributions")).isEqualByComparingTo("30000");
        assertThat(dec(me, "paidRotating")).isEqualByComparingTo("30000");
        assertThat(dec(me, "verifiedRotating")).isEqualByComparingTo("30000");
        assertThat(dec(me, "payoutReceived")).isEqualByComparingTo("120000");
        assertThat(dec(me, "remainingScheduled")).as("next month's 30,000 is still to pay").isEqualByComparingTo("30000");
        assertThat(dec(me, "overdue")).isEqualByComparingTo("0");
        assertThat(me.path("nextDeadline").asText()).isEqualTo(LocalDate.of(NEXT.plusMonths(1).getYear(), NEXT.plusMonths(1).getMonthValue(), 1).toString());
        assertThat(dec(me, "rotationalAdvance")).as("received 120,000 having contributed 30,000").isEqualByComparingTo("90000");
        assertThat(me.path("paidOutAndStillContributing").asBoolean()).isTrue();
        assertThat(me.path("periodsWithoutObligation").asInt()).as("the third and fourth months have no row yet").isEqualTo(2);
        assertThat(me.path("recipientPeriods")).hasSize(1);

        // the advance is a view of the same money: it is NOT added to what is owed
        assertThat(dec(me, "remainingScheduled").add(dec(me, "overdue"))).isEqualByComparingTo("30000");

        // the last recipient has not been paid: no advance, same contributions to make
        JsonNode last = obligation(p, p.allMembers().get(3));
        assertThat(dec(last, "payoutReceived")).isEqualByComparingTo("0");
        assertThat(dec(last, "rotationalAdvance")).isEqualByComparingTo("0");
        assertThat(last.path("paidOutAndStillContributing").asBoolean()).isFalse();
        assertThat(dec(last, "remainingScheduled")).isEqualByComparingTo("30000");
    }

    @Test
    void aMissedDeadlineMovesTheAmountFromRemainingToOverdueAndTheAdvanceShrinksAsTheMemberPaysOn() throws Exception {
        PaymentScenario p = rotating("ROTATING", 10000);
        everyonePaysAndTheFirstRecipientIsPaid(p);
        Actor first = p.allMembers().get(0);

        jdbc.update("update contributions set due_date = ? where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                LocalDate.now().minusDays(1), p.id(), first.id(), NEXT.plusMonths(1).getYear(), NEXT.plusMonths(1).getMonthValue());
        JsonNode overdue = obligation(p, first);
        assertThat(dec(overdue, "overdue")).isEqualByComparingTo("30000");
        assertThat(dec(overdue, "remainingScheduled")).isEqualByComparingTo("0");
        assertThat(overdue.path("nextDeadline").isNull()).isTrue();
        assertThat(dec(overdue, "rotationalAdvance")).isEqualByComparingTo("90000");

        p.postedFor(first, "30000", contribution(NEXT.plusMonths(1), "30000"));
        JsonNode paidOn = obligation(p, first);
        assertThat(dec(paidOn, "overdue")).isEqualByComparingTo("0");
        assertThat(dec(paidOn, "paidRotating")).isEqualByComparingTo("60000");
        assertThat(dec(paidOn, "rotationalAdvance")).as("120,000 received less 60,000 contributed so far").isEqualByComparingTo("60000");
        assertThat(paidOn.path("paidOutAndStillContributing").asBoolean()).as("nothing left on their rows").isFalse();
    }

    @Test
    void inAHybridSchemeOnlyTheRotatingPartCountsTowardsTheAdvance() throws Exception {
        PaymentScenario p = rotating("HYBRID", 4000);
        for (Actor member : p.allMembers()) {
            p.postedFor(member, "30000", contribution(NEXT, "30000"));
        }
        MvcResult requested = p.requestPayout(p.reviewer, p.period(NEXT), "48000");
        assertThat(status(requested)).as(requested.getResponse().getContentAsString()).isEqualTo(200);
        p.approveAndDisburse(UUID.fromString(p.data(requested).path("id").asText()), "BK-HYB");
        JsonNode me = obligation(p, p.allMembers().get(0));
        assertThat(dec(me, "paidContributions")).isEqualByComparingTo("30000");
        assertThat(dec(me, "paidRotating")).as("40% of 30,000").isEqualByComparingTo("12000");
        assertThat(dec(me, "payoutReceived")).isEqualByComparingTo("48000");
        assertThat(dec(me, "rotationalAdvance")).isEqualByComparingTo("36000");
    }

    @Test
    void membersSeeOnlyTheirOwnPositionAndOfficersSeeEveryoneWithinTheirScheme() throws Exception {
        PaymentScenario p = rotating("ROTATING", 10000);
        PaymentScenario other = rotating("ROTATING", 10000);
        Actor first = p.allMembers().get(0);
        Actor second = p.allMembers().get(1);

        MvcResult mine = p.getAs(first, "/rotation/obligations/mine");
        assertThat(status(mine)).isEqualTo(200);
        assertThat(p.data(mine).path("memberUserId").asText()).isEqualTo(first.id().toString());
        assertThat(status(p.getAs(first, "/rotation/obligations/members/" + second.id()))).as("another member's position").isEqualTo(403);
        assertThat(status(p.getAs(first, "/rotation/obligations"))).isEqualTo(403);

        MvcResult all = p.getAs(p.reviewer, "/rotation/obligations");
        assertThat(status(all)).isEqualTo(200);
        assertThat(p.data(all)).hasSize(4);
        assertThat(status(p.getAs(other.scheme.president(), "/rotation/obligations"))).as("another scheme's officer").isEqualTo(403);
        assertThat(status(p.getAs(other.reviewer, "/rotation/obligations/members/" + first.id()))).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode obligation(PaymentScenario p, Actor member) throws Exception {
        MvcResult result = p.getAs(p.reviewer, "/rotation/obligations/members/" + member.id());
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return p.data(result);
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.path(field).decimalValue();
    }
}
