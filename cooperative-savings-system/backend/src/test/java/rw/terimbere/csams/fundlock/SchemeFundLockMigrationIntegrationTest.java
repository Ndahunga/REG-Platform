package rw.terimbere.csams.fundlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * V29 (the {@code scheme_fund_locks} table and its backfill), scheme-creation integration and the lock service's
 * fail-safe behaviour. Runs on H2 (PostgreSQL mode) in the normal suite; the SQL is plain, portable SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchemeFundLockMigrationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CooperativeRepository cooperativeRepository;
    @Autowired private SchemeFundLockService lockService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private LedgerService ledgerService;
    @Autowired private LedgerFinancialCalculationService fundService;

    /** Spied so one test can make the initialization step fail; it passes through to the real service otherwise. */
    @SpyBean private SchemeFundLockService spiedLockService;

    // ------------------------------------------------------------------ migration backfill

    @Test
    void backfillGivesEverySchemeExactlyOneLockRowAndTouchesNoFinancialRecord() throws Exception {
        UUID funded = createSchemeThroughApi();
        UUID legacy = persistCooperativeWithoutLock(); // a scheme that existed before V29
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(funded)
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .creditAmount(new BigDecimal("12345"))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now().minusDays(3))
                .sourceEntityType("MIGRATION_TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("v29-" + UUID.randomUUID())
                .description("seed")
                .build());
        Map<String, Object> before = financialSnapshot();
        BigDecimal fundsBefore = fundService.calculateAvailableGroupFund(funded);
        assertThat(lockRows(legacy)).as("the legacy scheme has no lock row yet").isZero();

        // Re-run the migration's own backfill statement, read from the migration file, after clearing the table.
        jdbc.update("delete from scheme_fund_locks");
        jdbc.execute(backfillStatement());

        assertThat(count("select count(*) from scheme_fund_locks")).isEqualTo(count("select count(*) from cooperatives"));
        assertThat(count("select count(*) from cooperatives c where "
                        + "(select count(*) from scheme_fund_locks l where l.cooperative_id = c.id) <> 1"))
                .as("every scheme has exactly one lock row")
                .isZero();
        assertThat(count("select count(*) from scheme_fund_locks l where not exists "
                        + "(select 1 from cooperatives c where c.id = l.cooperative_id)"))
                .as("no lock row points at a missing scheme")
                .isZero();
        assertThat(lockRows(legacy)).isEqualTo(1);
        assertThat(lockRows(funded)).isEqualTo(1);
        assertThat(financialSnapshot()).as("no financial record changed").isEqualTo(before);
        assertThat(fundService.calculateAvailableGroupFund(funded)).isEqualByComparingTo(fundsBefore);
    }

    // ------------------------------------------------------------------ new schemes

    @Test
    void everyCreationPathGivesTheNewSchemeItsLockRowInTheSameTransaction() throws Exception {
        UUID byAdmin = createSchemeThroughApi();
        assertThat(lockRows(byAdmin)).isEqualTo(1);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult signup = mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("fl_" + suffix, "RCA/FL/" + suffix.toUpperCase())))
                .andExpect(status().isOk())
                .andReturn();
        UUID bySignup = UUID.fromString(objectMapper
                .readTree(signup.getResponse().getContentAsString())
                .path("data")
                .path("user")
                .path("cooperativeIds")
                .get(0)
                .asText());
        assertThat(lockRows(bySignup)).isEqualTo(1);
        assertThat(count("select count(*) from cooperatives c where "
                        + "(select count(*) from scheme_fund_locks l where l.cooperative_id = c.id) <> 1 "
                        + "and c.id in (?, ?)", byAdmin, bySignup))
                .isZero();
    }

    @Test
    void aFailedInitializationRollsTheWholeSchemeCreationBack() throws Exception {
        // The method is MANDATORY-transactional, so it can only be stubbed from inside a transaction.
        transactionTemplate.executeWithoutResult(tx -> Mockito.doThrow(new IllegalStateException("injected initialization failure"))
                .when(spiedLockService)
                .initializeForNewScheme(org.mockito.ArgumentMatchers.any()));
        String name = "FL Rollback " + UUID.randomUUID().toString().substring(0, 8);
        long cooperativesBefore = count("select count(*) from cooperatives");
        long locksBefore = count("select count(*) from scheme_fund_locks");

        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name, "10000.0000", 1)))
                .andExpect(status().isInternalServerError());

        assertThat(count("select count(*) from cooperatives where name = ?", name))
                .as("the scheme was not created without its lock row")
                .isZero();
        assertThat(count("select count(*) from cooperatives")).isEqualTo(cooperativesBefore);
        assertThat(count("select count(*) from scheme_fund_locks")).isEqualTo(locksBefore);
    }

    // ------------------------------------------------------------------ integrity

    @Test
    void theTableEnforcesOneRowPerExistingScheme() throws Exception {
        UUID scheme = createSchemeThroughApi();

        assertThatThrownBy(() -> jdbc.update("insert into scheme_fund_locks (cooperative_id) values (?)", scheme))
                .as("a second lock row for the same scheme is impossible")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into scheme_fund_locks (cooperative_id) values (?)", UUID.randomUUID()))
                .as("a lock row for a scheme that does not exist is impossible")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(lockRows(scheme)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the lock service fails safely

    @Test
    void theLockServiceNeverContinuesUnlockedAndNeverCreatesARowOnDemand() throws Exception {
        UUID scheme = createSchemeThroughApi();
        UUID legacy = persistCooperativeWithoutLock();

        // No transaction: refused, because a lock outside the financial transaction would protect nothing.
        assertThatThrownBy(() -> lockService.lockSchemeFund(scheme)).isInstanceOf(IllegalTransactionStateException.class);

        // Inside a transaction: the scheme's own row is locked and released at commit.
        transactionTemplate.executeWithoutResult(tx -> lockService.lockSchemeFund(scheme));

        // A scheme without a lock row and an unknown scheme both fail; nothing is created to make them succeed.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> lockService.lockSchemeFund(legacy)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Scheme fund lock is missing");
        assertThatThrownBy(() ->
                        transactionTemplate.executeWithoutResult(tx -> lockService.lockSchemeFund(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lockRows(legacy)).as("no row was created on demand").isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String backfillStatement() throws Exception {
        String sql = new String(
                new ClassPathResource("db/migration/V29__scheme_fund_locks.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("INSERT INTO scheme_fund_locks");
        assertThat(start).as("the migration contains its backfill statement").isGreaterThanOrEqualTo(0);
        return sql.substring(start, sql.indexOf(';', start));
    }

    /** Counts and sums over every financial table the migration must not touch. */
    private Map<String, Object> financialSnapshot() {
        return jdbc.queryForMap(
                "select (select count(*) from financial_ledger) as ledger_rows, "
                        + "(select coalesce(sum(credit_amount), 0) from financial_ledger) as credits, "
                        + "(select coalesce(sum(debit_amount), 0) from financial_ledger) as debits, "
                        + "(select count(*) from loans) as loans, "
                        + "(select coalesce(sum(outstanding_principal), 0) from loans) as outstanding, "
                        + "(select count(*) from fines) as fines, "
                        + "(select count(*) from loan_repayments) as repayments");
    }

    private long lockRows(UUID scheme) {
        return count("select count(*) from scheme_fund_locks where cooperative_id = ?", scheme);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private UUID createSchemeThroughApi() throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "FL Migration " + UUID.randomUUID().toString().substring(0, 8), "10000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    /** A scheme persisted directly, as one that predates V29 would be: no lock row. */
    private UUID persistCooperativeWithoutLock() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return cooperativeRepository
                .saveAndFlush(Cooperative.builder()
                        .name("FL Legacy " + suffix)
                        .registrationNumber("RCA/LEG/" + suffix.toUpperCase())
                        .contactEmail("leg-" + suffix.toLowerCase() + "@test.local")
                        .contactPhone("0781234567")
                        .currency("RWF")
                        .financialYearStartMonth(1)
                        .monthlyContributionAmount(new BigDecimal("1000.0000"))
                        .contributionDueDay(1)
                        .registrationDate(LocalDate.of(2024, 1, 15))
                        .status(CooperativeStatus.ACTIVE)
                        .onboardingState(CooperativeOnboardingState.AWAITING_PRESIDENT)
                        .operatingModel(rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel.ACCUMULATING)
                        .build())
                .getId();
    }

    private String superAdminTokenCache;

    private String superAdminToken() throws Exception {
        if (superAdminTokenCache == null) {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"superadmin\",\"password\":\"ChangeMe@123!\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            superAdminTokenCache = objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .path("data")
                    .path("accessToken")
                    .asText();
        }
        return superAdminTokenCache;
    }

    private static String signupBody(String username, String registrationNumber) {
        return """
                {
                  "cooperative": {
                    "name":"Public Scheme %s",
                    "registrationNumber":"%s",
                    "contactEmail":"%s@test.local",
                    "contactPhone":"0781234567",
                    "address":"Kigali",
                    "currency":"RWF",
                    "financialYearStartMonth":1,
                    "monthlyContributionAmount":5000,
                    "contributionDueDay":5,
                    "operatingModel":"ACCUMULATING",
                    "registrationDate":"2024-01-15"
                  },
                  "creator": {
                    "username":"%s",
                    "email":"%s@test.local",
                    "password":"SignupPass1!",
                    "firstName":"Pat",
                    "lastName":"President",
                    "phone":"0781112233"
                  }
                }
                """
                .formatted(username, registrationNumber, username, username, username);
    }
}
