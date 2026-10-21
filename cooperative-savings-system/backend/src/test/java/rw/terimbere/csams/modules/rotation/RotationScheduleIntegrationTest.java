package rw.terimbere.csams.modules.rotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

/**
 * Task 3B-1: rotation configuration, monthly schedule and recipient assignments. Storage only: nothing here may post,
 * allocate or distribute money, and the tests assert that. Runs on H2 (PostgreSQL mode); PostgreSQL is not verified.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RotationScheduleIntegrationTest {

    private static final YearMonth NOW = YearMonth.now(ZoneId.of("Africa/Kigali"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void hybridPercentagesAreStoredInBasisPointsAndInvalidSplitsAreRejected() throws Exception {
        UUID hybrid = scheme("HYBRID");
        YearMonth month = NOW.plusMonths(1);

        configure(hybrid, month, 7000, 3000, superAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rotatingBps").value(7000))
                .andExpect(jsonPath("$.data.retainedBps").value(3000));

        for (int[] bad : new int[][] {{6000, 3000}, {10000, 0}, {0, 10000}, {5000, 6000}}) {
            configure(hybrid, month.plusMonths(1), bad[0], bad[1], superAdminToken).andExpect(status().is4xxClientError());
        }
        // a Rotating scheme distributes everything
        UUID rotating = scheme("ROTATING");
        configure(rotating, month, 10000, 0, superAdminToken).andExpect(status().isOk());
        configure(rotating, month.plusMonths(1), 8000, 2000, superAdminToken).andExpect(status().isBadRequest());
        assertThat(count("select count(*) from rotation_configurations where cooperative_id = ?", rotating)).isEqualTo(1);
        assertThat(count("select count(*) from rotation_configurations where cooperative_id = ?", hybrid)).isEqualTo(1);
    }

    @Test
    void theConfigurationForAPeriodIsTheLatestOneEffectiveOnOrBeforeIt() throws Exception {
        UUID hybrid = scheme("HYBRID");
        YearMonth a = NOW.plusMonths(1);
        YearMonth b = NOW.plusMonths(4);
        configure(hybrid, a, 7000, 3000, superAdminToken).andExpect(status().isOk());
        configure(hybrid, b, 5000, 5000, superAdminToken).andExpect(status().isOk());

        assertEffective(hybrid, a.minusMonths(1), null);
        assertEffective(hybrid, a, 7000);
        assertEffective(hybrid, a.plusMonths(2), 7000);
        assertEffective(hybrid, b, 5000);
        assertEffective(hybrid, b.plusMonths(6), 5000);
        // history is listed newest first and nothing was replaced
        mockMvc.perform(get(base(hybrid) + "/configurations").header("Authorization", bearer(superAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].rotatingBps").value(5000));
    }

    @Test
    void storedConfigurationsAreImmutableAndOnlyFutureMonthsCanBeConfigured() throws Exception {
        UUID hybrid = scheme("HYBRID");
        YearMonth month = NOW.plusMonths(2);
        configure(hybrid, month, 7000, 3000, superAdminToken).andExpect(status().isOk());

        // a different value for the same month is refused; an identical retry is a success without a second row
        configure(hybrid, month, 6000, 4000, superAdminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROTATION_CONFIG_EXISTS"));
        configure(hybrid, month, 7000, 3000, superAdminToken).andExpect(status().isOk());
        assertThat(count("select count(*) from rotation_configurations where cooperative_id = ?", hybrid)).isEqualTo(1);
        assertThat(count("select rotating_bps from rotation_configurations where cooperative_id = ?", hybrid))
                .isEqualTo(7000);

        // the current month has begun, so it (and the past) cannot be configured
        for (YearMonth begun : List.of(NOW, NOW.minusMonths(1))) {
            configure(hybrid, begun, 7000, 3000, superAdminToken)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ROTATION_PERIOD_STARTED"));
        }
    }

    // ---------------------------------------------------------------- schedule generation

    @Test
    void aCycleGeneratesOneMonthlyPeriodPerRecipientInTheGivenOrderAcrossYearEnds() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 4);
        YearMonth start = YearMonth.of(NOW.getYear() + 1, 11); // November, so the cycle crosses a year end

        createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk());
        JsonNode cycle = data(createCycleResult);
        assertThat(cycle.path("status").asText()).isEqualTo("DRAFT");
        assertThat(cycle.path("periods")).hasSize(4);
        YearMonth expected = start;
        for (int i = 0; i < 4; i++) {
            JsonNode period = cycle.path("periods").get(i);
            assertThat(period.path("sequenceNumber").asInt()).isEqualTo(i + 1);
            assertThat(period.path("year").asInt()).isEqualTo(expected.getYear());
            assertThat(period.path("month").asInt()).isEqualTo(expected.getMonthValue());
            assertThat(period.path("recipientUserId").asText()).isEqualTo(members.get(i).toString());
            expected = expected.plusMonths(1);
        }
        // a draft holds no month
        assertThat(count("select count(*) from rotation_periods where cycle_id = ? and slot_key is not null",
                        UUID.fromString(cycle.path("id").asText())))
                .isZero();
    }

    @Test
    void recipientsMustBeDistinctActiveMembersOfTheSameScheme() throws Exception {
        UUID scheme = scheme("ROTATING");
        UUID other = scheme("ROTATING");
        List<UUID> members = members(scheme, 3);
        UUID stranger = members(other, 1).get(0);
        YearMonth start = NOW.plusMonths(2);

        createCycle(scheme, start, List.of(members.get(0), members.get(0)), superAdminToken)
                .andExpect(status().isBadRequest());
        createCycle(scheme, start, List.of(members.get(0), stranger), superAdminToken)
                .andExpect(status().isBadRequest());
        jdbc.update("update cooperative_memberships set membership_status = 'INACTIVE' where cooperative_id = ? and user_id = ?",
                scheme, members.get(2));
        createCycle(scheme, start, List.of(members.get(0), members.get(2)), superAdminToken)
                .andExpect(status().isBadRequest());
        createCycle(scheme, start, List.of(), superAdminToken).andExpect(status().isBadRequest());
        assertThat(count("select count(*) from rotation_cycles where cooperative_id = ?", scheme)).isZero();
    }

    @Test
    void aCycleCannotStartInAMonthThatHasBegun() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 2);
        for (YearMonth begun : List.of(NOW, NOW.minusMonths(3))) {
            createCycle(scheme, begun, members, superAdminToken)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ROTATION_PERIOD_STARTED"));
        }
    }

    // ---------------------------------------------------------------- operating model gate

    @Test
    void accumulatingAndUnclassifiedLegacySchemesCannotUseRotation() throws Exception {
        UUID accumulating = scheme("ACCUMULATING");
        UUID legacy = scheme("ACCUMULATING");
        jdbc.update("update cooperatives set operating_model = null, operating_model_unclassified = true where id = ?", legacy);
        YearMonth month = NOW.plusMonths(1);

        configure(accumulating, month, 10000, 0, superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROTATION_NOT_APPLICABLE"));
        configure(legacy, month, 10000, 0, superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPERATING_MODEL_NOT_CONFIGURED"));
        createCycle(legacy, month, List.of(members(legacy, 1).get(0)), superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPERATING_MODEL_NOT_CONFIGURED"));
        // reading is harmless and simply empty
        mockMvc.perform(get(base(accumulating) + "/cycles").header("Authorization", bearer(superAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------------------------------------------------------------- activation, overlap, cancellation, replacement

    @Test
    void activationNeedsAnEffectiveConfigurationAndHoldsTheMonths() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 2);
        YearMonth start = NOW.plusMonths(2);
        UUID cycle = id(createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk()));

        action(scheme, cycle, "activate", null, superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROTATION_CONFIG_MISSING"));

        configure(scheme, start, 10000, 0, superAdminToken).andExpect(status().isOk());
        action(scheme, cycle, "activate", null, superAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(count("select count(*) from rotation_periods where cycle_id = ? and slot_key is not null", cycle))
                .isEqualTo(2);
        action(scheme, cycle, "activate", null, superAdminToken).andExpect(status().isConflict());
    }

    @Test
    void overlappingActiveCyclesAreRefusedAndTheDatabaseBacksItUp() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 3);
        YearMonth start = NOW.plusMonths(2);
        configure(scheme, start, 10000, 0, superAdminToken).andExpect(status().isOk());
        UUID first = id(createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk()));
        UUID overlapping = id(createCycle(scheme, start.plusMonths(1), members, superAdminToken).andExpect(status().isOk()));
        action(scheme, first, "activate", null, superAdminToken).andExpect(status().isOk());

        action(scheme, overlapping, "activate", null, superAdminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROTATION_SCHEDULE_OVERLAP"));

        // even bypassing the service, two live periods cannot share a month in one scheme
        UUID draftPeriod = UUID.fromString(jdbc.queryForObject(
                "select cast(id as varchar) from rotation_periods where cycle_id = ? and sequence_number = 1", String.class, overlapping));
        String takenKey = jdbc.queryForObject(
                "select slot_key from rotation_periods where cycle_id = ? and sequence_number = 2", String.class, first);
        assertThatThrownBy(() -> jdbc.update("update rotation_periods set slot_key = ? where id = ?", takenKey, draftPeriod))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentActivationOfOverlappingCyclesLetsExactlyOneWin() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 3);
        YearMonth start = NOW.plusMonths(2);
        configure(scheme, start, 10000, 0, superAdminToken).andExpect(status().isOk());
        UUID a = id(createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk()));
        UUID b = id(createCycle(scheme, start.plusMonths(1), members, superAdminToken).andExpect(status().isOk()));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> attempts = List.of(
                    () -> activateStatus(scheme, a, go), () -> activateStatus(scheme, b, go));
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("select count(*) from rotation_cycles where cooperative_id = ? and status = 'ACTIVE'", scheme))
                .isEqualTo(1);
        assertThat(count(
                        "select count(*) from (select slot_key from rotation_periods where cooperative_id = ? "
                                + "and slot_key is not null group by slot_key having count(*) > 1) d",
                        scheme))
                .as("no month is held twice")
                .isZero();
    }

    @Test
    void aCancelledCycleKeepsItsHistoryAndAllowsAReplacementForTheSameMonths() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 3);
        YearMonth start = NOW.plusMonths(2);
        configure(scheme, start, 10000, 0, superAdminToken).andExpect(status().isOk());
        UUID first = id(createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk()));
        action(scheme, first, "activate", null, superAdminToken).andExpect(status().isOk());

        action(scheme, first, "cancel", Map.of("reason", "Wrong order"), superAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // history is kept: the cycle, its periods and the reason are still there, but the months are released
        assertThat(count("select count(*) from rotation_periods where cycle_id = ?", first)).isEqualTo(3);
        assertThat(count("select count(*) from rotation_periods where cycle_id = ? and status = 'CANCELLED' and slot_key is null", first))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select cancel_reason from rotation_cycles where id = ?", String.class, first))
                .isEqualTo("Wrong order");

        // a replacement for the very same months (and recipients) can now be created and activated
        UUID replacement = id(createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk()));
        action(scheme, replacement, "activate", null, superAdminToken).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select cycle_number from rotation_cycles where id = ?", Integer.class, replacement))
                .isEqualTo(2);
        assertThat(count("select count(*) from rotation_cycles where cooperative_id = ?", scheme)).isEqualTo(2);

        // a cancelled cycle cannot be revived, and a cancellation needs a reason
        action(scheme, first, "activate", null, superAdminToken).andExpect(status().isConflict());
        action(scheme, replacement, "cancel", Map.of("reason", " "), superAdminToken).andExpect(status().isBadRequest());
    }

    @Test
    void aCycleWhoseFirstMonthHasBegunCanNeitherBeActivatedNorCancelled() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 2);
        YearMonth start = NOW.plusMonths(2);
        configure(scheme, start, 10000, 0, superAdminToken).andExpect(status().isOk());
        UUID draft = id(createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk()));
        UUID active = id(createCycle(scheme, start.plusMonths(5), members, superAdminToken).andExpect(status().isOk()));
        configure(scheme, start.plusMonths(5), 10000, 0, superAdminToken).andExpect(status().isOk());
        action(scheme, active, "activate", null, superAdminToken).andExpect(status().isOk());

        // time passes: both cycles' first months are now in the past (simulated directly in the database)
        jdbc.update("update rotation_cycles set start_year = ?, start_month = ? where id in (?, ?)",
                NOW.minusMonths(1).getYear(), NOW.minusMonths(1).getMonthValue(), draft, active);

        action(scheme, draft, "activate", null, superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROTATION_PERIOD_STARTED"));
        action(scheme, active, "cancel", Map.of("reason", "Too late"), superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROTATION_PERIOD_STARTED"));
        assertThat(jdbc.queryForObject("select status from rotation_cycles where id = ?", String.class, active)).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- recipient changes

    @Test
    void aRecipientChangeNeedsAReasonIsAuditedAndKeepsTheScheduleIdentity() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 4);
        YearMonth start = NOW.plusMonths(2);
        configure(scheme, start, 10000, 0, superAdminToken).andExpect(status().isOk());
        JsonNode cycle = data(createCycle(scheme, start, members.subList(0, 3), superAdminToken).andExpect(status().isOk()));
        UUID cycleId = UUID.fromString(cycle.path("id").asText());
        action(scheme, cycleId, "activate", null, superAdminToken).andExpect(status().isOk());
        UUID period = UUID.fromString(cycle.path("periods").get(1).path("id").asText());

        // a reason is mandatory
        changeRecipient(scheme, period, members.get(3), " ", superAdminToken).andExpect(status().isBadRequest());
        // no duplicates within the cycle, no no-op, no stranger, no inactive member
        changeRecipient(scheme, period, members.get(0), "Swap", superAdminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROTATION_RECIPIENT_DUPLICATE"));
        changeRecipient(scheme, period, members.get(1), "Same", superAdminToken).andExpect(status().isBadRequest());
        changeRecipient(scheme, period, members(scheme("ROTATING"), 1).get(0), "Stranger", superAdminToken)
                .andExpect(status().isBadRequest());

        changeRecipient(scheme, period, members.get(3), "Member 2 is travelling", superAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipientUserId").value(members.get(3).toString()));

        // identity preserved: same period row, same month and sequence; history and audit are complete
        assertThat(jdbc.queryForObject("select cast(scheduled_recipient_user_id as varchar) from rotation_periods where id = ?",
                        String.class, period))
                .isEqualTo(members.get(3).toString());
        assertThat(count("select count(*) from rotation_periods where cycle_id = ?", cycleId)).isEqualTo(3);
        mockMvc.perform(get(base(scheme) + "/periods/" + period + "/recipient-changes")
                        .header("Authorization", bearer(superAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].previousRecipientUserId").value(members.get(1).toString()))
                .andExpect(jsonPath("$.data[0].newRecipientUserId").value(members.get(3).toString()))
                .andExpect(jsonPath("$.data[0].reason").value("Member 2 is travelling"));
        assertThat(count("select count(*) from audit_logs where action = 'ROTATION_RECIPIENT_CHANGE' and entity_id = ? and cooperative_id = ?",
                        period, scheme))
                .isEqualTo(1);
        // the replaced member can be scheduled again nowhere in this cycle, but is free for a later change
        changeRecipient(scheme, period, members.get(1), "Back again", superAdminToken).andExpect(status().isOk());
        assertThat(count("select count(*) from rotation_recipient_changes where rotation_period_id = ?", period)).isEqualTo(2);
    }

    @Test
    void aPeriodThatHasBegunCannotBeReassigned() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 3);
        YearMonth start = NOW.plusMonths(2);
        configure(scheme, start, 10000, 0, superAdminToken).andExpect(status().isOk());
        JsonNode cycle = data(createCycle(scheme, start, members.subList(0, 2), superAdminToken).andExpect(status().isOk()));
        action(scheme, UUID.fromString(cycle.path("id").asText()), "activate", null, superAdminToken).andExpect(status().isOk());
        UUID period = UUID.fromString(cycle.path("periods").get(0).path("id").asText());
        jdbc.update("update rotation_periods set period_year = ?, period_month = ? where id = ?",
                NOW.getYear(), NOW.getMonthValue(), period);

        changeRecipient(scheme, period, members.get(2), "Too late", superAdminToken)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROTATION_PERIOD_STARTED"));
        assertThat(count("select count(*) from rotation_recipient_changes where rotation_period_id = ?", period)).isZero();
    }

    // ---------------------------------------------------------------- authorization and tenant isolation

    @Test
    void onlyAdministratorsManageButEveryActiveMemberOfTheSchemeCanView() throws Exception {
        UUID scheme = scheme("ROTATING");
        UUID otherScheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 2);
        String president = tokenFor(scheme, "PRESIDENT");
        String vice = tokenFor(scheme, "VICE_PRESIDENT");
        String member = tokenFor(scheme, "MEMBER");
        String accountant = tokenFor(scheme, "ACCOUNTANT");
        String otherPresident = tokenFor(otherScheme, "PRESIDENT");
        YearMonth start = NOW.plusMonths(2);

        // officers with COOPERATIVE_WRITE (President, Vice President) and Super Admin manage
        configure(scheme, start, 10000, 0, president).andExpect(status().isOk());
        UUID cycle = id(createCycle(scheme, start, members, vice).andExpect(status().isOk()));
        action(scheme, cycle, "activate", null, president).andExpect(status().isOk());

        // everyone else is refused, including another scheme's President
        for (String token : List.of(member, accountant, otherPresident)) {
            configure(scheme, start.plusMonths(1), 10000, 0, token).andExpect(status().isForbidden());
            createCycle(scheme, start.plusMonths(6), members, token).andExpect(status().isForbidden());
            action(scheme, cycle, "cancel", Map.of("reason", "No"), token).andExpect(status().isForbidden());
        }
        // any member of the scheme can view the active schedule; other schemes' people cannot
        for (String token : List.of(member, accountant)) {
            mockMvc.perform(get(base(scheme) + "/cycles").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
            mockMvc.perform(get(base(scheme) + "/periods?year=" + start.getYear() + "&month=" + start.getMonthValue())
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
        mockMvc.perform(get(base(scheme) + "/cycles").header("Authorization", bearer(otherPresident)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(base(scheme) + "/cycles/" + cycle).header("Authorization", bearer(otherPresident)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(base(scheme) + "/cycles"))
                .andExpect(status().is4xxClientError());
        // a cycle id of one scheme never resolves under another scheme's path
        mockMvc.perform(get(base(otherScheme) + "/cycles/" + cycle).header("Authorization", bearer(superAdminToken)))
                .andExpect(status().isNotFound());
        action(otherScheme, cycle, "cancel", Map.of("reason", "Cross"), superAdminToken).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select status from rotation_cycles where id = ?", String.class, cycle)).isEqualTo("ACTIVE");
    }

    @Test
    void membersDoNotSeeDraftOrCancelledCycles() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 2);
        String member = tokenFor(scheme, "MEMBER");
        UUID draft = id(createCycle(scheme, NOW.plusMonths(2), members, superAdminToken).andExpect(status().isOk()));

        mockMvc.perform(get(base(scheme) + "/cycles").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get(base(scheme) + "/cycles/" + draft).header("Authorization", bearer(member)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(base(scheme) + "/cycles").header("Authorization", bearer(superAdminToken)))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void repeatingACycleRequestWithTheSameKeyReturnsTheOriginalAndADifferentRequestIsRefused() throws Exception {
        UUID scheme = scheme("ROTATING");
        List<UUID> members = members(scheme, 3);
        YearMonth start = NOW.plusMonths(2);
        String key = "cycle-" + UUID.randomUUID();

        UUID first = id(createCycleWithKey(scheme, start, members, key).andExpect(status().isOk()));
        UUID replay = id(createCycleWithKey(scheme, start, members, key).andExpect(status().isOk()));
        assertThat(replay).isEqualTo(first);
        assertThat(count("select count(*) from rotation_cycles where cooperative_id = ?", scheme)).isEqualTo(1);

        createCycleWithKey(scheme, start.plusMonths(1), members, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROTATION_IDEMPOTENCY_MISMATCH"));
        // without a key a retry is a new draft: the documented limitation (drafts are harmless and cancellable)
        createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk());
        assertThat(count("select count(*) from rotation_cycles where cooperative_id = ?", scheme)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- no financial effect

    @Test
    void rotationSetupPostsNothingAndChangesNoBalance() throws Exception {
        UUID scheme = scheme("HYBRID");
        List<UUID> members = members(scheme, 3);
        Map<String, Object> before = financialSnapshot();
        YearMonth start = NOW.plusMonths(2);

        configure(scheme, start, 7000, 3000, superAdminToken).andExpect(status().isOk());
        JsonNode cycle = data(createCycle(scheme, start, members, superAdminToken).andExpect(status().isOk()));
        UUID cycleId = UUID.fromString(cycle.path("id").asText());
        action(scheme, cycleId, "activate", null, superAdminToken).andExpect(status().isOk());
        changeRecipient(scheme, UUID.fromString(cycle.path("periods").get(0).path("id").asText()),
                        members(scheme, 1).get(0), "Replace", superAdminToken)
                .andExpect(status().isOk());
        action(scheme, cycleId, "cancel", Map.of("reason", "Rebuild"), superAdminToken).andExpect(status().isOk());

        assertThat(financialSnapshot()).as("no ledger entry, contribution, loan, fine or share purchase changed")
                .isEqualTo(before);
    }

    // ---------------------------------------------------------------- helpers

    private String base(UUID scheme) {
        return "/api/v1/cooperatives/" + scheme + "/rotation";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private ResultActions configure(UUID scheme, YearMonth month, int rotating, int retained, String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effectiveYear", month.getYear());
        body.put("effectiveMonth", month.getMonthValue());
        body.put("rotatingBps", rotating);
        body.put("retainedBps", retained);
        return mockMvc.perform(post(base(scheme) + "/configurations")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void assertEffective(UUID scheme, YearMonth month, Integer expectedRotatingBps) throws Exception {
        MvcResult result = mockMvc.perform(get(base(scheme) + "/configurations/effective?year=" + month.getYear()
                                + "&month=" + month.getMonthValue())
                        .header("Authorization", bearer(superAdminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        if (expectedRotatingBps == null) {
            assertThat(node.isMissingNode() || node.isNull()).as("no configuration effective for %s", month).isTrue();
        } else {
            assertThat(node.path("rotatingBps").asInt()).as("configuration for %s", month).isEqualTo(expectedRotatingBps);
        }
    }

    private MvcResult createCycleResult;

    private ResultActions createCycle(UUID scheme, YearMonth start, List<UUID> recipients, String token) throws Exception {
        return createCycleWith(scheme, start, recipients, token, null);
    }

    private ResultActions createCycleWithKey(UUID scheme, YearMonth start, List<UUID> recipients, String key) throws Exception {
        return createCycleWith(scheme, start, recipients, superAdminToken, key);
    }

    private ResultActions createCycleWith(UUID scheme, YearMonth start, List<UUID> recipients, String token, String key)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startYear", start.getYear());
        body.put("startMonth", start.getMonthValue());
        body.put("recipientUserIds", recipients);
        var request = post(base(scheme) + "/cycles")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        ResultActions actions = mockMvc.perform(request);
        createCycleResult = actions.andReturn();
        return actions;
    }

    private ResultActions action(UUID scheme, UUID cycle, String action, Map<String, Object> body, String token) throws Exception {
        var request = post(base(scheme) + "/cycles/" + cycle + "/" + action).header("Authorization", bearer(token));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private ResultActions changeRecipient(UUID scheme, UUID period, UUID recipient, String reason, String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientUserId", recipient);
        body.put("reason", reason);
        return mockMvc.perform(put(base(scheme) + "/periods/" + period + "/recipient")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int activateStatus(UUID scheme, UUID cycle, CountDownLatch go) throws Exception {
        go.await();
        return action(scheme, cycle, "activate", null, superAdminToken).andReturn().getResponse().getStatus();
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private UUID id(ResultActions actions) throws Exception {
        return UUID.fromString(data(actions).path("id").asText());
    }

    private UUID scheme(String model) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "ROT " + UUID.randomUUID().toString().substring(0, 8), "1000.0000", 1, model)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    private List<UUID> members(UUID scheme, int n) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(register(scheme, "MEMBER").id());
        }
        return ids;
    }

    private String tokenFor(UUID scheme, String role) throws Exception {
        return register(scheme, role).token();
    }

    private record Registered(UUID id, String token) {}

    private Registered register(UUID scheme, String role) throws Exception {
        String username = "rt_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", bearer(superAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Rt","lastName":"Member","username":"%s","email":"%s@test.local",
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
                        + "(select count(*) from contributions) as contributions, "
                        + "(select coalesce(sum(paid_amount), 0) from contributions) as paid, "
                        + "(select count(*) from loans) as loans, "
                        + "(select count(*) from fines) as fines, "
                        + "(select count(*) from share_purchases) as share_purchases, "
                        + "(select count(*) from payout_runs) as payout_runs");
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
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
