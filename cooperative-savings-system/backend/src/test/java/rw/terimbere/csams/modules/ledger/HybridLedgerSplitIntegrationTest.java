package rw.terimbere.csams.modules.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rw.terimbere.csams.testsupport.PaymentScenario.NEXT;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * A hybrid scheme's contribution is one cash movement with two purposes. The ledger keeps them apart (rotating: owed to
 * the period's recipient; retained: member capital) and the two parts always add up to the entry, so no cash is
 * duplicated. The reconciliation proves it against the contributions themselves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HybridLedgerSplitIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    /** A hybrid scheme split 40% rotating / 60% retained, with {@code members} members of 30 shares (30,000 a month). */
    private PaymentScenario hybrid(int members) throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "HYBRID", 30);
        List<Actor> all = new ArrayList<>(List.of(p.payer));
        for (int i = 1; i < members; i++) {
            all.add(p.addMember(30));
        }
        p.configureRotation(NEXT, 4000, 6000);
        p.activateCycle(NEXT, all);
        p.rows(NEXT, all);
        return p;
    }

    @Test
    void aPaymentOf30000AtFortySixtyIsFiled12000RotatingAnd18000RetainedInTheLedger() throws Exception {
        PaymentScenario p = hybrid(1);
        p.posted("30000", contribution(NEXT, "30000"));

        var live = liveEntries(p);
        assertThat(live).hasSize(1);
        assertThat((BigDecimal) live.get(0).get("credit_amount")).isEqualByComparingTo("30000");
        assertThat((BigDecimal) live.get(0).get("rotating_amount")).isEqualByComparingTo("12000");
        assertThat((BigDecimal) live.get(0).get("retained_amount")).isEqualByComparingTo("18000");

        JsonNode recon = reconciliation(p);
        assertThat(recon.path("ledgerRotating").decimalValue()).isEqualByComparingTo("12000");
        assertThat(recon.path("ledgerRetained").decimalValue()).isEqualByComparingTo("18000");
        assertThat(recon.path("cashDifference").decimalValue()).isEqualByComparingTo("0");
        assertThat(recon.path("balanced").asBoolean()).isTrue();
    }

    @Test
    void fiveMembersPaying30000EachGive60000RotatingAnd90000RetainedOutOf150000() throws Exception {
        PaymentScenario p = hybrid(5);
        assertThat(p.allMembers()).hasSize(5);
        for (Actor member : p.allMembers()) {
            p.postedFor(member, "30000", contribution(NEXT, "30000"));
        }

        JsonNode recon = reconciliation(p);
        assertThat(recon.path("ledgerRotating").decimalValue()).isEqualByComparingTo("60000");
        assertThat(recon.path("ledgerRetained").decimalValue()).isEqualByComparingTo("90000");
        assertThat(recon.path("ledgerTotal").decimalValue()).isEqualByComparingTo("150000");
        assertThat(recon.path("contributionsPaid").decimalValue()).isEqualByComparingTo("150000");
        assertThat(recon.path("derivedRotating").decimalValue()).isEqualByComparingTo("60000");
        assertThat(recon.path("derivedRetained").decimalValue()).isEqualByComparingTo("90000");
        assertThat(recon.path("balanced").asBoolean()).isTrue();
        // the cash is counted once: rotating + retained is the ledger total, not more
        assertThat(recon.path("ledgerRotating").decimalValue().add(recon.path("ledgerRetained").decimalValue()))
                .isEqualByComparingTo(recon.path("ledgerTotal").decimalValue());
    }

    @Test
    void partialPaymentsKeepTheLedgerSplitEqualToTheSplitOfTheTotalPaid() throws Exception {
        PaymentScenario p = hybrid(1);
        p.posted("10001", contribution(NEXT, "10001"));
        p.posted("19999", contribution(NEXT, "19999"));

        var live = liveEntries(p);
        assertThat(live).as("one live entry: the running total").hasSize(1);
        assertThat((BigDecimal) live.get(0).get("credit_amount")).isEqualByComparingTo("30000");
        assertThat((BigDecimal) live.get(0).get("rotating_amount")).isEqualByComparingTo("12000");
        assertThat((BigDecimal) live.get(0).get("retained_amount")).isEqualByComparingTo("18000");
        assertThat(p.count("select count(*) from financial_ledger where cooperative_id = ? and status = 'REVERSED'", p.id()))
                .as("the first running total was reversed, not edited").isEqualTo(1);
        assertThat(reconciliation(p).path("balanced").asBoolean()).isTrue();
    }

    @Test
    void reversingThePaymentTakesBackBothPartsWithTheReversalCarryingTheSameSplit() throws Exception {
        PaymentScenario p = hybrid(1);
        UUID payment = p.posted("30000", contribution(NEXT, "30000"));
        UUID reversal = p.requestReversal(p.reviewer, payment, "Wrong amount");
        MvcResult approved = p.decide(p.scheme.president(), reversal, "approve");
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);

        assertThat(liveEntries(p)).isEmpty();
        var reversalEntries = jdbc.queryForList(
                "select * from financial_ledger where cooperative_id = ? and transaction_type = 'REVERSAL'", p.id());
        assertThat(reversalEntries).hasSize(1);
        assertThat((BigDecimal) reversalEntries.get(0).get("debit_amount")).isEqualByComparingTo("30000");
        assertThat((BigDecimal) reversalEntries.get(0).get("rotating_amount")).isEqualByComparingTo("12000");
        assertThat((BigDecimal) reversalEntries.get(0).get("retained_amount")).isEqualByComparingTo("18000");

        JsonNode recon = reconciliation(p);
        assertThat(recon.path("ledgerRotating").decimalValue()).isEqualByComparingTo("0");
        assertThat(recon.path("ledgerRetained").decimalValue()).isEqualByComparingTo("0");
        assertThat(recon.path("balanced").asBoolean()).isTrue();
    }

    @Test
    void anAccumulatingSchemeIsEntirelyRetainedAndARotatingOneEntirelyRotating() throws Exception {
        PaymentScenario acc = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        acc.row(NEXT);
        acc.posted("30000", contribution(NEXT, "30000"));
        var accEntry = liveEntries(acc).get(0);
        assertThat((BigDecimal) accEntry.get("rotating_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) accEntry.get("retained_amount")).isEqualByComparingTo("30000");

        PaymentScenario rot = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ROTATING", 30);
        rot.configureRotation(NEXT, 10000, 0);
        rot.activateCycle(NEXT, List.of(rot.payer));
        rot.rows(NEXT, List.of(rot.payer));
        rot.posted("30000", contribution(NEXT, "30000"));
        var rotEntry = liveEntries(rot).get(0);
        assertThat((BigDecimal) rotEntry.get("rotating_amount")).isEqualByComparingTo("30000");
        assertThat((BigDecimal) rotEntry.get("retained_amount")).isEqualByComparingTo("0");
    }

    @Test
    void entriesFromBeforeTheSplitAreReportedUnclassifiedNotGuessedAndCashStillAgrees() throws Exception {
        PaymentScenario p = hybrid(1);
        p.posted("30000", contribution(NEXT, "30000"));
        jdbc.update("update financial_ledger set rotating_amount = null, retained_amount = null where cooperative_id = ?", p.id());

        JsonNode recon = reconciliation(p);
        assertThat(recon.path("unclassifiedEntries").asInt()).isEqualTo(1);
        assertThat(recon.path("ledgerUnclassified").decimalValue()).isEqualByComparingTo("30000");
        assertThat(recon.path("ledgerRotating").decimalValue()).isEqualByComparingTo("0");
        assertThat(recon.path("derivedRotating").decimalValue()).as("derived from the contribution itself").isEqualByComparingTo("12000");
        assertThat(recon.path("cashDifference").decimalValue()).isEqualByComparingTo("0");
        assertThat(recon.path("balanced").asBoolean()).isTrue();
    }

    @Test
    void entriesFromBeforeTheSplitAndNewClassifiedOnesReconcileTogetherWithoutCountingAnythingTwice() throws Exception {
        PaymentScenario p = hybrid(2);
        Actor first = p.allMembers().get(0);
        Actor second = p.allMembers().get(1);
        p.postedFor(first, "30000", contribution(NEXT, "30000"));
        // the first entry was written before the split existed
        jdbc.update("update financial_ledger set rotating_amount = null, retained_amount = null where cooperative_id = ?", p.id());
        // a later change of the scheme's split must not re-split the old money
        p.configureRotation(NEXT.plusMonths(3), 8000, 2000);
        p.postedFor(second, "30000", contribution(NEXT, "30000"));

        JsonNode recon = reconciliation(p);
        assertThat(recon.path("ledgerTotal").decimalValue()).as("contribution cash reconciles").isEqualByComparingTo("60000");
        assertThat(recon.path("contributionsPaid").decimalValue()).isEqualByComparingTo("60000");
        assertThat(recon.path("cashDifference").decimalValue()).isEqualByComparingTo("0");
        // only the classified entry is in the retained and rotating totals, at the split frozen on its contribution (40/60)
        assertThat(recon.path("ledgerRotating").decimalValue()).isEqualByComparingTo("12000");
        assertThat(recon.path("ledgerRetained").decimalValue()).isEqualByComparingTo("18000");
        // the old entry is shown on its own, never as retained and rotating at once
        assertThat(recon.path("ledgerUnclassified").decimalValue()).isEqualByComparingTo("30000");
        assertThat(recon.path("unclassifiedEntries").asInt()).isEqualTo(1);
        assertThat(recon.path("ledgerRotating").decimalValue()
                        .add(recon.path("ledgerRetained").decimalValue())
                        .add(recon.path("ledgerUnclassified").decimalValue()))
                .as("classified + unclassified is the whole, nothing is counted twice")
                .isEqualByComparingTo(recon.path("ledgerTotal").decimalValue());
        // what the contributions themselves say (their own frozen split) for both rows
        assertThat(recon.path("derivedRotating").decimalValue()).isEqualByComparingTo("24000");
        assertThat(recon.path("derivedRetained").decimalValue()).isEqualByComparingTo("36000");
        assertThat(recon.path("mismatches")).isEmpty();
        assertThat(recon.path("balanced").asBoolean()).isTrue();
    }

    @Test
    void aSplitThatDisagreesWithTheContributionOrWithTheCashIsFlagged() throws Exception {
        PaymentScenario p = hybrid(1);
        p.posted("30000", contribution(NEXT, "30000"));
        // a split that adds up to the entry (so the database accepts it) but is not the contribution's split
        jdbc.update("update financial_ledger set rotating_amount = 20000, retained_amount = 10000 where cooperative_id = ? and status = 'APPROVED' and transaction_type = 'REGULAR_CONTRIBUTION'", p.id());
        JsonNode recon = reconciliation(p);
        assertThat(recon.path("balanced").asBoolean()).isFalse();
        assertThat(recon.path("mismatches").get(0).path("reason").asText()).isEqualTo("SPLIT_DIFFERS_FROM_CONTRIBUTION");

        // and cash that was altered behind the contribution's back
        jdbc.update("update contributions set paid_amount = 20000 where cooperative_id = ?", p.id());
        JsonNode drift = reconciliation(p);
        assertThat(drift.path("cashDifference").decimalValue()).isEqualByComparingTo("10000");
        assertThat(drift.path("balanced").asBoolean()).isFalse();
    }

    @Test
    void theDatabaseRefusesASplitThatAddsOrLosesCash() throws Exception {
        PaymentScenario p = hybrid(1);
        p.posted("30000", contribution(NEXT, "30000"));
        assertThatThrownBy(() -> jdbc.update(
                        "update financial_ledger set rotating_amount = 20000, retained_amount = 20000 where cooperative_id = ? and status = 'APPROVED' and transaction_type = 'REGULAR_CONTRIBUTION'",
                        p.id()))
                .as("rotating + retained more than the entry")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update financial_ledger set rotating_amount = 10000, retained_amount = null where cooperative_id = ? and status = 'APPROVED' and transaction_type = 'REGULAR_CONTRIBUTION'",
                        p.id()))
                .as("one part without the other")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update financial_ledger set rotating_amount = -1000, retained_amount = 31000 where cooperative_id = ? and status = 'APPROVED' and transaction_type = 'REGULAR_CONTRIBUTION'",
                        p.id()))
                .as("a negative part")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theReconciliationBelongsToTheSchemeAndNeedsLedgerAccess() throws Exception {
        PaymentScenario p = hybrid(1);
        PaymentScenario other = hybrid(1);
        assertThat(status(p.getAs(other.scheme.president(), "/ledger/split-reconciliation"))).as("another scheme's officer").isEqualTo(403);
        assertThat(status(p.getAs(p.scheme.memberB(), "/ledger/split-reconciliation"))).as("a plain member").isEqualTo(403);
        assertThat(status(p.getAs(p.scheme.president(), "/ledger/split-reconciliation"))).isEqualTo(200);
    }

    // ------------------------------------------------------------------ helpers

    private List<java.util.Map<String, Object>> liveEntries(PaymentScenario p) {
        return jdbc.queryForList(
                "select * from financial_ledger where cooperative_id = ? and transaction_type = 'REGULAR_CONTRIBUTION' and status = 'APPROVED'",
                p.id());
    }

    private JsonNode reconciliation(PaymentScenario p) throws Exception {
        MvcResult result = p.getAs(p.scheme.president(), "/ledger/split-reconciliation");
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return p.data(result);
    }
}
