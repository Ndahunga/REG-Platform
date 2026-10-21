package rw.terimbere.csams.modules.fine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import rw.terimbere.csams.modules.fine.service.FineService;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * Phase 4: the automatic late-fine job follows the effective-dated contribution due day and the Africa/Kigali calendar.
 * The clock is frozen per test; the job is called directly, exactly as the scheduler calls it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LateFineDueDayIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FineService fineService;

    @MockBean private Clock clock;

    private Scheme scheme;
    private UUID member;

    /** Setup runs on a frozen January 2026 so the scheme, its trial and its first due-day setting are consistent. */
    @BeforeEach
    void setUp() throws Exception {
        freeze("2026-01-15T10:00:00Z");
        SchemeFixtures fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
        // registration accepts 1 to 28; the due-day setting itself allows 29 to 31, which clamp to a short month's last day
        scheme = fixtures.scheme("ACCUMULATING", 28);
        jdbc.update("update contribution_due_day_settings set due_day = 31 where cooperative_id = ? and effective_year = 2026 and effective_month = 2",
                scheme.id());
        member = scheme.memberA().id();
        mockMvc.perform(put("/api/v1/cooperatives/" + scheme.id() + "/fine-settings")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoFinesEnabled":true,"fineMode":"FIXED","baseFineAmount":500.0000,"dailyIncrement":0,"graceDays":0}
                                """))
                .andReturn();
        // the due day changes to 10 from May 2026 (settings are insert-only and effective-dated)
        jdbc.update("""
                insert into contribution_due_day_settings (id, cooperative_id, effective_year, effective_month, due_day, created_by)
                values (?, ?, 2026, 5, 10, ?)
                """, UUID.randomUUID(), scheme.id(), fixtures.superAdminId());
    }

    @Test
    void aShortMonthIsDueOnItsLastDayAndTheKigaliCalendarDecidesWhenItIsOverdue() {
        UUID february = pending(2026, 2);

        // 23:59 on 28 February in Kigali (21:59 UTC): the due date itself, not yet overdue
        freeze("2026-02-28T21:59:00Z");
        fineService.generateAutomaticForEnabledCooperatives();
        assertThat(fines(february)).isZero();

        // 00:00 on 1 March in Kigali is still 28 February in UTC: the Kigali date must decide
        freeze("2026-02-28T22:00:00Z");
        fineService.generateAutomaticForEnabledCooperatives();
        assertThat(fines(february)).isEqualTo(1);
    }

    @Test
    void aDueDayChangeNeverMovesAnObligationThatAlreadyExistsButAppliesToLaterPeriods() {
        UUID march = pending(2026, 3); // governed by the day-31 setting: due 31 March
        UUID may = pending(2026, 5); // governed by the day-10 setting: due 10 May

        freeze("2026-05-10T12:00:00Z");
        fineService.generateAutomaticForEnabledCooperatives();
        assertThat(fines(march)).as("overdue since 31 March under the rule in force for March").isEqualTo(1);
        assertThat(fines(may)).as("10 May is the due date itself").isZero();

        freeze("2026-05-11T08:00:00Z");
        fineService.generateAutomaticForEnabledCooperatives();
        assertThat(fines(may)).isEqualTo(1);
        assertThat(fines(march)).isEqualTo(1);
    }

    @Test
    void aSnapshottedObligationKeepsItsOwnDueDateEvenIfTheSchemeRulesChangeLater() {
        UUID april = pending(2026, 4);
        // the obligation was created with a due date of 20 April; the scheme-wide rule would say 30 April
        jdbc.update("update contributions set obligation_snapshot_at = now(), due_date = DATE '2026-04-20' where id = ?", april);

        freeze("2026-04-21T08:00:00Z");
        fineService.generateAutomaticForEnabledCooperatives();
        assertThat(fines(april)).isEqualTo(1);
    }

    @Test
    void rerunningTheJobNeverFinesTheSameContributionTwiceAndLeavesExistingFinesAlone() {
        UUID march = pending(2026, 3);
        UUID december = pending(2025, 12); // before any due-day setting: judged by the scheme's configured day
        freeze("2026-06-01T08:00:00Z");

        fineService.generateAutomaticForEnabledCooperatives();
        long first = jdbc.queryForObject("select count(*) from fines where cooperative_id = ?", Long.class, scheme.id());
        assertThat(fines(march)).isEqualTo(1);
        assertThat(fines(december)).isEqualTo(1);
        java.math.BigDecimal total = jdbc.queryForObject("select coalesce(sum(total_amount),0) from fines where cooperative_id = ?", java.math.BigDecimal.class, scheme.id());

        // the retry after a failed or repeated run: nothing changes, nothing is duplicated
        fineService.generateAutomaticForEnabledCooperatives();
        fineService.generateAutomaticForEnabledCooperatives();
        assertThat(jdbc.queryForObject("select count(*) from fines where cooperative_id = ?", Long.class, scheme.id())).isEqualTo(first);
        assertThat(jdbc.queryForObject("select coalesce(sum(total_amount),0) from fines where cooperative_id = ?", java.math.BigDecimal.class, scheme.id()))
                .isEqualByComparingTo(total);
        // the contributions themselves are untouched
        assertThat(jdbc.queryForObject("select paid_amount from contributions where id = ?", java.math.BigDecimal.class, march)).isEqualByComparingTo("0");
    }

    @Test
    void aPaidObligationIsNeverFinedAndAMonthNotYetDueIsNotFined() {
        UUID paid = pending(2026, 3);
        jdbc.update("update contributions set paid_amount = expected_amount, outstanding_amount = 0, status = 'PAID' where id = ?", paid);
        UUID future = pending(2026, 9);
        freeze("2026-06-01T08:00:00Z");
        fineService.generateAutomaticForEnabledCooperatives();
        assertThat(fines(paid)).isZero();
        assertThat(fines(future)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private UUID pending(int year, int month) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into contributions (id, cooperative_id, member_user_id, "year", "month", expected_amount, paid_amount,
                  outstanding_amount, status, ledger_revision, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, 1000, 0, 1000, 'PENDING', 0, now(), now(), 0)
                """, id, scheme.id(), member, year, month);
        return id;
    }

    private long fines(UUID contribution) {
        return jdbc.queryForObject("select count(*) from fines where source_contribution_id = ?", Long.class, contribution);
    }

    private void freeze(String instant) {
        when(clock.instant()).thenReturn(Instant.parse(instant));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }
}
