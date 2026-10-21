package rw.terimbere.csams.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/** Phase 4: the model-aware financial report, from posted records only, with distribution unavailable pending policy. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModelFinancialReportIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LedgerService ledgerService;

    private SchemeFixtures fixtures;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
    }

    @Test
    void anAccumulatingSchemeReportsContributionsLoansEarningsAndSharesAndNoRotationOrHybridBlocks() throws Exception {
        Scheme s = fixtures.scheme("ACCUMULATING");
        ledger(s, LedgerTransactionType.REGULAR_CONTRIBUTION, "1000", true);
        ledger(s, LedgerTransactionType.LOAN_DISBURSEMENT, "400", false);
        ledger(s, LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT, "400", true);
        ledger(s, LedgerTransactionType.LOAN_INTEREST_PAYMENT, "300", true);
        ledger(s, LedgerTransactionType.FINE_PAYMENT, "100", true);
        ledger(s, LedgerTransactionType.OTHER_INCOME, "50", true);
        ledger(s, LedgerTransactionType.GENERAL_EXPENSE, "120", false);

        JsonNode report = report(s, s.accountantA(), "");

        assertThat(report.path("operatingModel").asText()).isEqualTo("ACCUMULATING");
        assertThat(dec(report.path("movements"), "contributionsCollected")).isEqualByComparingTo("1000");
        assertThat(dec(report.path("movements"), "loansDisbursed")).isEqualByComparingTo("400");
        assertThat(dec(report.path("movements"), "principalRepaid")).isEqualByComparingTo("400");
        JsonNode earnings = report.path("earnings");
        assertThat(dec(earnings, "interestIncome")).isEqualByComparingTo("300");
        assertThat(dec(earnings, "totalIncome")).isEqualByComparingTo("450");
        assertThat(dec(earnings, "totalExpenses")).isEqualByComparingTo("120");
        assertThat(dec(earnings, "netEarnings")).isEqualByComparingTo("330");
        // no distribution policy is approved: nothing is distributed or calculated as a dividend
        assertThat(earnings.path("distributionStatus").asText()).isEqualTo("UNAVAILABLE_PENDING_POLICY");
        assertThat(earnings.has("dividend")).isFalse();
        assertThat(report.path("shares").path("members").asInt()).isEqualTo(5);
        assertThat(report.path("rotation").isNull()).isTrue();
        assertThat(report.path("hybrid").isNull()).isTrue();
        assertThat(dec(report.path("position"), "availableFunds")).as("the report agrees with the ledger").isEqualByComparingTo("1330");
        assertThat(report.path("notes").toString()).contains("UNAVAILABLE_PENDING_POLICY");
    }

    @Test
    void theReportCoversOnlyTheRequestedPeriodAndRefusesAnInvertedRange() throws Exception {
        Scheme s = fixtures.scheme("ACCUMULATING");
        ledgerDated(s, LedgerTransactionType.OTHER_INCOME, "70", true, LocalDate.of(2023, 6, 1));
        ledgerDated(s, LedgerTransactionType.OTHER_INCOME, "30", true, LocalDate.of(2024, 6, 1));

        assertThat(dec(report(s, s.accountantA(), "?from=2023-01-01&to=2023-12-31").path("earnings"), "otherIncome")).isEqualByComparingTo("70");
        assertThat(dec(report(s, s.accountantA(), "?from=2024-01-01&to=2024-12-31").path("earnings"), "otherIncome")).isEqualByComparingTo("30");
        assertThat(dec(report(s, s.accountantA(), "").path("earnings"), "otherIncome")).isEqualByComparingTo("100");
        MvcResult inverted = mockMvc.perform(get(base(s) + "?from=2025-01-01&to=2024-01-01").header("Authorization", bearer(s.accountantA()))).andReturn();
        assertThat(inverted.getResponse().getStatus()).isIn(400, 422);
    }

    @Test
    void aHybridSchemeShowsRetainedAndRotatingPortionsAndOnlyAnIndicativeEarningsAttribution() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        ledger(s, LedgerTransactionType.REGULAR_CONTRIBUTION, "1000", true);
        ledger(s, LedgerTransactionType.LOAN_INTEREST_PAYMENT, "200", true);
        // 1000 of retained capital, stated as such: a contribution recorded as accumulating
        jdbc.update("""
                insert into contributions (id, cooperative_id, member_user_id, "year", "month", expected_amount, paid_amount,
                  outstanding_amount, status, ledger_revision, obligation_snapshot_at, operating_model_snapshot, created_at, updated_at, version)
                values (?, ?, ?, 2024, 1, 1000, 1000, 0, 'PAID', 1, now(), 'ACCUMULATING', now(), now(), 0)
                """, UUID.randomUUID(), s.id(), s.memberA().id());

        JsonNode report = report(s, s.accountantA(), "");

        assertThat(report.path("rotation").isNull()).isFalse();
        JsonNode hybrid = report.path("hybrid");
        assertThat(dec(hybrid, "retainedCapital")).isEqualByComparingTo("1000");
        assertThat(dec(hybrid, "rotatingHeld")).isEqualByComparingTo("0");
        assertThat(hybrid.path("retainedCapitalShareBps").asInt()).isEqualTo(10000);
        assertThat(hybrid.path("attributionBasis").asText()).isEqualTo("PROPORTIONAL_TO_CAPITAL_INDICATIVE_ONLY");
        assertThat(dec(hybrid, "earningsAttributableToRetained")).isEqualByComparingTo("200");
        assertThat(report.path("earnings").path("distributionStatus").asText()).isEqualTo("UNAVAILABLE_PENDING_POLICY");
    }

    @Test
    void aHybridSchemeHoldingUnresolvedRotatingMoneyAttributesNothingToRetainedCapitalForIt() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        ledger(s, LedgerTransactionType.REGULAR_CONTRIBUTION, "3000", true);
        ledger(s, LedgerTransactionType.OTHER_INCOME, "300", true);
        // 1000 retained, 2000 known to be rotating but with no period: held, not capital
        jdbc.update("""
                insert into contributions (id, cooperative_id, member_user_id, "year", "month", expected_amount, paid_amount,
                  outstanding_amount, status, ledger_revision, obligation_snapshot_at, operating_model_snapshot, created_at, updated_at, version)
                values (?, ?, ?, 2024, 1, 1000, 1000, 0, 'PAID', 1, now(), 'ACCUMULATING', now(), now(), 0)
                """, UUID.randomUUID(), s.id(), s.memberA().id());
        jdbc.update("""
                insert into contributions (id, cooperative_id, member_user_id, "year", "month", expected_amount, paid_amount,
                  outstanding_amount, status, ledger_revision, obligation_snapshot_at, operating_model_snapshot, created_at, updated_at, version)
                values (?, ?, ?, 2024, 2, 2000, 2000, 0, 'PAID', 1, now(), 'ROTATING', now(), now(), 0)
                """, UUID.randomUUID(), s.id(), s.memberA().id());

        JsonNode report = report(s, s.accountantA(), "");

        JsonNode hybrid = report.path("hybrid");
        assertThat(dec(hybrid, "retainedCapital")).isEqualByComparingTo("1000");
        assertThat(dec(hybrid, "rotatingHeld")).isEqualByComparingTo("2000");
        assertThat(hybrid.path("retainedCapitalShareBps").asInt()).isEqualTo(3333);
        assertThat(dec(report.path("rotation"), "unresolvedRotating")).isEqualByComparingTo("2000");
        assertThat(dec(report.path("rotation"), "verifiedCollected")).isEqualByComparingTo("0");
        assertThat(report.path("notes").toString()).contains("held, not payable");
    }

    @Test
    void aRotatingSchemeReportsRotationAndNeverTheHybridBlock() throws Exception {
        Scheme s = fixtures.scheme("ROTATING");
        JsonNode report = report(s, s.accountantA(), "");
        assertThat(report.path("operatingModel").asText()).isEqualTo("ROTATING");
        assertThat(report.path("rotation").isNull()).isFalse();
        assertThat(report.path("rotation").path("periods").isArray()).isTrue();
        assertThat(report.path("hybrid").isNull()).isTrue();
        assertThat(dec(report.path("rotation"), "unpaidEntitlements")).isEqualByComparingTo("0");
    }

    @Test
    void theReportIsForOfficersOfTheSchemeOnly() throws Exception {
        Scheme s = fixtures.scheme("ACCUMULATING");
        Scheme other = fixtures.scheme("ACCUMULATING");
        assertThat(mockMvc.perform(get(base(s)).header("Authorization", bearer(s.memberA()))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get(base(s)).header("Authorization", bearer(other.accountantA()))).andReturn().getResponse().getStatus()).isIn(403, 404);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode report(Scheme s, Actor who, String query) throws Exception {
        MvcResult result = mockMvc.perform(get(base(s) + query).header("Authorization", bearer(who))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void ledger(Scheme s, LedgerTransactionType type, String amount, boolean credit) {
        ledgerDated(s, type, amount, credit, LocalDate.now());
    }

    private void ledgerDated(Scheme s, LedgerTransactionType type, String amount, boolean credit, LocalDate date) {
        UUID admin = fixtures.superAdminId();
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(s.id())
                .memberUserId(s.memberA().id())
                .transactionType(type)
                .debitAmount(credit ? BigDecimal.ZERO : new BigDecimal(amount))
                .creditAmount(credit ? new BigDecimal(amount) : BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(date)
                .reference("T-" + UUID.randomUUID().toString().substring(0, 8))
                .sourceEntityType(LedgerService.SOURCE_INCOME_EXPENSE)
                .sourceEntityId(UUID.randomUUID())
                .description("Report test")
                .recordedBy(admin)
                .approvedBy(admin)
                .idempotencyKey("RPT-" + UUID.randomUUID())
                .build());
    }

    private static String base(Scheme s) {
        return "/api/v1/cooperatives/" + s.id() + "/reports/model-report";
    }

    private static String bearer(Actor who) {
        return "Bearer " + who.token();
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.path(field).decimalValue();
    }
}
