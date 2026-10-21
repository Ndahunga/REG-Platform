package rw.terimbere.csams.modules.cooperative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.investment.entity.Investment;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanSettingsService;
import rw.terimbere.csams.modules.payout.entity.PayoutLine;
import rw.terimbere.csams.modules.payout.entity.PayoutLineStatus;
import rw.terimbere.csams.modules.payout.entity.PayoutRun;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;
import rw.terimbere.csams.modules.payout.repository.PayoutLineRepository;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * Task 3A: the operating-model foundation. A scheme has exactly one of ACCUMULATING, ROTATING or HYBRID, chosen at
 * registration; legacy schemes (no model) are classified once, atomically and audited; a model never changes
 * afterwards; and the three accumulating-only outflows are refused for schemes that chose another model.
 *
 * <p>Runs on H2 (PostgreSQL mode); PostgreSQL is not verified here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchemeOperatingModelIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LedgerService ledgerService;
    @Autowired private LedgerFinancialCalculationService fundService;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LoanSettingsService loanSettingsService;
    @Autowired private PayoutRunRepository payoutRunRepository;
    @Autowired private PayoutLineRepository payoutLineRepository;
    @Autowired private InvestmentRepository investmentRepository;

    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
    }

    // ---------------------------------------------------------------- registration (admin)

    @Test
    void eachModelCanBeChosenAtRegistrationAndIsPersistedReturnedAndGivenAFundLock() throws Exception {
        for (String model : List.of("ACCUMULATING", "ROTATING", "HYBRID")) {
            MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                            .header("Authorization", "Bearer " + superAdminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CooperativeTestFixtures.createBody("OM " + model, "1000.0000", 1, model)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.operatingModel").value(model))
                    .andReturn();
            UUID id = idOf(created);

            assertThat(storedModel(id)).isEqualTo(model);
            assertThat(jdbc.queryForObject(
                            "select operating_model_unclassified from cooperatives where id = ?", Boolean.class, id))
                    .as("a new scheme is never flagged as legacy")
                    .isFalse();
            assertThat(count("select count(*) from scheme_fund_locks where cooperative_id = ?", id))
                    .as("the fund-lock row is still created with the scheme")
                    .isEqualTo(1);
            mockMvc.perform(get("/api/v1/cooperatives/" + id).header("Authorization", "Bearer " + superAdminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.operatingModel").value(model));
        }
    }

    @Test
    void aMissingOrUnsupportedModelIsRejectedAndNothingIsCreated() throws Exception {
        for (String model : new String[] {null, "", "WEEKLY", "hybrid", "UNKNOWN"}) {
            String body = CooperativeTestFixtures.createBody("OM Bad " + model, "1000.0000", 1, model);
            String name = objectMapper.readTree(body).path("name").asText();
            mockMvc.perform(post("/api/v1/cooperatives")
                            .header("Authorization", "Bearer " + superAdminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'operatingModel')]").exists());
            assertThat(count("select count(*) from cooperatives where name = ?", name))
                    .as("nothing is created for model=%s", model)
                    .isZero();
        }
    }

    @Test
    void theDatabaseItselfRefusesASchemeWithoutAModel() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "insert into cooperatives (id, name, currency, financial_year_start_month, "
                                + "monthly_contribution_amount, contribution_due_day, status, onboarding_state, deleted, version) "
                                + "values (?, ?, 'RWF', 1, 0, 1, 'ACTIVE', 'COMPLETE', false, 0)",
                        UUID.randomUUID(),
                        "OM NoModel " + suffix))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- registration (public onboarding)

    @Test
    void publicOnboardingRequiresAndPersistsTheModelAndKeepsTheFundLock() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult signup = mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("om_" + suffix, "RCA/OM/" + suffix.toUpperCase(), "HYBRID")))
                .andExpect(status().isOk())
                .andReturn();
        UUID id = UUID.fromString(objectMapper
                .readTree(signup.getResponse().getContentAsString())
                .path("data")
                .path("user")
                .path("cooperativeIds")
                .get(0)
                .asText());
        assertThat(storedModel(id)).isEqualTo("HYBRID");
        assertThat(count("select count(*) from scheme_fund_locks where cooperative_id = ?", id)).isEqualTo(1);

        for (String bad : new String[] {null, "", "WEEKLY"}) {
            String other = UUID.randomUUID().toString().substring(0, 8);
            mockMvc.perform(post("/api/v1/onboarding/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("om_" + other, "RCA/OM/" + other.toUpperCase(), bad)))
                    .andExpect(status().isBadRequest());
            assertThat(count("select count(*) from users where username = ?", "om_" + other))
                    .as("a rejected signup creates no user")
                    .isZero();
        }
    }

    // ---------------------------------------------------------------- legacy classification

    @Test
    void aLegacySchemeStaysUnclassifiedAndOrdinaryUpdatesCannotSetIt() throws Exception {
        UUID legacy = legacyScheme();

        mockMvc.perform(get("/api/v1/cooperatives/" + legacy).header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatingModel").doesNotExist());
        assertThat(storedModel(legacy)).isNull();

        // A generic update, even one that smuggles the field in, never sets or changes the model.
        mockMvc.perform(put("/api/v1/cooperatives/" + legacy)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("HYBRID")))
                .andExpect(status().isOk());
        assertThat(storedModel(legacy)).isNull();
    }

    @Test
    void anAuthorizedAdministratorClassifiesALegacySchemeOnceAndItIsAudited() throws Exception {
        UUID legacy = legacyScheme();
        UUID presidentOf = legacy;
        String presidentToken = registerAndLogin(presidentOf, "PRESIDENT");

        mockMvc.perform(post("/api/v1/cooperatives/" + legacy + "/operating-model")
                        .header("Authorization", "Bearer " + presidentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(classifyBody("ROTATING", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatingModel").value("ROTATING"));

        assertThat(storedModel(legacy)).isEqualTo("ROTATING");
        assertThat(jdbc.queryForObject(
                        "select operating_model_unclassified from cooperatives where id = ?", Boolean.class, legacy))
                .isFalse();
        assertThat(count(
                        "select count(*) from audit_logs where action = 'COOPERATIVE_OPERATING_MODEL_SET' "
                                + "and entity_id = ? and cooperative_id = ?",
                        legacy,
                        legacy))
                .as("the classification is audited")
                .isEqualTo(1);
    }

    @Test
    void classificationNeedsExplicitConfirmationAndAValidModel() throws Exception {
        UUID legacy = legacyScheme();
        for (String body : new String[] {
            classifyBody("ACCUMULATING", false),
            "{\"operatingModel\":\"ACCUMULATING\"}",
            classifyBody("WEEKLY", true),
            "{\"confirmed\":true}"
        }) {
            mockMvc.perform(post("/api/v1/cooperatives/" + legacy + "/operating-model")
                            .header("Authorization", "Bearer " + superAdminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(storedModel(legacy)).isNull();
    }

    @Test
    void unauthorizedAndCrossSchemeClassificationIsRejected() throws Exception {
        UUID legacy = legacyScheme();
        UUID otherScheme = legacyScheme();
        String memberToken = registerAndLogin(legacy, "MEMBER");
        String accountantToken = registerAndLogin(legacy, "ACCOUNTANT");
        String otherPresidentToken = registerAndLogin(otherScheme, "PRESIDENT");

        for (String token : List.of(memberToken, accountantToken, otherPresidentToken)) {
            mockMvc.perform(post("/api/v1/cooperatives/" + legacy + "/operating-model")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(classifyBody("ACCUMULATING", true)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/cooperatives/" + legacy + "/operating-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(classifyBody("ACCUMULATING", true)))
                .andExpect(status().is4xxClientError());
        assertThat(storedModel(legacy)).as("nothing was classified").isNull();
        assertThat(storedModel(otherScheme)).as("the other scheme is untouched").isNull();
    }

    @Test
    void concurrentClassificationsCannotSilentlyOverwriteEachOther() throws Exception {
        UUID legacy = legacyScheme();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> attempts = List.of(
                    () -> classifyStatus(legacy, "ACCUMULATING", go), () -> classifyStatus(legacy, "ROTATING", go));
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("exactly one wins, the other is told it is already set").containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(storedModel(legacy)).isIn("ACCUMULATING", "ROTATING");
        assertThat(count(
                        "select count(*) from audit_logs where action = 'COOPERATIVE_OPERATING_MODEL_SET' and entity_id = ?",
                        legacy))
                .as("only the winning classification is audited")
                .isEqualTo(1);
    }

    @Test
    void onceSetTheModelCannotBeChangedByAnyRoute() throws Exception {
        UUID legacy = legacyScheme();
        classify(legacy, "ACCUMULATING").andExpect(status().isOk());
        UUID created = createScheme("HYBRID");

        // Same value, a different value, and a scheme that was created with its model: all refused.
        for (UUID scheme : List.of(legacy, created)) {
            for (String model : List.of("ACCUMULATING", "ROTATING", "HYBRID")) {
                String before = storedModel(scheme);
                classify(scheme, model)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("OPERATING_MODEL_ALREADY_SET"));
                assertThat(storedModel(scheme)).isEqualTo(before);
            }
        }
        // And a generic update cannot change it either.
        mockMvc.perform(put("/api/v1/cooperatives/" + created)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("ROTATING")))
                .andExpect(status().isOk());
        assertThat(storedModel(created)).isEqualTo("HYBRID");
    }

    @Test
    void classificationChangesNoFinancialRecord() throws Exception {
        UUID legacy = legacyScheme();
        UUID member = registerMember(legacy);
        seedFunds(legacy, "100000");
        approvedLoan(legacy, member, "30000");
        Map<String, Object> before = financialSnapshot();
        BigDecimal fundsBefore = fundService.calculateAvailableGroupFund(legacy);

        classify(legacy, "ACCUMULATING").andExpect(status().isOk());

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(fundService.calculateAvailableGroupFund(legacy)).isEqualByComparingTo(fundsBefore);
    }

    // ---------------------------------------------------------------- financial safety

    @Test
    void rotatingAndHybridSchemesCannotUseTheAccumulatingOutflows() throws Exception {
        for (String model : List.of("ROTATING", "HYBRID")) {
            UUID scheme = createScheme(model);
            UUID member = registerMember(scheme);
            seedFunds(scheme, "100000");
            UUID loan = approvedLoan(scheme, member, "30000");
            UUID run = previewedPayoutRun(scheme, member, "30000");
            UUID investment = plannedInvestment(scheme, "30000");

            expectUnsupported(scheme, "/loans/" + loan + "/disburse");
            expectUnsupported(scheme, "/payouts/" + run + "/confirm");
            expectUnsupported(scheme, "/investments/" + investment + "/activate");

            assertThat(jdbc.queryForObject("select status from loans where id = ?", String.class, loan))
                    .isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("select status from payout_runs where id = ?", String.class, run))
                    .isEqualTo("PREVIEWED");
            assertThat(jdbc.queryForObject("select status from investments where id = ?", String.class, investment))
                    .isEqualTo("PLANNED");
            assertThat(count(
                            "select count(*) from financial_ledger where cooperative_id = ? and transaction_type in "
                                    + "('LOAN_DISBURSEMENT','MEMBER_PAYOUT','INVESTMENT_OUTFLOW')",
                            scheme))
                    .as("no outflow was posted for a %s scheme", model)
                    .isZero();
            assertThat(fundService.calculateAvailableGroupFund(scheme)).isEqualByComparingTo("100000");
        }
    }

    @Test
    void accumulatingAndLegacySchemesKeepTheirExistingBehavior() throws Exception {
        UUID accumulating = createScheme("ACCUMULATING");
        UUID legacy = legacyScheme();
        for (UUID scheme : List.of(accumulating, legacy)) {
            UUID member = registerMember(scheme);
            seedFunds(scheme, "100000");
            UUID loan = approvedLoan(scheme, member, "30000");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/v1/cooperatives/" + scheme + "/loans/" + loan + "/disburse")
                            .header("Authorization", "Bearer " + superAdminToken))
                    .andExpect(status().isOk());
            assertThat(fundService.calculateAvailableGroupFund(scheme)).isEqualByComparingTo("70000");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void expectUnsupported(UUID scheme, String path) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/cooperatives/" + scheme + path)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPERATING_MODEL_NOT_SUPPORTED"));
    }

    private org.springframework.test.web.servlet.ResultActions classify(UUID scheme, String model) throws Exception {
        return mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/operating-model")
                .header("Authorization", "Bearer " + superAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(classifyBody(model, true)));
    }

    private int classifyStatus(UUID scheme, String model, CountDownLatch go) throws Exception {
        go.await();
        return classify(scheme, model).andReturn().getResponse().getStatus();
    }

    private static String classifyBody(String model, boolean confirmed) {
        return "{\"operatingModel\":\"" + model + "\",\"confirmed\":" + confirmed + "}";
    }

    private UUID createScheme(String model) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "OM " + UUID.randomUUID().toString().substring(0, 8), "1000.0000", 1, model)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(created);
    }

    /** A scheme as one that predates V30 would be: created, then returned to the legacy "no model" state. */
    private UUID legacyScheme() throws Exception {
        UUID id = createScheme("ACCUMULATING");
        jdbc.update("update cooperatives set operating_model = null, operating_model_unclassified = true where id = ?", id);
        return id;
    }

    private String storedModel(UUID id) {
        return jdbc.queryForObject("select operating_model from cooperatives where id = ?", String.class, id);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    private Map<String, Object> financialSnapshot() {
        return jdbc.queryForMap(
                "select (select count(*) from financial_ledger) as ledger_rows, "
                        + "(select coalesce(sum(credit_amount), 0) from financial_ledger) as credits, "
                        + "(select coalesce(sum(debit_amount), 0) from financial_ledger) as debits, "
                        + "(select count(*) from loans) as loans, "
                        + "(select coalesce(sum(outstanding_principal), 0) from loans) as outstanding, "
                        + "(select count(*) from fines) as fines, (select count(*) from share_purchases) as share_purchases");
    }

    private UUID registerMember(UUID scheme) throws Exception {
        return registerWithRole(scheme, "MEMBER").id();
    }

    private String registerAndLogin(UUID scheme, String role) throws Exception {
        return registerWithRole(scheme, role).token();
    }

    private record Registered(UUID id, String token) {}

    private Registered registerWithRole(UUID scheme, String role) throws Exception {
        String username = "om_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Om","lastName":"Member","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return new Registered(
                UUID.fromString(data.path("userId").asText()),
                login(username, data.path("temporaryPassword").asText()));
    }

    private void seedFunds(UUID scheme, String amount) {
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(scheme)
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .creditAmount(new BigDecimal(amount))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now(KIGALI).minusMonths(1))
                .sourceEntityType("OM_TEST_SEED")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("om-seed-" + UUID.randomUUID())
                .description("seed")
                .build());
    }

    private UUID approvedLoan(UUID scheme, UUID borrower, String amount) {
        loanSettingsService.requireSettings(scheme);
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository
                .saveAndFlush(Loan.builder()
                        .cooperativeId(scheme)
                        .memberUserId(borrower)
                        .requestedAmount(new BigDecimal(amount))
                        .approvedAmount(new BigDecimal(amount))
                        .interestRatePercent(new BigDecimal("2"))
                        .interestType(InterestType.FLAT)
                        .termMonths(6)
                        .interestAmount(BigDecimal.ZERO)
                        .outstandingPrincipal(BigDecimal.ZERO)
                        .outstandingInterest(BigDecimal.ZERO)
                        .requestDate(today.minusDays(5))
                        .approvalDate(today.minusDays(1))
                        .status(LoanStatus.APPROVED)
                        .build())
                .getId();
    }

    private UUID previewedPayoutRun(UUID scheme, UUID member, String amount) {
        LocalDate today = LocalDate.now(KIGALI);
        BigDecimal pool = new BigDecimal(amount);
        PayoutRun run = payoutRunRepository.saveAndFlush(PayoutRun.builder()
                .cooperativeId(scheme)
                .name("om test run")
                .periodFrom(today.minusMonths(2))
                .periodTo(today.minusMonths(1))
                .includeRegular(true)
                .includeSpecial(false)
                .availableFundSnapshot(pool)
                .payoutPoolAmount(pool)
                .totalEligibleContributions(pool)
                .currency("RWF")
                .status(PayoutRunStatus.PREVIEWED)
                .build());
        payoutLineRepository.saveAndFlush(PayoutLine.builder()
                .payoutRunId(run.getId())
                .cooperativeId(scheme)
                .memberUserId(member)
                .eligibleContributionAmount(pool)
                .percentage(new BigDecimal("100"))
                .payoutAmount(pool)
                .status(PayoutLineStatus.PENDING)
                .build());
        return run.getId();
    }

    private UUID plannedInvestment(UUID scheme, String amount) {
        return investmentRepository
                .saveAndFlush(Investment.builder()
                        .cooperativeId(scheme)
                        .name("om test investment")
                        .amount(new BigDecimal(amount))
                        .remainingCapital(BigDecimal.ZERO)
                        .totalCapitalReturned(BigDecimal.ZERO)
                        .totalProfitReturned(BigDecimal.ZERO)
                        .status(InvestmentStatus.PLANNED)
                        .build())
                .getId();
    }

    private String updateBody(String smuggledModel) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return """
                {
                  "name":"OM Updated %s",
                  "registrationNumber":"RCA/UPD/%s",
                  "contactEmail":"upd-%s@test.local",
                  "contactPhone":"0781234567",
                  "currency":"RWF",
                  "financialYearStartMonth":1,
                  "monthlyContributionAmount":1000,
                  "contributionDueDay":1,
                  "registrationDate":"2024-01-15",
                  "operatingModel":"%s"
                }
                """
                .formatted(suffix, suffix.toUpperCase(), suffix.toLowerCase(), smuggledModel);
    }

    private static String signupBody(String username, String registrationNumber, String operatingModel) {
        String model = operatingModel == null ? "" : "\"operatingModel\":\"" + operatingModel + "\",";
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
                    %s
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
                .formatted(username, registrationNumber, username, model, username, username);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }
}
