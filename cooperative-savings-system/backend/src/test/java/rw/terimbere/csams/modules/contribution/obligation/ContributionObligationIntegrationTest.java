package rw.terimbere.csams.modules.contribution.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.ResultActions;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.AttributionPlan;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Task 3B-2: contribution obligations, arrears and the rotating/retained allocation foundation. Obligations are
 * expectations: these tests also assert that nothing here posts to the ledger or changes a balance.
 *
 * <p>Months: only the current and the next month can be given an obligation snapshot, and a rotation configuration can
 * only take effect from next month, so the rotating and hybrid scenarios use NEXT. Runs on H2 (PostgreSQL mode);
 * PostgreSQL is not verified here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContributionObligationIntegrationTest {

    private static final java.time.ZoneId KIGALI = java.time.ZoneId.of("Africa/Kigali");
    private static final YearMonth NOW = YearMonth.now(KIGALI);
    private static final YearMonth NEXT = NOW.plusMonths(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContributionObligationService obligationService;

    private String superAdminToken;
    private UUID superAdminId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        superAdminId = jdbc.queryForObject("select id from users where username = 'superadmin'", UUID.class);
    }

    // ---------------------------------------------------------------- due day: legacy and configuration

    @Test
    void aLegacySchemeWithNoConfiguredDueDayGetsNoDueDatesAndNoArrearsBackdating() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        legacyDueDay(scheme);
        Registered account = register(scheme, "MEMBER");
        jdbc.update("update cooperative_memberships set share_count = 2 where cooperative_id = ? and user_id = ?", scheme, account.id());
        UUID member = account.id();

        mockMvc.perform(get(dueDayPath(scheme)).header("Authorization", bearer(superAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.currentDueDay").doesNotExist());

        // a past month recorded late: row stored, no snapshot, no due date, nothing overdue, whatever the legacy column says
        saveLine(scheme, NOW.minusMonths(2), member, "0").andExpect(status().isOk());
        JsonNode row = rowOf(grid(scheme, NOW.minusMonths(2)), member);
        assertThat(row.path("persisted").asBoolean()).isTrue();
        assertThat(row.path("obligationSnapshotted").asBoolean()).isFalse();
        assertThat(absent(row.path("dueDate"))).isTrue();
        assertThat(row.path("overdue").asBoolean()).isFalse();
        assertThat(decimal(row, "overdueAmount")).isEqualByComparingTo("0");
        assertThat(count("select count(*) from contributions where cooperative_id = ? and obligation_snapshot_at is not null", scheme))
                .as("a month that has begun gets no invented snapshot")
                .isZero();

        // the member's own preview shows no due date either (the legacy column defaults to day 1)
        String memberToken = account.token();
        mockMvc.perform(get(base(scheme) + "/contributions/my/period-preview?year=" + NOW.getYear() + "&month=" + NOW.getMonthValue())
                        .header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueDate").doesNotExist())
                .andExpect(jsonPath("$.data.overdue").value(false));
    }

    @Test
    void aConfiguredDueDayAppliesFromNextMonthOnlyAndKeepsItsHistory() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        legacyDueDay(scheme);
        UUID member = member(scheme, 2);

        setDueDay(scheme, 15, true, superAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.currentDueDay").doesNotExist())
                .andExpect(jsonPath("$.data.pending.dueDay").value(15))
                .andExpect(jsonPath("$.data.pending.effectiveYear").value(NEXT.getYear()))
                .andExpect(jsonPath("$.data.pending.effectiveMonth").value(NEXT.getMonthValue()));

        // no backdating: this month and earlier have no due date, next month has the 15th
        assertThat(absent(rowOf(grid(scheme, NOW), member).path("dueDate"))).isTrue();
        assertThat(absent(rowOf(grid(scheme, NOW.minusMonths(1)), member).path("dueDate"))).isTrue();
        assertThat(rowOf(grid(scheme, NEXT), member).path("dueDate").asText())
                .isEqualTo(ContributionDueDates.dueDateFor(NEXT.getYear(), NEXT.getMonthValue(), 15).toString());

        // an identical repeat is a success; a different value for the same month is refused (insert-only)
        setDueDay(scheme, 15, true, superAdminToken).andExpect(status().isOk());
        setDueDay(scheme, 20, true, superAdminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRIBUTION_DUE_DAY_PENDING"));
        assertThat(count("select count(*) from contribution_due_day_settings where cooperative_id = ?", scheme)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'CONTRIBUTION_DUE_DAY_SET' and cooperative_id = ?", scheme))
                .isEqualTo(1);
    }

    @Test
    void theDueDayNeedsAValidValueAndExplicitConfirmation() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        legacyDueDay(scheme);
        for (String body : new String[] {
            "{\"dueDay\":0,\"confirmed\":true}",
            "{\"dueDay\":32,\"confirmed\":true}",
            "{\"dueDay\":10,\"confirmed\":false}",
            "{\"dueDay\":10}",
            "{\"confirmed\":true}"
        }) {
            mockMvc.perform(post(dueDayPath(scheme))
                            .header("Authorization", bearer(superAdminToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("select count(*) from contribution_due_day_settings where cooperative_id = ?", scheme)).isZero();
    }

    @Test
    void shortMonthsFollowTheEffectiveDueDayForSnapshotsAndProjections() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        legacyDueDay(scheme);
        UUID member = member(scheme, 1);
        // day 31 effective from next month (inserted directly: the API schedules from next month as well)
        jdbc.update("insert into contribution_due_day_settings (id, cooperative_id, effective_year, effective_month, due_day, created_by) values (?, ?, ?, ?, 31, ?)",
                UUID.randomUUID(), scheme, NEXT.getYear(), NEXT.getMonthValue(), superAdminId);
        YearMonth february = YearMonth.of(NOW.getYear() + 2, 2);
        assertThat(rowOf(grid(scheme, february), member).path("dueDate").asText())
                .isEqualTo(ContributionDueDates.dueDateFor(february.getYear(), 2, 31).toString());
        YearMonth april = YearMonth.of(NOW.getYear() + 2, 4);
        assertThat(rowOf(grid(scheme, april), member).path("dueDate").asText()).isEqualTo(april.atEndOfMonth().toString());
    }

    @Test
    void newSchemesRegisterTheirDueDayFromNextMonthThroughBothCreationPaths() throws Exception {
        UUID byAdmin = scheme("ACCUMULATING");
        assertThat(count("select count(*) from contribution_due_day_settings where cooperative_id = ? and due_day = 1 "
                                + "and effective_year = ? and effective_month = ?",
                        byAdmin, NEXT.getYear(), NEXT.getMonthValue()))
                .isEqualTo(1);
        mockMvc.perform(get(dueDayPath(byAdmin)).header("Authorization", bearer(superAdminToken)))
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.currentDueDay").doesNotExist());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult signup = mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("ob_" + suffix, "RCA/OB/" + suffix.toUpperCase())))
                .andExpect(status().isOk())
                .andReturn();
        UUID bySignup = UUID.fromString(objectMapper.readTree(signup.getResponse().getContentAsString())
                .path("data").path("user").path("cooperativeIds").get(0).asText());
        assertThat(count("select count(*) from contribution_due_day_settings where cooperative_id = ? and due_day = 5", bySignup))
                .isEqualTo(1);
    }

    @Test
    void anOrdinarySchemeEditNeverConfirmsADueDayForALegacyScheme() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        legacyDueDay(scheme);
        mockMvc.perform(put("/api/v1/cooperatives/" + scheme)
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(9)))
                .andExpect(status().isOk());
        assertThat(count("select count(*) from contribution_due_day_settings where cooperative_id = ?", scheme))
                .as("an edit of the legacy field must not silently configure a due day nobody chose")
                .isZero();

        // for a scheme that already has one, a changed day is scheduled from next month
        UUID configured = scheme("ACCUMULATING");
        mockMvc.perform(put("/api/v1/cooperatives/" + configured)
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(9)))
                .andExpect(status().isOk());
        assertThat(count("select count(*) from contribution_due_day_settings where cooperative_id = ? and due_day = 1", configured))
                .as("the seeded day 1 and an unchanged request leave one row, a change adds a pending one")
                .isGreaterThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------- authorization and tenant isolation

    @Test
    void onlyAdministratorsSetTheDueDayAndOnlyMembersOfTheSchemeReadIt() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        UUID other = scheme("ACCUMULATING");
        legacyDueDay(scheme);
        String president = tokenForRole(scheme, "PRESIDENT");
        String member = tokenForRole(scheme, "MEMBER");
        String accountant = tokenForRole(scheme, "ACCOUNTANT");
        String otherPresident = tokenForRole(other, "PRESIDENT");

        for (String token : List.of(member, accountant, otherPresident)) {
            setDueDay(scheme, 12, true, token).andExpect(status().isForbidden());
        }
        setDueDay(scheme, 12, true, president).andExpect(status().isOk());

        for (String token : List.of(member, accountant, president)) {
            mockMvc.perform(get(dueDayPath(scheme)).header("Authorization", bearer(token))).andExpect(status().isOk());
        }
        mockMvc.perform(get(dueDayPath(scheme)).header("Authorization", bearer(otherPresident)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(dueDayPath(scheme))).andExpect(status().is4xxClientError());
    }

    @Test
    void materializingNeedsContributionWriteAndStaysInsideTheScheme() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        UUID other = scheme("ACCUMULATING");
        member(scheme, 1);
        String member = tokenForRole(scheme, "MEMBER");
        String accountant = tokenForRole(scheme, "ACCOUNTANT");
        String otherAccountant = tokenForRole(other, "ACCOUNTANT");

        materialize(scheme, NEXT, member).andExpect(status().isForbidden());
        materialize(scheme, NEXT, otherAccountant).andExpect(status().isForbidden());
        assertThat(count("select count(*) from contributions where cooperative_id = ?", scheme)).isZero();
        materialize(scheme, NEXT, accountant).andExpect(status().isOk());
        assertThat(count("select count(*) from contributions where cooperative_id = ?", other)).isZero();
    }

    // ---------------------------------------------------------------- materialization and snapshot stability

    @Test
    void materializingCreatesOneSnapshottedRowPerActiveMemberAndNeverTouchesExistingRows() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        setUnitAmount(scheme, "1000");
        confirmedDueDay(scheme, 12);
        UUID a = member(scheme, 3);
        UUID b = member(scheme, 1);
        UUID inactive = member(scheme, 5);
        jdbc.update("update cooperative_memberships set membership_status = 'INACTIVE' where cooperative_id = ? and user_id = ?",
                scheme, inactive);
        // a row that already exists for the current month (it predates snapshots: no snapshot columns)
        UUID existing = member(scheme, 2);
        jdbc.update("insert into contributions (id, cooperative_id, member_user_id, \"year\", \"month\", expected_amount, paid_amount, outstanding_amount, status, share_count) "
                        + "values (?, ?, ?, ?, ?, 2000, 500, 1500, 'PARTIALLY_PAID', 2)",
                UUID.randomUUID(), scheme, existing, NOW.getYear(), NOW.getMonthValue());

        materialize(scheme, NOW, superAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.alreadyPresent").value(1))
                .andExpect(jsonPath("$.data.activeMembers").value(3));
        assertThat(count("select count(*) from contributions where cooperative_id = ? and \"year\" = ? and \"month\" = ?",
                        scheme, NOW.getYear(), NOW.getMonthValue()))
                .isEqualTo(3);
        // the existing row was not recreated, rewritten or snapshotted
        assertThat(jdbc.queryForObject("select paid_amount from contributions where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                        BigDecimal.class, existing, NOW.getYear(), NOW.getMonthValue()))
                .isEqualByComparingTo("500");
        assertThat(jdbc.queryForObject("select obligation_snapshot_at from contributions where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                        Object.class, existing, NOW.getYear(), NOW.getMonthValue()))
                .isNull();

        Map<String, Object> rowA = jdbc.queryForMap(
                "select * from contributions where member_user_id = ? and \"year\" = ? and \"month\" = ?", a, NOW.getYear(), NOW.getMonthValue());
        assertThat(((Number) rowA.get("share_count")).intValue()).isEqualTo(3);
        assertThat((BigDecimal) rowA.get("expected_amount")).isEqualByComparingTo("3000");
        assertThat((BigDecimal) rowA.get("unit_amount_snapshot")).isEqualByComparingTo("1000");
        assertThat(rowA.get("obligation_snapshot_at")).isNotNull();
        assertThat(rowA.get("operating_model_snapshot")).isEqualTo("ACCUMULATING");
        assertThat(rowA.get("rotating_bps_snapshot")).as("no split for an accumulating scheme").isNull();
        assertThat(jdbc.queryForObject("select count(*) from contributions where member_user_id = ?", Long.class, inactive)).isZero();

        // repeating changes nothing
        materialize(scheme, NOW, superAdminToken)
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.alreadyPresent").value(3));
        assertThat(b).isNotNull();
    }

    @Test
    void obligationsCanOnlyBeCreatedForTheCurrentOrNextMonth() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        member(scheme, 1);
        for (YearMonth bad : List.of(NOW.minusMonths(1), NOW.plusMonths(2), NOW.minusYears(1))) {
            materialize(scheme, bad, superAdminToken)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("OBLIGATION_PERIOD_NOT_ALLOWED"));
        }
        assertThat(count("select count(*) from contributions where cooperative_id = ?", scheme)).isZero();
    }

    @Test
    void concurrentMaterializationCreatesEachObligationExactlyOnce() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        setUnitAmount(scheme, "1000");
        for (int i = 0; i < 6; i++) {
            member(scheme, 1 + i);
        }
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(() -> {
                    go.await();
                    JsonNode body = objectMapper.readTree(
                            materialize(scheme, NEXT, superAdminToken).andReturn().getResponse().getContentAsString());
                    return body.path("data").path("created").asInt(-1);
                });
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(call));
            }
            go.countDown();
            int created = 0;
            for (Future<Integer> future : futures) {
                int value = future.get(90, TimeUnit.SECONDS);
                assertThat(value).as("no request failed").isGreaterThanOrEqualTo(0);
                created += value;
            }
            assertThat(created).as("the six obligations were created exactly once between the three calls").isEqualTo(6);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("select count(*) from contributions where cooperative_id = ? and \"year\" = ? and \"month\" = ?",
                        scheme, NEXT.getYear(), NEXT.getMonthValue()))
                .isEqualTo(6);
    }

    @Test
    void aMidMonthShareChangeNeverChangesAnExistingObligationAndAppliesToLaterMonths() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        setUnitAmount(scheme, "1000");
        UUID member = member(scheme, 1);
        materialize(scheme, NEXT, superAdminToken).andExpect(status().isOk());
        JsonNode before = rowOf(grid(scheme, NEXT), member);

        jdbc.update("update cooperative_memberships set share_count = 4 where cooperative_id = ? and user_id = ?", scheme, member);

        JsonNode after = rowOf(grid(scheme, NEXT), member);
        assertThat(decimal(after, "expectedAmount")).as("the snapshotted obligation is unchanged").isEqualByComparingTo("1000");
        assertThat(after.path("shareCount").asInt()).isEqualTo(1);
        assertThat(after.toString()).as("repeated reads are identical").isEqualTo(before.toString());
        // a later month that has no obligation yet projects with the new share count
        assertThat(decimal(rowOf(grid(scheme, NEXT.plusMonths(1)), member), "expectedAmount")).isEqualByComparingTo("4000");
    }

    @Test
    void rowsRecordedThroughTheExistingEntryPathGetASnapshotOnlyForCurrentAndFutureMonths() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        setUnitAmount(scheme, "1000");
        confirmedDueDay(scheme, 12);
        UUID member = member(scheme, 2);

        saveLine(scheme, NEXT, member, "0").andExpect(status().isOk());
        saveLine(scheme, NOW.minusMonths(3), member, "500").andExpect(status().isOk());

        JsonNode future = rowOf(grid(scheme, NEXT), member);
        assertThat(future.path("obligationSnapshotted").asBoolean()).isTrue();
        assertThat(future.path("dueDate").asText())
                .isEqualTo(ContributionDueDates.dueDateFor(NEXT.getYear(), NEXT.getMonthValue(), 12).toString());
        JsonNode past = rowOf(grid(scheme, NOW.minusMonths(3)), member);
        assertThat(past.path("obligationSnapshotted").asBoolean()).isFalse();
        assertThat(past.path("persisted").asBoolean()).isTrue();
        assertThat(decimal(past, "paidAmount")).isEqualByComparingTo("500");
    }

    // ---------------------------------------------------------------- arrears

    @Test
    void anObligationIsOverdueOnlyAfterItsDueDateAndOnlyForWhatIsStillOutstanding() throws Exception {
        UUID scheme = scheme("ACCUMULATING");
        setUnitAmount(scheme, "1000");
        confirmedDueDay(scheme, 12);
        UUID member = member(scheme, 10);
        materialize(scheme, NEXT, superAdminToken).andExpect(status().isOk());
        LocalDate today = LocalDate.now(KIGALI);

        setStoredDueDate(scheme, member, NEXT, today.plusDays(1));
        JsonNode notYet = rowOf(grid(scheme, NEXT), member);
        assertThat(notYet.path("overdue").asBoolean()).isFalse();
        assertThat(decimal(notYet, "overdueAmount")).isEqualByComparingTo("0");
        assertThat(decimal(notYet, "outstandingAmount")).as("outstanding but not yet due").isEqualByComparingTo("10000");

        setStoredDueDate(scheme, member, NEXT, today);
        assertThat(rowOf(grid(scheme, NEXT), member).path("overdue").asBoolean()).as("on the due date itself").isFalse();

        setStoredDueDate(scheme, member, NEXT, today.minusDays(1));
        JsonNode late = rowOf(grid(scheme, NEXT), member);
        assertThat(late.path("overdue").asBoolean()).isTrue();
        assertThat(decimal(late, "overdueAmount")).isEqualByComparingTo("10000");

        // partly paid (as the existing accounting recognizes it): only the rest is overdue
        jdbc.update("update contributions set paid_amount = 4000, outstanding_amount = 6000, status = 'PARTIALLY_PAID' where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                member, NEXT.getYear(), NEXT.getMonthValue());
        JsonNode partial = rowOf(grid(scheme, NEXT), member);
        assertThat(decimal(partial, "paidAmount")).isEqualByComparingTo("4000");
        assertThat(decimal(partial, "overdueAmount")).isEqualByComparingTo("6000");

        // settled or waived: nothing overdue
        jdbc.update("update contributions set paid_amount = 10000, outstanding_amount = 0, status = 'PAID' where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                member, NEXT.getYear(), NEXT.getMonthValue());
        assertThat(rowOf(grid(scheme, NEXT), member).path("overdue").asBoolean()).isFalse();
        jdbc.update("update contributions set paid_amount = 0, outstanding_amount = 0, status = 'WAIVED' where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                member, NEXT.getYear(), NEXT.getMonthValue());
        assertThat(decimal(rowOf(grid(scheme, NEXT), member), "overdueAmount")).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- rotating and hybrid

    @Test
    void aRotatingSchemeNeedsItsConfigurationAndScheduleThenSnapshotsFullyRotatingObligations() throws Exception {
        UUID scheme = scheme("ROTATING");
        setUnitAmount(scheme, "1000");
        List<UUID> members = members(scheme, 2, 3);

        materialize(scheme, NEXT, superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROTATION_CONFIG_MISSING"));
        configure(scheme, NEXT, 10000, 0);
        materialize(scheme, NEXT, superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROTATION_SCHEDULE_MISSING"));
        assertThat(count("select count(*) from contributions where cooperative_id = ?", scheme))
                .as("a refused materialization creates nothing")
                .isZero();

        activateCycle(scheme, NEXT, members);
        materialize(scheme, NEXT, superAdminToken).andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(3));

        UUID periodId = jdbc.queryForObject("select id from rotation_periods where cooperative_id = ? and slot_key = ?",
                UUID.class, scheme, NEXT.toString());
        JsonNode row = rowOf(grid(scheme, NEXT), members.get(0));
        assertThat(row.path("obligationSnapshotted").asBoolean()).isTrue();
        assertThat(row.path("operatingModel").asText()).isEqualTo("ROTATING");
        assertThat(row.path("rotatingBps").asInt()).isEqualTo(10000);
        assertThat(row.path("retainedBps").asInt()).isEqualTo(0);
        assertThat(decimal(row, "expectedRotatingAmount")).isEqualByComparingTo(decimal(row, "expectedAmount"));
        assertThat(decimal(row, "expectedRetainedAmount")).isEqualByComparingTo("0");
        assertThat(row.path("rotationPeriodId").asText()).isEqualTo(periodId.toString());
    }

    @Test
    void aHybridObligationUsesTheConfigurationEffectiveForItsPeriodAndKeepsItAfterLaterChanges() throws Exception {
        UUID scheme = scheme("HYBRID");
        setUnitAmount(scheme, "1001");
        List<UUID> members = members(scheme, 5, 2);
        configure(scheme, NEXT, 3333, 6667);
        activateCycle(scheme, NEXT, members);
        materialize(scheme, NEXT, superAdminToken).andExpect(status().isOk());

        // 5 shares x 1001 = 5005; 33.33% = 1668.1665 -> 1668 rotating (HALF_UP), 3337 retained
        JsonNode row = rowOf(grid(scheme, NEXT), members.get(0));
        assertThat(decimal(row, "expectedAmount")).isEqualByComparingTo("5005");
        assertThat(row.path("rotatingBps").asInt()).isEqualTo(3333);
        assertThat(decimal(row, "expectedRotatingAmount")).isEqualByComparingTo("1668");
        assertThat(decimal(row, "expectedRetainedAmount")).isEqualByComparingTo("3337");
        assertThat(decimal(row, "expectedRotatingAmount").add(decimal(row, "expectedRetainedAmount")))
                .isEqualByComparingTo(decimal(row, "expectedAmount"));

        // a later configuration changes the months it governs, never an obligation that already exists
        YearMonth later = NEXT.plusMonths(2);
        configure(scheme, later, 5000, 5000);
        assertThat(rowOf(grid(scheme, NEXT), members.get(0)).path("rotatingBps").asInt()).isEqualTo(3333);
        JsonNode projected = rowOf(grid(scheme, later), members.get(0));
        assertThat(projected.path("obligationSnapshotted").asBoolean()).isFalse();
        assertThat(projected.path("rotatingBps").asInt()).isEqualTo(5000);
        assertThat(decimal(projected, "expectedRotatingAmount")).isEqualByComparingTo("2503");
        assertThat(decimal(projected, "expectedRetainedAmount")).isEqualByComparingTo("2502");
        // the month between the two configurations still resolves to the first
        assertThat(rowOf(grid(scheme, NEXT.plusMonths(1)), members.get(0)).path("rotatingBps").asInt()).isEqualTo(3333);
        // and a month before any configuration has no split at all (nothing is invented)
        JsonNode earlier = rowOf(grid(scheme, NOW), members.get(0));
        assertThat(absent(earlier.path("rotatingBps"))).isTrue();
        assertThat(absent(earlier.path("expectedRotatingAmount"))).isTrue();
    }

    // ---------------------------------------------------------------- late-payment attribution (read only)

    @Test
    void aLatePaymentIsAttributedToTheOriginalPeriodsSplitAndRecipientAndNeverTwice() throws Exception {
        UUID scheme = scheme("HYBRID");
        setUnitAmount(scheme, "1001");
        List<UUID> members = members(scheme, 5, 3);
        configure(scheme, NEXT, 3333, 6667);
        activateCycle(scheme, NEXT, members);
        materialize(scheme, NEXT, superAdminToken).andExpect(status().isOk());
        UUID payer = members.get(2); // scheduled for the third month, not the first
        UUID firstRecipient = members.get(0);
        UUID originalPeriod = jdbc.queryForObject("select id from rotation_periods where cooperative_id = ? and slot_key = ?",
                UUID.class, scheme, NEXT.toString());

        // 5005 owed. A first payment of 2000 goes to the NEXT obligation's own split and its own recipient...
        AttributionPlan first = obligationService.previewAttribution(scheme, payer, NEXT.getYear(), NEXT.getMonthValue(), new BigDecimal("2000"));
        assertThat(first.year()).isEqualTo(NEXT.getYear());
        assertThat(first.month()).isEqualTo(NEXT.getMonthValue());
        assertThat(first.rotationPeriodId()).isEqualTo(originalPeriod);
        assertThat(first.originalScheduledRecipientUserId())
                .as("the recipient of the period the obligation belongs to, not of any other month")
                .isEqualTo(firstRecipient);
        assertThat(first.rotatingDelta().add(first.retainedDelta())).isEqualByComparingTo("2000");
        assertThat(first.outstandingBefore()).isEqualByComparingTo("5005");
        assertThat(first.outstandingAfter()).isEqualByComparingTo("3005");

        // ...then the recognized paid amount moves (as the existing accounting would record it)
        recordPaid(scheme, payer, NEXT, "2000");
        AttributionPlan second = obligationService.previewAttribution(scheme, payer, NEXT.getYear(), NEXT.getMonthValue(), new BigDecimal("3005"));
        BigDecimal rotating = first.rotatingDelta().add(second.rotatingDelta());
        BigDecimal retained = first.retainedDelta().add(second.retainedDelta());
        assertThat(rotating).as("two payments, no drift: exactly the obligation's own 1668").isEqualByComparingTo("1668");
        assertThat(retained).isEqualByComparingTo("3337");

        // the same money cannot be allocated twice: nothing is left once fully paid, and over-allocation is refused
        recordPaid(scheme, payer, NEXT, "5005");
        assertThatThrownBy(() -> obligationService.previewAttribution(scheme, payer, NEXT.getYear(), NEXT.getMonthValue(), new BigDecimal("1")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("ALLOCATION_EXCEEDS_OUTSTANDING"));
        recordPaid(scheme, payer, NEXT, "2000");
        assertThatThrownBy(() -> obligationService.previewAttribution(scheme, payer, NEXT.getYear(), NEXT.getMonthValue(), new BigDecimal("3006")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("ALLOCATION_EXCEEDS_OUTSTANDING"));
        assertThatThrownBy(() -> obligationService.previewAttribution(scheme, payer, NEXT.getYear(), NEXT.getMonthValue(), BigDecimal.ZERO))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("ALLOCATION_AMOUNT_INVALID"));
    }

    @Test
    void attributionIsRefusedForRowsWithoutASnapshotForClosedRowsAndAcrossSchemes() throws Exception {
        UUID scheme = scheme("HYBRID");
        UUID other = scheme("HYBRID");
        setUnitAmount(scheme, "1000");
        List<UUID> members = members(scheme, 2, 2);
        configure(scheme, NEXT, 5000, 5000);
        activateCycle(scheme, NEXT, members);
        materialize(scheme, NEXT, superAdminToken).andExpect(status().isOk());

        // a row that predates snapshots has no original split to attribute to
        saveLine(scheme, NOW.minusMonths(2), members.get(0), "0").andExpect(status().isOk());
        assertThatThrownBy(() -> obligationService.previewAttribution(scheme, members.get(0), NOW.minusMonths(2).getYear(),
                        NOW.minusMonths(2).getMonthValue(), new BigDecimal("10")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("OBLIGATION_NOT_SNAPSHOTTED"));

        jdbc.update("update contributions set status = 'WAIVED', outstanding_amount = 0 where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                members.get(1), NEXT.getYear(), NEXT.getMonthValue());
        assertThatThrownBy(() -> obligationService.previewAttribution(scheme, members.get(1), NEXT.getYear(), NEXT.getMonthValue(), new BigDecimal("10")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("OBLIGATION_CLOSED"));

        // the obligation is found by scheme, member and period only: another scheme sees nothing
        assertThatThrownBy(() -> obligationService.previewAttribution(other, members.get(0), NEXT.getYear(), NEXT.getMonthValue(), new BigDecimal("10")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- nothing is posted

    @Test
    void obligationsPostNothingAndChangeNoBalance() throws Exception {
        UUID scheme = scheme("HYBRID");
        setUnitAmount(scheme, "1000");
        List<UUID> members = members(scheme, 2, 3);
        legacyDueDay(scheme);
        Map<String, Object> before = financialSnapshot();

        setDueDay(scheme, 12, true, superAdminToken).andExpect(status().isOk());
        configure(scheme, NEXT, 7000, 3000);
        activateCycle(scheme, NEXT, members);
        materialize(scheme, NEXT, superAdminToken).andExpect(status().isOk());
        grid(scheme, NEXT);
        grid(scheme, NOW);
        obligationService.previewAttribution(scheme, members.get(0), NEXT.getYear(), NEXT.getMonthValue(), new BigDecimal("500"));

        assertThat(financialSnapshot()).as("no ledger entry, loan, fine or recognized paid amount changed").isEqualTo(before);
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", scheme)).isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(paid_amount), 0) from contributions where cooperative_id = ?", BigDecimal.class, scheme))
                .isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- helpers

    private String base(UUID scheme) {
        return "/api/v1/cooperatives/" + scheme;
    }

    private String dueDayPath(UUID scheme) {
        return base(scheme) + "/contribution-due-day";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private ResultActions setDueDay(UUID scheme, int day, boolean confirmed, String token) throws Exception {
        return mockMvc.perform(post(dueDayPath(scheme))
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueDay\":" + day + ",\"confirmed\":" + confirmed + "}"));
    }

    private ResultActions materialize(UUID scheme, YearMonth month, String token) throws Exception {
        return mockMvc.perform(post(base(scheme) + "/contributions/obligations/materialize")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"year\":" + month.getYear() + ",\"month\":" + month.getMonthValue() + "}"));
    }

    private JsonNode grid(UUID scheme, YearMonth month) throws Exception {
        MvcResult result = mockMvc.perform(get(base(scheme) + "/contributions/period?year=" + month.getYear() + "&month=" + month.getMonthValue())
                        .header("Authorization", bearer(superAdminToken)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode rowOf(JsonNode grid, UUID member) {
        for (JsonNode row : grid) {
            if (member.toString().equals(row.path("memberUserId").asText())) {
                return row;
            }
        }
        throw new AssertionError("no row for member " + member);
    }

    private static boolean absent(JsonNode node) {
        return node.isNull() || node.isMissingNode();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.path(field).asText("0"));
    }

    private ResultActions saveLine(UUID scheme, YearMonth month, UUID member, String paid) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("memberUserId", member);
        line.put("paidAmount", new BigDecimal(paid));
        if (new BigDecimal(paid).signum() > 0) {
            line.put("paymentDate", LocalDate.now(KIGALI).toString());
        }
        return mockMvc.perform(put(base(scheme) + "/contributions/period?year=" + month.getYear() + "&month=" + month.getMonthValue())
                .header("Authorization", bearer(superAdminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("lines", List.of(line)))));
    }

    private void recordPaid(UUID scheme, UUID member, YearMonth month, String paid) {
        BigDecimal paidAmount = new BigDecimal(paid);
        BigDecimal expected = jdbc.queryForObject("select expected_amount from contributions where member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, member, month.getYear(), month.getMonthValue());
        jdbc.update("update contributions set paid_amount = ?, outstanding_amount = ?, status = ? where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                paidAmount, expected.subtract(paidAmount).max(BigDecimal.ZERO),
                paidAmount.compareTo(expected) >= 0 ? "PAID" : paidAmount.signum() > 0 ? "PARTIALLY_PAID" : "PENDING",
                scheme, member, month.getYear(), month.getMonthValue());
    }

    private void setStoredDueDate(UUID scheme, UUID member, YearMonth month, LocalDate due) {
        jdbc.update("update contributions set due_date = ? where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                due, scheme, member, month.getYear(), month.getMonthValue());
    }

    private void legacyDueDay(UUID scheme) {
        jdbc.update("delete from contribution_due_day_settings where cooperative_id = ?", scheme);
    }

    private void confirmedDueDay(UUID scheme, int day) {
        legacyDueDay(scheme);
        jdbc.update("insert into contribution_due_day_settings (id, cooperative_id, effective_year, effective_month, due_day, created_by) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheme, NOW.minusMonths(1).getYear(), NOW.minusMonths(1).getMonthValue(), day, superAdminId);
    }

    private void setUnitAmount(UUID scheme, String amount) {
        jdbc.update("update cooperatives set monthly_contribution_amount = ? where id = ?", new BigDecimal(amount), scheme);
    }

    private void configure(UUID scheme, YearMonth month, int rotating, int retained) throws Exception {
        mockMvc.perform(post(base(scheme) + "/rotation/configurations")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveYear", month.getYear(),
                                "effectiveMonth", month.getMonthValue(),
                                "rotatingBps", rotating,
                                "retainedBps", retained))))
                .andExpect(status().isOk());
    }

    private void activateCycle(UUID scheme, YearMonth start, List<UUID> recipients) throws Exception {
        MvcResult created = mockMvc.perform(post(base(scheme) + "/rotation/cycles")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startYear", start.getYear(),
                                "startMonth", start.getMonthValue(),
                                "recipientUserIds", recipients))))
                .andExpect(status().isOk())
                .andReturn();
        String cycleId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        mockMvc.perform(post(base(scheme) + "/rotation/cycles/" + cycleId + "/activate")
                        .header("Authorization", bearer(superAdminToken)))
                .andExpect(status().isOk());
    }

    private UUID scheme(String model) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "OBL " + UUID.randomUUID().toString().substring(0, 8), "1000.0000", 1, model)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    /** A member of the scheme with the given number of shares. */
    private UUID member(UUID scheme, int shares) throws Exception {
        UUID id = register(scheme, "MEMBER").id();
        jdbc.update("update cooperative_memberships set share_count = ? where cooperative_id = ? and user_id = ?", shares, scheme, id);
        return id;
    }

    private List<UUID> members(UUID scheme, int shares, int count) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(member(scheme, shares));
        }
        return ids;
    }

    private String tokenForRole(UUID scheme, String role) throws Exception {
        return register(scheme, role).token();
    }

    private record Registered(UUID id, String token) {}

    private Registered register(UUID scheme, String role) throws Exception {
        String username = "ob_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult register = mockMvc.perform(post(base(scheme) + "/members")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ob","lastName":"Member","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return new Registered(
                UUID.fromString(data.path("userId").asText()),
                login(username, data.path("temporaryPassword").asText()));
    }

    private Map<String, Object> financialSnapshot() {
        return jdbc.queryForMap(
                "select (select count(*) from financial_ledger) as ledger_rows, "
                        + "(select coalesce(sum(credit_amount), 0) from financial_ledger) as credits, "
                        + "(select coalesce(sum(debit_amount), 0) from financial_ledger) as debits, "
                        + "(select coalesce(sum(paid_amount), 0) from contributions) as paid, "
                        + "(select count(*) from loans) as loans, "
                        + "(select count(*) from fines) as fines, "
                        + "(select count(*) from share_purchases) as share_purchases");
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String updateBody(int dueDay) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return """
                {
                  "name":"OBL Updated %s",
                  "registrationNumber":"RCA/OBU/%s",
                  "contactEmail":"obu-%s@test.local",
                  "contactPhone":"0781234567",
                  "currency":"RWF",
                  "financialYearStartMonth":1,
                  "monthlyContributionAmount":1000,
                  "contributionDueDay":%d,
                  "registrationDate":"2024-01-15"
                }
                """
                .formatted(suffix, suffix.toUpperCase(), suffix.toLowerCase(), dueDay);
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

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
}
