package rw.terimbere.csams.modules.historicalimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTxWorkbook;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * A scheme brings its past in one way only: opening balances at a cutover date, or the detailed transaction history.
 * Doing both would count the same contributions, loans and balances twice, so the second way is refused and nothing is
 * created, whichever came first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MigrationStrategyGuardIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private rw.terimbere.csams.modules.loan.repository.LoanRepository loanRepository;

    private SchemeFixtures fixtures;
    private static final String CUTOVER = LocalDate.now().minusDays(20).toString();

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
    }

    // ------------------------------------------------------------ the two orders

    @Test
    void afterTheTransactionHistoryIsPostedOpeningBalancesAreRefusedAndNothingIsCreated() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        postDetailedHistory(s);
        assertThat(strategyOf(s)).isEqualTo("DETAILED_TRANSACTION_HISTORY");
        assertThat(jdbc.queryForObject("select source from cooperative_migration_strategies where cooperative_id = ?", String.class, s.id()))
                .isEqualTo("FIRST_IMPORT");

        long members = count("select count(*) from cooperative_memberships where cooperative_id = ?", s.id());
        long ledger = count("select count(*) from financial_ledger where cooperative_id = ?", s.id());
        // a cutover after the posted history is already invalid on its own (money would be counted twice)...
        assertThat(openingPreview(s, "late_" + id8()).path("status").asText()).isEqualTo("INVALID");
        // ...and one before it passes validation, so only the strategy guard stands in the way
        JsonNode preview = openingPreview(s, "dbl_" + id8(), "2023-01-01");
        assertThat(preview.path("status").asText()).isEqualTo("READY");

        MvcResult confirm = confirmOpening(s, preview.path("importId").asText(preview.path("id").asText()));
        assertThat(status(confirm)).isEqualTo(409);
        assertThat(code(confirm)).isEqualTo("MIGRATION_STRATEGY_CONFLICT");
        assertThat(count("select count(*) from cooperative_memberships where cooperative_id = ?", s.id())).isEqualTo(members);
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isEqualTo(ledger);
        assertThat(count("select count(*) from historical_imports where cooperative_id = ? and status = 'CONFIRMED'", s.id())).isZero();
        assertThat(count("select count(*) from historical_imports where cooperative_id = ? and status = 'FAILED'", s.id()))
                .as("a refused import is not marked failed: it stays as it was").isZero();
    }

    @Test
    void afterOpeningBalancesAreConfirmedTheTransactionHistoryCannotBePosted() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        JsonNode preview = openingPreview(s, "ob_" + id8());
        MvcResult confirm = confirmOpening(s, preview.path("importId").asText(preview.path("id").asText()));
        assertThat(status(confirm)).as(confirm.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(strategyOf(s)).isEqualTo("OPENING_BALANCE");
        assertThat(jdbc.queryForObject("select cutover_date from cooperative_migration_strategies where cooperative_id = ?", java.sql.Date.class, s.id())
                .toLocalDate()).isEqualTo(LocalDate.parse(CUTOVER));

        JsonNode batch = data(uploadHistory(s));
        long ledger = count("select count(*) from financial_ledger where cooperative_id = ?", s.id());
        MvcResult approve = mockMvc.perform(post(txBase(s) + "/" + batch.path("id").asText() + "/approve")
                        .header("Authorization", "Bearer " + s.president().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(status(approve)).isEqualTo(409);
        assertThat(code(approve)).isEqualTo("MIGRATION_STRATEGY_CONFLICT");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isEqualTo(ledger);
        assertThat(count("select count(*) from historical_ledger_imports where cooperative_id = ? and status = 'POSTED'", s.id())).isZero();
    }

    // ------------------------------------------------------------ explicit choice

    @Test
    void anExplicitChoiceBindsTheImporterAndCanBeChangedOnlyWhileNothingHasBeenImported() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        assertThat(data(getStrategy(s, s.president())).path("strategy").isNull()).isTrue();

        assertThat(status(choose(s, s.president(), "DETAILED_TRANSACTION_HISTORY", null))).isEqualTo(200);
        // opening balances are now the wrong way for this scheme
        JsonNode preview = openingPreview(s, "ex_" + id8());
        MvcResult refused = confirmOpening(s, preview.path("importId").asText(preview.path("id").asText()));
        assertThat(code(refused)).isEqualTo("MIGRATION_STRATEGY_CONFLICT");

        // nothing imported yet: the officer may change their mind
        assertThat(status(choose(s, s.president(), "OPENING_BALANCE", CUTOVER))).isEqualTo(200);
        MvcResult ok = confirmOpening(s, preview.path("importId").asText(preview.path("id").asText()));
        assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);

        // something was imported: the strategy is locked
        MvcResult locked = choose(s, s.president(), "DETAILED_TRANSACTION_HISTORY", null);
        assertThat(status(locked)).isEqualTo(409);
        assertThat(code(locked)).isEqualTo("MIGRATION_STRATEGY_LOCKED");
        JsonNode now = data(getStrategy(s, s.president()));
        assertThat(now.path("strategy").asText()).isEqualTo("OPENING_BALANCE");
        assertThat(now.path("locked").asBoolean()).isTrue();
    }

    @Test
    void openingBalancesNeedACutoverDateAndOnlyLeadershipAndMembersOfTheSchemeCanSee() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        Scheme other = fixtures.scheme("HYBRID");
        assertThat(code(choose(s, s.president(), "OPENING_BALANCE", null))).isEqualTo("MIGRATION_CUTOVER_REQUIRED");
        assertThat(status(choose(s, s.accountantA(), "OPENING_BALANCE", CUTOVER))).isEqualTo(403);
        assertThat(status(choose(s, s.memberA(), "OPENING_BALANCE", CUTOVER))).isEqualTo(403);
        assertThat(status(getStrategy(s, other.president()))).as("another scheme's officer").isEqualTo(403);

        // a choice in one scheme does not touch another
        assertThat(status(choose(other, other.president(), "DETAILED_TRANSACTION_HISTORY", null))).isEqualTo(200);
        assertThat(data(getStrategy(s, s.president())).path("strategy").isNull()).isTrue();
    }

    // ------------------------------------------------------------ database protection

    @Test
    void theDatabaseAllowsOnePerSchemeAndOnlyTheTwoStrategies() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        assertThat(status(choose(s, s.president(), "DETAILED_TRANSACTION_HISTORY", null))).isEqualTo(200);

        assertThatThrownBy(() -> jdbc.update(
                        "insert into cooperative_migration_strategies (id, cooperative_id, strategy, source) values (?, ?, 'OPENING_BALANCE', 'EXPLICIT')",
                        UUID.randomUUID(), s.id()))
                .as("a second row for the same scheme")
                .isInstanceOf(DataIntegrityViolationException.class);

        Scheme t = fixtures.scheme("HYBRID");
        assertThatThrownBy(() -> jdbc.update(
                        "insert into cooperative_migration_strategies (id, cooperative_id, strategy, source) values (?, ?, 'BOTH', 'EXPLICIT')",
                        UUID.randomUUID(), t.id()))
                .as("an unknown strategy")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSchemeThatHadImportedBothWaysIsFlaggedAndAcceptsNoFurtherImport() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        jdbc.update("insert into cooperative_migration_strategies (id, cooperative_id, strategy, source, needs_review)"
                + " values (?, ?, 'OPENING_BALANCE', 'BACKFILL', true)", UUID.randomUUID(), s.id());
        JsonNode preview = openingPreview(s, "nr_" + id8());
        MvcResult confirm = confirmOpening(s, preview.path("importId").asText(preview.path("id").asText()));
        assertThat(status(confirm)).isEqualTo(409);
        assertThat(code(confirm)).isEqualTo("MIGRATION_NEEDS_REVIEW");
        assertThat(code(choose(s, s.president(), "DETAILED_TRANSACTION_HISTORY", null))).isEqualTo("MIGRATION_NEEDS_REVIEW");
    }

    // ------------------------------------------------------------ the review of a flagged scheme

    @Test
    void aFlaggedSchemeIsListedForPlatformAdminsBlocksOnlyImportsAndIsResolvedByAnAuthorityWithAReason() throws Exception {
        rw.terimbere.csams.testsupport.PaymentScenario p =
                new rw.terimbere.csams.testsupport.PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
        Scheme s = p.scheme;
        jdbc.update("insert into cooperative_migration_strategies (id, cooperative_id, strategy, source, needs_review)"
                + " values (?, ?, 'OPENING_BALANCE', 'BACKFILL', true)", UUID.randomUUID(), s.id());

        // visible to a platform administrator, and only to them
        MvcResult list = mockMvc.perform(get("/api/v1/platform/migration-reviews")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken()))
                .andReturn();
        assertThat(status(list)).isEqualTo(200);
        assertThat(data(list).toString()).contains(s.id().toString());
        assertThat(status(mockMvc.perform(get("/api/v1/platform/migration-reviews")
                        .header("Authorization", "Bearer " + s.president().token())).andReturn())).isEqualTo(403);
        // and visible in the scheme's own strategy view
        assertThat(data(getStrategy(s, s.president())).path("needsReview").asBoolean()).isTrue();

        // historical imports are blocked while the review is open...
        JsonNode preview = openingPreview(s, "rev_" + id8());
        assertThat(code(confirmOpening(s, preview.path("importId").asText(preview.path("id").asText())))).isEqualTo("MIGRATION_NEEDS_REVIEW");
        // ...but ordinary business is not: a member's payment is verified and posted
        p.row(rw.terimbere.csams.testsupport.PaymentScenario.NOW);
        p.posted("30000", rw.terimbere.csams.testsupport.PaymentScenario.contribution(rw.terimbere.csams.testsupport.PaymentScenario.NOW, "30000"));
        assertThat(p.paid(rw.terimbere.csams.testsupport.PaymentScenario.NOW)).isEqualByComparingTo("30000");

        // resolving needs authority, an explicit strategy and a reason
        assertThat(status(resolve(s, s.accountantA(), "OPENING_BALANCE", CUTOVER, "Reconciled with the old books and corrected."))).isEqualTo(403);
        assertThat(status(resolve(s, s.memberA(), "OPENING_BALANCE", CUTOVER, "Reconciled with the old books and corrected."))).isEqualTo(403);
        Scheme other = fixtures.scheme("HYBRID");
        assertThat(status(resolve(s, other.president(), "OPENING_BALANCE", CUTOVER, "Reconciled with the old books and corrected."))).as("another scheme").isEqualTo(403);
        assertThat(code(resolve(s, s.president(), "OPENING_BALANCE", CUTOVER, "too short"))).isEqualTo("MIGRATION_RESOLUTION_NOTE_REQUIRED");
        assertThat(status(resolve(s, s.president(), null, CUTOVER, "Reconciled with the old books and corrected."))).as("a strategy is never guessed").isEqualTo(400);
        assertThat(code(resolve(s, s.president(), "OPENING_BALANCE", null, "Reconciled with the old books and corrected."))).isEqualTo("MIGRATION_CUTOVER_REQUIRED");
        assertThat(data(getStrategy(s, s.president())).path("needsReview").asBoolean()).as("nothing was cleared by the refusals").isTrue();

        MvcResult ok = resolve(s, s.president(), "OPENING_BALANCE", CUTOVER, "Reconciled with the old books and corrected.");
        assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(data(ok).path("needsReview").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select resolution_note from cooperative_migration_strategies where cooperative_id = ?", String.class, s.id()))
                .isEqualTo("Reconciled with the old books and corrected.");
        assertThat(count("select count(*) from audit_logs where cooperative_id = ? and action = 'MIGRATION_STRATEGY_RESOLVED'", s.id())).isEqualTo(1);
        assertThat(code(resolve(s, s.president(), "OPENING_BALANCE", CUTOVER, "Reconciled with the old books and corrected."))).isEqualTo("MIGRATION_NOT_UNDER_REVIEW");
        assertThat(data(mockMvc.perform(get("/api/v1/platform/migration-reviews")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())).andReturn()).toString()).doesNotContain(s.id().toString());

        // imports are allowed again, with the strategy the authority named
        MvcResult confirm = confirmOpening(s, preview.path("importId").asText(preview.path("id").asText()));
        assertThat(status(confirm)).as(confirm.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    void choosingAStrategyIsAuditedAndNeverGuessed() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        assertThat(status(choose(s, s.president(), "DETAILED_TRANSACTION_HISTORY", null))).isEqualTo(200);
        assertThat(count("select count(*) from audit_logs where cooperative_id = ? and action = 'MIGRATION_STRATEGY_CHOSEN'", s.id())).isEqualTo(1);
        assertThat(count("select count(*) from cooperative_migration_strategies where cooperative_id = ? and source = 'EXPLICIT'", s.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------ helpers

    private void postDetailedHistory(Scheme s) throws Exception {
        JsonNode batch = data(uploadHistory(s));
        MvcResult approve = mockMvc.perform(post(txBase(s) + "/" + batch.path("id").asText() + "/approve")
                        .header("Authorization", "Bearer " + s.president().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(status(approve)).as(approve.getResponse().getContentAsString()).isEqualTo(200);
    }

    private MvcResult uploadHistory(Scheme s) throws Exception {
        String a = s.memberA().username();
        List<String[]> members = List.of(
                new String[] {"SETTING", "", "", "", "OPENING_BALANCE", "0"},
                new String[] {"SETTING", "", "", "", "DECLARED_CLOSING_BALANCE", "5000"},
                new String[] {"MEMBER", a, "", "", "", ""});
        List<String[]> txs = java.util.Collections.singletonList(new String[] {"CONTRIBUTION", "2024-01-20", a, "5000", "R-" + id8(), "2024", "1", "", "", ""});
        byte[] bytes;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill(workbook.createSheet(HistoricalTxWorkbook.MEMBERS_SHEET), HistoricalTxWorkbook.MEMBER_HEADERS, members);
            fill(workbook.createSheet(HistoricalTxWorkbook.TRANSACTIONS_SHEET), HistoricalTxWorkbook.TX_HEADERS, txs);
            workbook.write(out);
            bytes = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "history.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes);
        return mockMvc.perform(multipart(txBase(s) + "/preview").file(file).header("Authorization", "Bearer " + s.accountantA().token()))
                .andReturn();
    }

    private static void fill(Sheet sheet, List<String> headers, List<String[]> rows) {
        Row header = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            header.createCell(c).setCellValue(headers.get(c));
        }
        int r = 1;
        for (String[] cells : rows) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < cells.length; c++) {
                row.createCell(c).setCellValue(cells[c]);
            }
        }
    }

    private JsonNode openingPreview(Scheme s, String username) throws Exception {
        return openingPreview(s, username, CUTOVER);
    }

    private JsonNode openingPreview(Scheme s, String username, String cutover) throws Exception {
        MockMultipartFile file = MigrationWorkbooks.create().member("Open Ing", username, "1000").file("open.xlsx");
        MvcResult result = mockMvc.perform(multipart(openBase(s) + "/preview")
                        .file(file)
                        .param("cutoverDate", cutover)
                        .param("openingAvailableFunds", "100000")
                        .header("Authorization", "Bearer " + s.president().token()))
                .andReturn();
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return data(result);
    }

    private MvcResult confirmOpening(Scheme s, String importId) throws Exception {
        return mockMvc.perform(post(openBase(s) + "/" + importId + "/confirm").header("Authorization", "Bearer " + s.president().token()))
                .andReturn();
    }

    private MvcResult choose(Scheme s, Actor who, String strategy, String cutover) throws Exception {
        String json = cutover == null
                ? "{\"strategy\":\"" + strategy + "\"}"
                : "{\"strategy\":\"" + strategy + "\",\"cutoverDate\":\"" + cutover + "\"}";
        return mockMvc.perform(put("/api/v1/cooperatives/" + s.id() + "/migration-strategy")
                        .header("Authorization", "Bearer " + who.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult resolve(Scheme s, Actor who, String strategy, String cutover, String note) throws Exception {
        StringBuilder json = new StringBuilder("{");
        if (strategy != null) {
            json.append("\"strategy\":\"").append(strategy).append("\",");
        }
        if (cutover != null) {
            json.append("\"cutoverDate\":\"").append(cutover).append("\",");
        }
        json.append("\"note\":\"").append(note).append("\"}");
        return mockMvc.perform(post("/api/v1/cooperatives/" + s.id() + "/migration-strategy/resolve")
                        .header("Authorization", "Bearer " + who.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.toString()))
                .andReturn();
    }

    private MvcResult getStrategy(Scheme s, Actor who) throws Exception {
        return mockMvc.perform(get("/api/v1/cooperatives/" + s.id() + "/migration-strategy").header("Authorization", "Bearer " + who.token()))
                .andReturn();
    }

    private String strategyOf(Scheme s) {
        return jdbc.queryForObject("select strategy from cooperative_migration_strategies where cooperative_id = ?", String.class, s.id());
    }

    private static String openBase(Scheme s) {
        return "/api/v1/cooperatives/" + s.id() + "/historical-imports";
    }

    private static String txBase(Scheme s) {
        return "/api/v1/cooperatives/" + s.id() + "/historical-transactions";
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String code(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static String id8() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
