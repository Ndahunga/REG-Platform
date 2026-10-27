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
        YearMonth start = NOW.minusMonths(2);
        MvcResult imported = mockMvc.perform(post(p.base() + "/rotation/migration")
                        .header("Authorization", "Bearer " + p.fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cutoverYear", NOW.getYear(), "cutoverMonth", NOW.getMonthValue(),
                                "rotatingUnitAmount", 700,
                                "cycle", Map.of(
                                        "startYear", start.getYear(), "startMonth", start.getMonthValue(), "months", 3,
                                        "payoutPerShare", 1000, "turnMode", "GROUPED_BY_MONTH",
                                        "turns", List.of(
                                                Map.of("monthIndex", 1, "memberUserId", p.payer.id(), "shares", 2),
                                                Map.of("monthIndex", 2, "memberUserId", b.id(), "shares", 1),
                                                Map.of("monthIndex", 3, "memberUserId", b.id(), "shares", 2))),
                                "members", List.of()))))
                .andReturn();
        assertThat(status(imported)).as(imported.getResponse().getContentAsString()).isEqualTo(200);

        // B pays the current month: 3 x 700 = 2,100 rotates into the pool.
        p.postedFor(b, "3000", contribution(NOW, "3000"));
        // The payer still owes the contribution of two months ago (recorded late, no due date: the 10th of the next month).
        p.row(start);

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

    @Test
    void theRotationWorkbookCanBeCorrected_keepingTurnPayoutsMadeSince() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "HYBRID", 2);
        Actor b = p.addMember(3);
        Actor c = p.addMember(1);
        YearMonth start = NOW.minusMonths(2);
        UUID importId = UUID.randomUUID();
        jdbc.update("insert into historical_imports (id, cooperative_id, file_hash, status, import_kind, cutover_date, opening_available_funds,"
                        + " total_rows, valid_rows, invalid_rows, created_at, updated_at, version) values (?, ?, 'h', 'CONFIRMED', 'OPENING_BALANCE', ?, 0, 0, 0, 0, now(), now(), 0)",
                importId, p.id(), start.atDay(1).minusDays(1));
        for (Actor a : List.of(p.payer, b, c)) {
            jdbc.update("insert into member_opening_balances (id, cooperative_id, member_user_id, cutover_date, historical_import_id)"
                    + " values (?, ?, ?, ?, ?)", UUID.randomUUID(), p.id(), a.id(), start.atDay(1).minusDays(1), importId);
        }
        String v1 = workbook(start, p.payer.username(), b.username(), c.username(), "2000", true);
        MvcResult first = uploadWorkbook(p, v1, null);
        assertThat(status(first)).as(first.getResponse().getContentAsString()).isEqualTo(200);

        // Since the import: B pays this month, and B's turn of last month is paid out.
        p.postedFor(b, "3000", contribution(NOW, "3000"));
        UUID turn = jdbc.queryForObject("select t.id from rotation_turns t join rotation_periods r on r.id = t.rotation_period_id"
                        + " where t.cooperative_id = ? and t.member_user_id = ? and r.period_month = ?",
                UUID.class, p.id(), b.id(), NOW.minusMonths(1).getMonthValue());
        MvcResult requested = mockMvc.perform(post(p.base() + "/rotation/payouts")
                        .header("Authorization", PaymentScenario.bearer(p.reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rotationTurnId\":\"" + turn + "\"}"))
                .andReturn();
        assertThat(status(requested)).as(requested.getResponse().getContentAsString()).isEqualTo(200);
        UUID payout = UUID.fromString(p.data(requested).path("id").asText());
        p.approveAndDisburse(payout, "BK-REV");

        // A correction needs a reason.
        MvcResult noReason = uploadWorkbook(p, workbook(start, p.payer.username(), b.username(), c.username(), "1500", true), null);
        assertThat(status(noReason)).as(noReason.getResponse().getContentAsString()).isEqualTo(422);

        // Corrected: the payer had received 1,500, not 2,000. B's paid-out turn moves to the corrected cycle.
        MvcResult corrected = uploadWorkbook(p, workbook(start, p.payer.username(), b.username(), c.username(), "1500", true), "The payer received 1,500 not 2,000");
        assertThat(status(corrected)).as(corrected.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from rotation_cycles where cooperative_id = ?", Long.class, p.id())).isEqualTo(1L);
        UUID newTurn = jdbc.queryForObject("select rotation_turn_id from rotation_payouts where id = ?", UUID.class, payout);
        assertThat(jdbc.queryForObject("select member_user_id from rotation_turns where id = ?", UUID.class, newTurn)).isEqualTo(b.id());
        assertThat(jdbc.queryForObject("select opening_received_amount from rotation_turns where cooperative_id = ? and member_user_id = ?",
                java.math.BigDecimal.class, p.id(), p.payer.id())).isEqualByComparingTo("1500");
        assertThat(jdbc.queryForObject("select count(*) from opening_workbook_imports where cooperative_id = ? and status = 'SUPERSEDED'",
                Long.class, p.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                Long.class, p.id(), b.id(), NOW.getYear(), NOW.getMonthValue())).as("B's paid month stays, once").isEqualTo(1L);

        // A correction that drops the turn already paid out is refused, and nothing changes.
        MvcResult dropped = uploadWorkbook(p, workbook(start, p.payer.username(), b.username(), c.username(), "1500", false), "Moving B's turn elsewhere");
        assertThat(status(dropped)).as(dropped.getResponse().getContentAsString()).isEqualTo(422);
        assertThat(dropped.getResponse().getContentAsString()).contains("ROTATION_RECORDS_NEED_THEIR_TURN");
        assertThat(jdbc.queryForObject("select count(*) from opening_workbook_imports where cooperative_id = ? and status = 'CURRENT'",
                Long.class, p.id())).isEqualTo(1L);
    }

    /** Three months from {@code start}: the payer's 2 shares in month 1; B in months 2 and 3, or C in month 2 and B in month 3. */
    private static String workbook(YearMonth start, String payer, String b, String c, String payerReceived, boolean bInMonthTwo) throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            var scheme = wb.createSheet("Scheme");
            String[][] settings = {
                {"Setting", "Value"},
                {"Cycle first month (YYYY-MM)", start.toString()},
                {"Months in the cycle", "3"},
                {"Payout per share (RWF)", "1000"},
                {"Rotated per share each month (RWF)", "700"},
                {"How shares are paid (ONE_SHARE_PER_TURN, ALL_SHARES_TOGETHER or GROUPED_BY_MONTH)", "GROUPED_BY_MONTH"},
                {"Rotation money held at the cutover (RWF)", ""},
                {"Income earned before the cutover not yet shared (RWF)", "0"},
                {"First month collected in the app (YYYY-MM), e.g. 2026-09 for an August cut-off", NOW.toString()}};
            for (int i = 0; i < settings.length; i++) {
                var row = scheme.createRow(i);
                row.createCell(0).setCellValue(settings[i][0]);
                row.createCell(1).setCellValue(settings[i][1]);
            }
            var turns = wb.createSheet("Turns");
            List<String[]> t = new java.util.ArrayList<>();
            t.add(new String[] {"Username", "Turn Month (YYYY-MM)", "Shares", "Amount Received Before Cutover (RWF)", "Date Received"});
            t.add(new String[] {payer, start.toString(), "2", payerReceived, start.atDay(15).toString()});
            if (bInMonthTwo) {
                t.add(new String[] {b, start.plusMonths(1).toString(), "1", "0", ""});
                t.add(new String[] {b, start.plusMonths(2).toString(), "2", "0", ""});
            } else {
                t.add(new String[] {c, start.plusMonths(1).toString(), "1", "0", ""});
                t.add(new String[] {b, start.plusMonths(2).toString(), "3", "0", ""});
            }
            for (int i = 0; i < t.size(); i++) {
                var row = turns.createRow(i);
                for (int col = 0; col < 5; col++) {
                    row.createCell(col).setCellValue(t.get(i)[col]);
                }
            }
            var members = wb.createSheet("Members");
            String[][] m = {{"Username", "Rotated Money Paid Before Cutover (RWF)", "Amount the Scheme Owes the Member (RWF)"},
                {payer, "1400", "0"}, {b, "2100", "0"}};
            for (int i = 0; i < m.length; i++) {
                var row = members.createRow(i);
                for (int col = 0; col < 3; col++) {
                    row.createCell(col).setCellValue(m[i][col]);
                }
            }
            wb.write(out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    private MvcResult uploadWorkbook(PaymentScenario p, String base64, String reason) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(p.base() + "/positions/opening-workbook")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "rotation.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", java.util.Base64.getDecoder().decode(base64)))
                .header("Authorization", "Bearer " + p.fixtures.superAdminToken());
        if (reason != null) {
            request.param("reason", reason);
        }
        return mockMvc.perform(request).andReturn();
    }
}
