package rw.terimbere.csams.modules.report.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerPeriodAggregationServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerPeriodAggregationService aggregationService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerService ledgerService;

    private String superAdminToken;
    private UUID cooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        cooperativeId = createCooperative("Ledger Agg " + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void approvedOnly_periodInclusive_openingClosingAndYearBoundary() {
        // Prior period (opening)
        credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "10000", LocalDate.of(2025, 12, 15));
        debit(LedgerTransactionType.GENERAL_EXPENSE, "2000", LocalDate.of(2025, 12, 20));

        // In period (Dec 31 edge → Jan–Feb)
        credit(LedgerTransactionType.LOAN_INTEREST_PAYMENT, "1500", LocalDate.of(2026, 1, 1));
        credit(LedgerTransactionType.FINE_PAYMENT, "500", LocalDate.of(2026, 1, 15));
        debit(LedgerTransactionType.GENERAL_EXPENSE, "300", LocalDate.of(2026, 2, 28));
        credit(LedgerTransactionType.OTHER_INCOME, "200", LocalDate.of(2026, 2, 28));

        // After period (excluded from closing through Feb 28? closing includes through toDate)
        credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "9999", LocalDate.of(2026, 3, 1));

        // Reversed / non-approved should not count
        LedgerEntry reversed = credit(LedgerTransactionType.OTHER_INCOME, "777", LocalDate.of(2026, 1, 10));
        reversed.setStatus(LedgerEntryStatus.REVERSED);
        ledgerEntryRepository.saveAndFlush(reversed);

        // Social fund excluded from main fund
        credit(LedgerTransactionType.SOCIAL_CONTRIBUTION, "5000", LocalDate.of(2026, 1, 5));

        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 2, 28);

        LedgerPeriodTotals totals = aggregationService.aggregateMainFund(cooperativeId, from, to);

        assertThat(totals.getOpeningLedgerBalance()).isEqualByComparingTo("8000.00"); // 10000-2000
        assertThat(totals.credit(LedgerTransactionType.LOAN_INTEREST_PAYMENT)).isEqualByComparingTo("1500.00");
        assertThat(totals.credit(LedgerTransactionType.FINE_PAYMENT)).isEqualByComparingTo("500.00");
        assertThat(totals.credit(LedgerTransactionType.OTHER_INCOME)).isEqualByComparingTo("200.00");
        assertThat(totals.debit(LedgerTransactionType.GENERAL_EXPENSE)).isEqualByComparingTo("300.00");
        assertThat(totals.getPeriodCredits()).isEqualByComparingTo("2200.00");
        assertThat(totals.getPeriodDebits()).isEqualByComparingTo("300.00");
        assertThat(totals.netCashMovement()).isEqualByComparingTo("1900.00");
        assertThat(totals.getClosingLedgerBalance()).isEqualByComparingTo("9900.00"); // 8000+1900
        assertThat(MoneyUtils.add(totals.getOpeningLedgerBalance(), totals.netCashMovement()))
                .isEqualByComparingTo(totals.getClosingLedgerBalance());

        // Social not in type map for main fund
        assertThat(totals.credit(LedgerTransactionType.SOCIAL_CONTRIBUTION)).isEqualByComparingTo("0.00");

        BigDecimal socialCredit = aggregationService.sumApprovedCreditsInPeriod(
                cooperativeId, from, to, EnumSet.of(LedgerTransactionType.SOCIAL_CONTRIBUTION));
        assertThat(socialCredit).isEqualByComparingTo("5000.00");
    }

    @Test
    void incomeAndExpenseTypeTotals() {
        credit(LedgerTransactionType.LOAN_INTEREST_PAYMENT, "1000", LocalDate.of(2026, 4, 1));
        credit(LedgerTransactionType.INVESTMENT_PROFIT, "400", LocalDate.of(2026, 4, 2));
        debit(LedgerTransactionType.INTEREST_EXPENSE, "50", LocalDate.of(2026, 4, 3));
        debit(LedgerTransactionType.GENERAL_EXPENSE, "150", LocalDate.of(2026, 4, 4));
        credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "8000", LocalDate.of(2026, 4, 5)); // not income

        LedgerPeriodTotals totals = aggregationService.aggregateMainFund(
                cooperativeId, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));

        BigDecimal income = MoneyUtils.scale(BigDecimal.ZERO);
        for (LedgerTransactionType t : FullFinancialLedgerClassifications.OPERATING_INCOME_TYPES) {
            income = MoneyUtils.add(income, totals.credit(t));
        }
        BigDecimal expense = MoneyUtils.scale(BigDecimal.ZERO);
        for (LedgerTransactionType t : FullFinancialLedgerClassifications.OPERATING_EXPENSE_TYPES) {
            expense = MoneyUtils.add(expense, totals.debit(t));
        }
        assertThat(income).isEqualByComparingTo("1400.00");
        assertThat(expense).isEqualByComparingTo("200.00");
        assertThat(totals.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("8000.00");
    }

    private LedgerEntry credit(LedgerTransactionType type, String amount, LocalDate date) {
        return ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(type)
                .creditAmount(new BigDecimal(amount))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(date)
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("test-c-" + UUID.randomUUID())
                .recordedBy(null)
                .description("test credit")
                .build());
    }

    private LedgerEntry debit(LedgerTransactionType type, String amount, LocalDate date) {
        return ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(type)
                .debitAmount(new BigDecimal(amount))
                .creditAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(date)
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("test-d-" + UUID.randomUUID())
                .recordedBy(null)
                .description("test debit")
                .build());
    }

    private UUID createCooperative(String name) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private String login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(login.getResponse().getContentAsString()).path("data");
        return data.path("accessToken").asText();
    }
}
