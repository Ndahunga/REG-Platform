package rw.terimbere.csams.modules.rotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.NOW;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
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
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.cooperative.service.OperatingModelChangeService;
import rw.terimbere.csams.modules.fine.entity.FineSettings;
import rw.terimbere.csams.modules.fine.service.CreditConversionService;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * End to end: a running share-based cycle imported at migration, a turn paid from the rotation pool and netted by the
 * recipient's overdue contribution; an unpaid contribution converted to a loan; an operating model changed from a
 * future month.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShareRotationAndPolicyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;
    @Autowired private CooperativeRepository cooperativeRepository;
    @Autowired private CreditConversionService creditConversionService;
    @Autowired private OperatingModelChangeService modelChangeService;

    @Test
    void aTurnIsPaidFromThePoolAndNettedByTheRecipientsOverdueContribution() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "HYBRID", 2);
        Actor b = p.addMember(3);
        YearMonth start = NOW.minusMonths(1);
        MvcResult imported = mockMvc.perform(post(p.base() + "/rotation/migration")
                        .header("Authorization", "Bearer " + p.fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cutoverYear", NOW.getYear(), "cutoverMonth", NOW.getMonthValue(),
                                "rotatingUnitAmount", 700,
                                "cycle", Map.of(
                                        "startYear", start.getYear(), "startMonth", start.getMonthValue(), "months", 2,
                                        "payoutPerShare", 1000, "turnMode", "GROUPED_BY_MONTH",
                                        "turns", List.of(
                                                Map.of("monthIndex", 1, "memberUserId", p.payer.id(), "shares", 2),
                                                Map.of("monthIndex", 2, "memberUserId", b.id(), "shares", 3))),
                                "members", List.of()))))
                .andReturn();
        assertThat(status(imported)).as(imported.getResponse().getContentAsString()).isEqualTo(200);

        // B pays the current month: 3 x 700 = 2,100 rotates into the pool.
        p.postedFor(b, "3000", contribution(NOW, "3000"));
        // The payer still owes last month's contribution, past its due date.
        p.row(start);
        jdbc.update("update contributions set due_date = ? where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                LocalDate.now(PaymentScenario.KIGALI).minusDays(5), p.id(), p.payer.id(), start.getYear(), start.getMonthValue());

        UUID turn = jdbc.queryForObject("select id from rotation_turns where cooperative_id = ? and member_user_id = ?",
                UUID.class, p.id(), p.payer.id());
        MvcResult requested = mockMvc.perform(post(p.base() + "/rotation/payouts")
                        .header("Authorization", PaymentScenario.bearer(p.reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rotationTurnId\":\"" + turn + "\"}"))
                .andReturn();
        assertThat(status(requested)).as(requested.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.data(requested).path("amount").decimalValue()).isEqualByComparingTo("2000");
        assertThat(p.data(requested).path("arrearsNetted").decimalValue()).isEqualByComparingTo("2000");
        p.approveAndDisburse(UUID.fromString(p.data(requested).path("id").asText()), "BK-TURN");

        assertThat(p.outstanding(start)).as("the overdue contribution was settled from the payout").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select coalesce(sum(amount),0) from rotation_payout_offsets where cooperative_id = ?",
                BigDecimal.class, p.id())).isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("select count(*) from rotation_payouts where cooperative_id = ? and status = 'DISBURSED'",
                Long.class, p.id())).isEqualTo(1L);
    }

    @Test
    void anUnpaidContributionBecomesALoanWithoutMovingCash() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 2);
        YearMonth month = NOW.minusMonths(2);
        p.row(month);
        LocalDate due = LocalDate.now(PaymentScenario.KIGALI).minusDays(40);
        UUID row = jdbc.queryForObject("select id from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                UUID.class, p.id(), p.payer.id(), month.getYear(), month.getMonthValue());
        BigDecimal before = p.availableFunds();
        FineSettings settings = FineSettings.builder().cooperativeId(p.id())
                .creditConversionDays(30).creditConversionRatePercent(new BigDecimal("2")).creditConversionTermMonths(3).build();
        int converted = creditConversionService.convertIfDue(
                cooperativeRepository.findById(p.id()).orElseThrow(), settings, row, due, LocalDate.now(PaymentScenario.KIGALI),
                p.scheme.president().id());
        assertThat(converted).isEqualTo(1);
        assertThat(p.outstanding(month)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select outstanding_principal from loans where cooperative_id = ? and purpose like ?",
                BigDecimal.class, p.id(), "%CC:" + row + "%")).isEqualByComparingTo("2000");
        assertThat(p.availableFunds()).as("no cash moved").isEqualByComparingTo(before);
        assertThat(creditConversionService.convertIfDue(
                cooperativeRepository.findById(p.id()).orElseThrow(), settings, row, due, LocalDate.now(PaymentScenario.KIGALI),
                p.scheme.president().id())).as("never twice").isEqualTo(0);
    }

    @Test
    void anOperatingModelChangesFromTheMonthChosen() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 1);
        YearMonth next = NOW.plusMonths(1);
        MvcResult scheduled = mockMvc.perform(post(p.base() + "/operating-model/changes")
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "operatingModel", "HYBRID", "effectiveYear", next.getYear(), "effectiveMonth", next.getMonthValue(),
                                "reason", "Members agreed to rotate part of each contribution"))))
                .andReturn();
        assertThat(status(scheduled)).as(scheduled.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult current = mockMvc.perform(post(p.base() + "/operating-model/changes")
                        .header("Authorization", PaymentScenario.bearer(p.scheme.president()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "operatingModel", "ROTATING", "effectiveYear", next.getYear(), "effectiveMonth", next.getMonthValue()))))
                .andReturn();
        assertThat(status(current)).as("one pending change at a time").isEqualTo(409);

        modelChangeService.applyDue();
        assertThat(jdbc.queryForObject("select operating_model from cooperatives where id = ?", String.class, p.id()))
                .as("not before its month").isEqualTo("ACCUMULATING");
        jdbc.update("update operating_model_changes set effective_year = ?, effective_month = ? where cooperative_id = ?",
                NOW.getYear(), NOW.getMonthValue(), p.id());
        modelChangeService.applyDue();
        assertThat(jdbc.queryForObject("select operating_model from cooperatives where id = ?", String.class, p.id())).isEqualTo("HYBRID");
        assertThat(jdbc.queryForObject("select status from operating_model_changes where cooperative_id = ?", String.class, p.id()))
                .isEqualTo("APPLIED");
    }
}
