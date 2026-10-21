package rw.terimbere.csams.modules.historicalimport.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * Phase 4: the guided historical transaction import. HTTP-level on H2 in PostgreSQL mode (PostgreSQL itself is not
 * verified here). Covers the template, file validation, per-row errors, the dry run, duplicate detection, idempotency,
 * independent approval, atomic posting, reconciliation and the classification of imported money.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoricalTransactionImportIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShareValuationService shareValuationService;
    @Autowired private LedgerService ledgerService;

    private SchemeFixtures fixtures;

    /** One workbook row: values in header order. */
    private static String[] row(String... cells) {
        return cells;
    }

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
    }

    // ============================================================ template and file validation

    @Test
    void theTemplateHasExactlyTwoWorksheetsWithTheDocumentedHeadersAndNoSampleRows() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        MvcResult result = mockMvc.perform(get(base(s) + "/template").header("Authorization", bearer(s.accountantA()))).andReturn();
        assertThat(status(result)).isEqualTo(200);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo(HistoricalTxWorkbook.MEMBERS_SHEET);
            assertThat(workbook.getSheetName(1)).isEqualTo(HistoricalTxWorkbook.TRANSACTIONS_SHEET);
            for (int i = 0; i < 2; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                List<String> headers = i == 0 ? HistoricalTxWorkbook.MEMBER_HEADERS : HistoricalTxWorkbook.TX_HEADERS;
                for (int c = 0; c < headers.size(); c++) {
                    assertThat(sheet.getRow(0).getCell(c).getStringCellValue()).isEqualTo(headers.get(c));
                }
                assertThat(sheet.getLastRowNum()).as("no sample rows").isZero();
            }
        }
        // members cannot download it
        assertThat(status(mockMvc.perform(get(base(s) + "/template").header("Authorization", bearer(s.memberA()))).andReturn())).isEqualTo(403);
    }

    @Test
    void notAWorkbookWrongSheetsAndWrongHeadersAreRefusedWithAReason() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        MvcResult text = upload(s, s.accountantA(), "history.xlsx", "not a workbook".getBytes());
        assertThat(status(text)).isIn(400, 422);
        MvcResult wrongExtension = upload(s, s.accountantA(), "history.csv", workbookBytes(List.of(), List.of()));
        assertThat(status(wrongExtension)).isIn(400, 422);

        byte[] oneSheet;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Members and Configuration");
            workbook.write(out);
            oneSheet = out.toByteArray();
        }
        MvcResult few = upload(s, s.accountantA(), "history.xlsx", oneSheet);
        assertThat(status(few)).isIn(400, 422);
        assertThat(few.getResponse().getContentAsString()).contains("exactly two worksheets");

        byte[] badHeaders;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet(HistoricalTxWorkbook.MEMBERS_SHEET).createRow(0).createCell(0).setCellValue("Wrong");
            workbook.createSheet(HistoricalTxWorkbook.TRANSACTIONS_SHEET).createRow(0).createCell(0).setCellValue("Wrong");
            workbook.write(out);
            badHeaders = out.toByteArray();
        }
        MvcResult headers = upload(s, s.accountantA(), "history.xlsx", badHeaders);
        assertThat(status(headers)).isIn(400, 422);
        assertThat(headers.getResponse().getContentAsString()).contains("headers must be exactly");
        assertThat(count("select count(*) from historical_ledger_imports where cooperative_id = ?", s.id())).isZero();
    }

    // ============================================================ dry run

    @Test
    void aValidMultiYearWorkbookIsValidatedReconciledAndPostsNothing() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        long ledgerBefore = count("select count(*) from financial_ledger where cooperative_id = ?", s.id());

        JsonNode batch = data(upload(s, s.accountantA(), "history.xlsx", validWorkbook(s, "16400")));

        assertThat(batch.path("status").asText()).isEqualTo("PREVIEWED");
        assertThat(batch.path("errorCount").asInt()).isZero();
        assertThat(batch.path("canApprove").asBoolean()).isTrue();
        assertThat(dec(batch, "computedClosingBalance")).isEqualByComparingTo("16400");
        assertThat(dec(batch, "balanceVariance")).isEqualByComparingTo("0");
        JsonNode recon = batch.path("summary").path("reconciliation");
        assertThat(recon.path("balanced").asBoolean()).isTrue();
        assertThat(recon.path("byYear")).hasSize(2);
        assertThat(recon.path("byYear").get(0).path("year").asInt()).isEqualTo(2023);
        assertThat(dec(recon.path("byYear").get(0), "credits")).isEqualByComparingTo("10000");
        assertThat(recon.path("loans")).as("loan rows are not importable, so nothing reconciles as a loan").isEmpty();
        // contributions carry no rotation attribution: reported as unresolved, never payout-eligible
        assertThat(dec(recon.path("unresolvedAttribution"), "amount")).isEqualByComparingTo("15000");

        // a dry run creates no financial record at all
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isEqualTo(ledgerBefore);
        assertThat(count("select count(*) from contributions where cooperative_id = ?", s.id())).isZero();
        assertThat(count("select count(*) from historical_ledger_rows where cooperative_id = ? and posted_key is not null", s.id())).isZero();
    }

    @Test
    void invalidRowsAreReportedOneByOneWithTheirRowNumbersAndNothingGuessedIsImported() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        String future = LocalDate.now().plusDays(5).toString();
        List<String[]> members = List.of(
                row("SETTING", "", "", "", "OPENING_BALANCE", "0"),
                row("MEMBER", s.memberA().username(), "", "", "", ""));
        List<String[]> txs = List.of(
                row("DIVIDEND", "2023-01-10", s.memberA().username(), "100", "D-1", "", "", "", "", ""),
                row("CONTRIBUTION", "2023-01-10", "nobody", "100", "R", "2023", "1", "", "", ""),
                row("CONTRIBUTION", future, s.memberA().username(), "100", "R", "2023", "1", "", "", ""),
                row("CONTRIBUTION", "2023-01-10", s.memberA().username(), "-5", "R", "2023", "1", "", "", ""),
                row("CONTRIBUTION", "2023-02-10", s.memberA().username(), "100", "R", "2023", "2", "", "", ""),
                row("CONTRIBUTION", "2023-02-10", s.memberA().username(), "100", "R", "2023", "2", "", "", ""),
                row("GENERAL_EXPENSE", "2023-03-10", "", "50", "", "", "", "", "", ""),
                row("LOAN_DISBURSEMENT", "2023-03-10", s.memberA().username(), "1000", "L", "", "", "LN9", "", ""),
                row("LOAN_PRINCIPAL_REPAYMENT", "2023-04-10", s.memberA().username(), "400", "P", "", "", "LN9", "", ""));

        JsonNode batch = data(upload(s, s.accountantA(), "bad.xlsx", workbookBytes(members, txs)));

        assertThat(batch.path("canApprove").asBoolean()).isFalse();
        assertThat(batch.path("errorCount").asInt()).isGreaterThan(6);
        JsonNode rows = data(mockMvc.perform(get(base(s) + "/" + batch.path("id").asText() + "/rows?onlyErrors=true")
                .header("Authorization", bearer(s.accountantA()))).andReturn());
        String all = rows.toString();
        for (String code : List.of("UNSUPPORTED_ROW_TYPE", "MEMBER_NOT_FOUND", "DATE_IN_FUTURE", "INVALID_AMOUNT",
                "DUPLICATE_IN_FILE", "REFERENCE_REQUIRED")) {
            assertThat(all).as(code).contains(code);
        }
        JsonNode dividend = rows.get(0);
        assertThat(dividend.path("rowNumber").asInt()).isEqualTo(2);
        assertThat(dividend.path("errors").get(0).path("message").asText()).contains("not a supported historical event");
        // the missing declared closing balance is reported for the whole sheet
        assertThat(batch.path("summary").path("sheetIssues").toString()).contains("DECLARED_CLOSING_BALANCE");

        MvcResult approve = postTo(s, s.president(), "/" + batch.path("id").asText() + "/approve", "{}");
        assertThat(status(approve)).isEqualTo(422);
        assertThat(code(approve)).isEqualTo("HISTORICAL_IMPORT_HAS_ERRORS");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isZero();
    }

    @Test
    void theSameFileIsOneBatchAndAChangedFileIsAnotherWithDuplicatesCaught() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        byte[] file = validWorkbook(s, "16400");
        JsonNode first = data(upload(s, s.accountantA(), "history.xlsx", file));
        JsonNode again = data(upload(s, s.accountantB(), "renamed.xlsx", file));
        assertThat(again.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(again.path("reused").asBoolean()).isTrue();
        assertThat(count("select count(*) from historical_ledger_imports where cooperative_id = ?", s.id())).isEqualTo(1);

        UUID id = UUID.fromString(first.path("id").asText());
        assertThat(status(postTo(s, s.president(), "/" + id + "/approve", "{}"))).isEqualTo(200);
        // posted: uploading the identical file returns the posted batch and posts nothing again
        JsonNode posted = data(upload(s, s.accountantA(), "history.xlsx", file));
        assertThat(posted.path("status").asText()).isEqualTo("POSTED");
        long ledger = count("select count(*) from financial_ledger where cooperative_id = ?", s.id());

        // a slightly different file carrying the same events: every event is a duplicate of one already imported
        JsonNode changed = data(upload(s, s.accountantA(), "history2.xlsx", validWorkbook(s, "16400", "extra setting row")));
        assertThat(changed.path("canApprove").asBoolean()).isFalse();
        JsonNode rows = data(mockMvc.perform(get(base(s) + "/" + changed.path("id").asText() + "/rows?onlyErrors=true")
                .header("Authorization", bearer(s.accountantA()))).andReturn());
        assertThat(rows.toString()).contains("DUPLICATE_EXISTING_CONTRIBUTION");
        assertThat(rows.toString()).contains("DUPLICATE_PREVIOUSLY_IMPORTED");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isEqualTo(ledger);
    }

    // ============================================================ approval and posting

    @Test
    void anIndependentFundAuthorityApprovesAndTheBatchPostsOnceWithOriginalDatesAndReferences() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        JsonNode batch = data(upload(s, s.accountantA(), "history.xlsx", validWorkbook(s, "16400")));
        UUID id = UUID.fromString(batch.path("id").asText());

        // the uploader, an accountant, a member and a foreign scheme's president cannot approve
        assertThat(status(postTo(s, s.accountantA(), "/" + id + "/approve", "{}"))).isEqualTo(403);
        assertThat(status(postTo(s, s.accountantB(), "/" + id + "/approve", "{}"))).isEqualTo(403);
        assertThat(status(postTo(s, s.memberA(), "/" + id + "/approve", "{}"))).isEqualTo(403);
        Scheme other = fixtures.scheme("HYBRID");
        assertThat(status(mockMvc.perform(post(base(s) + "/" + id + "/approve").header("Authorization", bearer(other.president()))).andReturn())).isIn(403, 404);
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isZero();
        // Super Admin uploads, so Super Admin cannot approve their own batch either (no waiver)
        JsonNode adminBatch = data(uploadAs(s, fixtures.superAdminToken(), "admin.xlsx", validWorkbook(s, "16400", "admin")));
        assertThat(status(mockMvc.perform(post(base(s) + "/" + adminBatch.path("id").asText() + "/approve")
                .header("Authorization", "Bearer " + fixtures.superAdminToken())).andReturn())).isEqualTo(403);

        MvcResult approved = postTo(s, s.president(), "/" + id + "/approve", "{}");
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(data(approved).path("status").asText()).isEqualTo("POSTED");

        // one ledger entry per event, at the original date, with the original reference
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from financial_ledger where cooperative_id = ? and transaction_date = DATE '2023-02-10' and reference = 'R1'",
                Long.class, s.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select coalesce(sum(credit_amount),0) - coalesce(sum(debit_amount),0) from financial_ledger where cooperative_id = ?",
                BigDecimal.class, s.id())).isEqualByComparingTo("15400");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ? and idempotency_key like 'HISTIMP:%'", s.id())).isEqualTo(6);
        // contributions are historical obligations with no rotation attribution at all
        assertThat(count("select count(*) from contributions where cooperative_id = ?", s.id())).isEqualTo(3);
        assertThat(count("select count(*) from contributions where cooperative_id = ? and rotation_period_id is not null", s.id())).isZero();
        assertThat(count("select count(*) from contributions where cooperative_id = ? and rotating_bps_snapshot is not null", s.id())).isZero();
        // approving again is a safe no-op: no duplicate credit
        assertThat(status(postTo(s, s.president(), "/" + id + "/approve", "{}"))).isEqualTo(200);
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isEqualTo(6);

        JsonNode events = data(mockMvc.perform(get(base(s) + "/" + id + "/events").header("Authorization", bearer(s.president()))).andReturn());
        assertThat(events).hasSize(2);
        assertThat(count("select count(*) from audit_logs where action = 'HISTORICAL_IMPORT_POST' and entity_id = ?", id)).isEqualTo(1);
        // posted rows can never be posted again by any batch: the database refuses it
        assertThat(count("select count(*) from historical_ledger_rows where cooperative_id = ? and posted_key is not null", s.id())).isEqualTo(6);
    }

    @Test
    void aVarianceNeedsAWrittenAcknowledgementAndIsRecorded() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        JsonNode batch = data(upload(s, s.accountantA(), "history.xlsx", validWorkbook(s, "16850"))); // declared 450 too high
        UUID id = UUID.fromString(batch.path("id").asText());
        assertThat(dec(batch, "balanceVariance")).isEqualByComparingTo("450");
        assertThat(batch.path("canApprove").asBoolean()).isTrue();

        MvcResult plain = postTo(s, s.president(), "/" + id + "/approve", "{}");
        assertThat(status(plain)).isEqualTo(422);
        assertThat(code(plain)).isEqualTo("HISTORICAL_VARIANCE_UNACKNOWLEDGED");
        MvcResult shortNote = postTo(s, s.president(), "/" + id + "/approve", "{\"acknowledgeVariance\":true,\"note\":\"ok\"}");
        assertThat(code(shortNote)).isEqualTo("HISTORICAL_VARIANCE_UNACKNOWLEDGED");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isZero();

        MvcResult ok = postTo(s, s.president(), "/" + id + "/approve",
                "{\"acknowledgeVariance\":true,\"note\":\"The 2024 cash box was short by 450 per the treasurer\"}");
        assertThat(status(ok)).isEqualTo(200);
        assertThat(data(ok).path("varianceAcknowledged").asBoolean()).isTrue();
        assertThat(data(ok).path("varianceNote").asText()).contains("cash box");
    }

    @Test
    void aBatchThatBecameStaleOrWasCancelledIsNeverPostedAndCancellingFreesTheFile() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        byte[] file = validWorkbook(s, "16400");
        JsonNode batch = data(upload(s, s.accountantA(), "history.xlsx", file));
        UUID id = UUID.fromString(batch.path("id").asText());
        // someone records one of the same member-periods in the meantime
        jdbc.update("""
                insert into contributions (id, cooperative_id, member_user_id, "year", "month", expected_amount, paid_amount,
                  outstanding_amount, status, ledger_revision, created_at, updated_at, version)
                values (?, ?, ?, 2023, 2, 5000, 0, 5000, 'PENDING', 0, now(), now(), 0)
                """, UUID.randomUUID(), s.id(), s.memberA().id());
        MvcResult stale = postTo(s, s.president(), "/" + id + "/approve", "{}");
        assertThat(status(stale)).isEqualTo(422);
        assertThat(code(stale)).isEqualTo("HISTORICAL_IMPORT_STALE");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isZero();

        MvcResult cancelled = postTo(s, s.accountantA(), "/" + id + "/cancel", "{\"note\":\"Superseded\"}");
        assertThat(status(cancelled)).isEqualTo(200);
        assertThat(data(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(status(postTo(s, s.president(), "/" + id + "/approve", "{}"))).isEqualTo(409);
        // the same file can be uploaded again as a new batch
        JsonNode again = data(upload(s, s.accountantA(), "history.xlsx", file));
        assertThat(again.path("id").asText()).isNotEqualTo(id.toString());

        // a posted batch is final
        Scheme t = fixtures.scheme("HYBRID");
        JsonNode posted = data(upload(t, t.accountantA(), "history.xlsx", validWorkbook(t, "16400")));
        assertThat(status(postTo(t, t.president(), "/" + posted.path("id").asText() + "/approve", "{}"))).isEqualTo(200);
        assertThat(status(postTo(t, t.accountantA(), "/" + posted.path("id").asText() + "/cancel", "{}"))).isEqualTo(409);
    }

    @Test
    void historyThatOverlapsLiveRecordsIsRefusedSoNothingIsCountedTwice() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(s.id())
                .memberUserId(s.memberA().id())
                .transactionType(LedgerTransactionType.OTHER_INCOME)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(BigDecimal.TEN)
                .currency("RWF")
                .transactionDate(LocalDate.of(2023, 3, 1))
                .reference("LIVE-1")
                .sourceEntityType(LedgerService.SOURCE_INCOME_EXPENSE)
                .sourceEntityId(UUID.randomUUID())
                .description("A live record")
                .recordedBy(fixtures.superAdminId())
                .approvedBy(fixtures.superAdminId())
                .idempotencyKey("LIVE-" + UUID.randomUUID())
                .build());
        JsonNode batch = data(upload(s, s.accountantA(), "history.xlsx", validWorkbook(s, "16400")));
        assertThat(batch.path("canApprove").asBoolean()).isFalse();
        JsonNode rows = data(mockMvc.perform(get(base(s) + "/" + batch.path("id").asText() + "/rows?onlyErrors=true")
                .header("Authorization", bearer(s.accountantA()))).andReturn());
        assertThat(rows.toString()).contains("DATE_OVERLAPS_LIVE_RECORDS");
    }

    @Test
    void historyIsScopedToItsSchemeAndAnotherSchemeCannotSeeOrUseIt() throws Exception {
        Scheme a = fixtures.scheme("HYBRID");
        Scheme b = fixtures.scheme("HYBRID");
        JsonNode batch = data(upload(a, a.accountantA(), "history.xlsx", validWorkbook(a, "16400")));
        String id = batch.path("id").asText();
        assertThat(status(mockMvc.perform(get(base(b) + "/" + id).header("Authorization", bearer(b.accountantA()))).andReturn())).isEqualTo(404);
        assertThat(status(mockMvc.perform(get(base(a) + "/" + id).header("Authorization", bearer(b.accountantA()))).andReturn())).isIn(403, 404);
        assertThat(data(mockMvc.perform(get(base(b)).header("Authorization", bearer(b.accountantA()))).andReturn())).isEmpty();
        // scheme A's usernames are not members of scheme B
        JsonNode foreign = data(upload(b, b.accountantA(), "history.xlsx", validWorkbook(a, "16400")));
        assertThat(foreign.path("canApprove").asBoolean()).isFalse();
        assertThat(count("select count(*) from historical_ledger_imports where cooperative_id = ?", b.id())).isEqualTo(1);
    }

    // ============================================================ classification of imported money

    @Test
    void importedContributionsAreNeverRetainedOrRecipientMoneyUntilTheirClassIsKnown() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        List<String[]> members = List.of(
                row("SETTING", "", "", "", "OPENING_BALANCE", "0"),
                row("SETTING", "", "", "", "DECLARED_CLOSING_BALANCE", "6000"));
        List<String[]> txs = List.of(
                row("CONTRIBUTION", "2022-01-10", s.memberA().username(), "1000", "A", "2022", "1", "", "", ""),
                row("CONTRIBUTION", "2022-02-10", s.memberA().username(), "2000", "B", "2022", "2", "", "ROTATING", ""),
                row("CONTRIBUTION", "2022-03-10", s.memberA().username(), "3000", "C", "2022", "3", "", "ACCUMULATING", ""));
        JsonNode batch = data(upload(s, s.accountantA(), "classes.xlsx", workbookBytes(members, txs)));
        assertThat(batch.path("errorCount").asInt()).as(batch.toString()).isZero();
        assertThat(status(postTo(s, s.president(), "/" + batch.path("id").asText() + "/approve", "{}"))).isEqualTo(200);

        JsonNode summary = data(mockMvc.perform(get("/api/v1/cooperatives/" + s.id() + "/reports/model-summary")
                .header("Authorization", bearer(s.accountantA()))).andReturn());
        assertThat(dec(summary, "postedCollections")).isEqualByComparingTo("6000");
        assertThat(dec(summary, "unclassifiedCollections")).as("class never stated").isEqualByComparingTo("1000");
        assertThat(dec(summary, "unresolvedRotatingCollections")).as("known rotating, period unknown").isEqualByComparingTo("2000");
        assertThat(dec(summary, "retainedCollections")).as("only what was stated as accumulating").isEqualByComparingTo("3000");
        assertThat(dec(summary, "rotatingCollected")).as("never verified rotating eligibility").isEqualByComparingTo("0");
        assertThat(dec(summary, "recipientEntitlementsOwed")).isEqualByComparingTo("0");
        assertThat(dec(summary, "totalRotatingLiabilities")).isEqualByComparingTo("2000");
        BigDecimal categories = dec(summary, "retainedCollections").add(dec(summary, "unclassifiedCollections"))
                .add(dec(summary, "unresolvedRotatingCollections")).add(dec(summary, "rotatingCollected"))
                .add(dec(summary, "rotatingRequiringReconciliation"));
        assertThat(categories).as("every collected rupee is in exactly one category").isEqualByComparingTo(dec(summary, "postedCollections"));

        // NAV: cash is counted once, and only the known-rotating money is set aside
        var nav = shareValuationService.calculate(s.id());
        assertThat(nav.getAvailableFunds()).isEqualByComparingTo("6000");
        assertThat(nav.getUnresolvedRotatingFunds()).isEqualByComparingTo("2000");
        assertThat(nav.getUnclassifiedFunds()).isEqualByComparingTo("1000");
        assertThat(nav.getLiabilities()).isEqualByComparingTo("2000");
        assertThat(nav.getTotalIkiminaValue()).as("cash minus liabilities, once").isEqualByComparingTo("4000");
        // nothing is payable and nothing can be reconciled as rotating entitlement
        assertThat(data(mockMvc.perform(get("/api/v1/cooperatives/" + s.id() + "/rotation/reconciliation/candidates")
                .header("Authorization", bearer(s.accountantA()))).andReturn())).isEmpty();
    }

    @Test
    void loanAndPayoutRowsAreRefusedWithAnExplanationAndNothingIsEverPostedToTheLedgerAlone() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        String a = s.memberA().username();
        List<String[]> members = List.of(
                row("SETTING", "", "", "", "OPENING_BALANCE", "0"),
                row("SETTING", "", "", "", "DECLARED_CLOSING_BALANCE", "1000"),
                row("MEMBER", a, "", "", "", ""));
        List<String[]> txs = List.of(
                row("CONTRIBUTION", "2023-01-10", a, "1000", "R1", "2023", "1", "", "", ""),
                row("LOAN_DISBURSEMENT", "2023-03-01", a, "300", "L-1", "", "", "LN1", "", ""),
                row("LOAN_PRINCIPAL_REPAYMENT", "2023-04-01", a, "300", "P-1", "", "", "LN1", "", ""),
                row("LOAN_INTEREST_PAYMENT", "2023-04-01", a, "30", "P-1i", "", "", "LN1", "", ""),
                row("MEMBER_PAYOUT", "2023-05-01", a, "100", "PO-1", "", "", "", "", ""));
        JsonNode batch = data(upload(s, s.accountantA(), "loans.xlsx", workbookBytes(members, txs)));
        assertThat(batch.path("canApprove").asBoolean()).isFalse();
        assertThat(batch.path("errorCount").asInt()).isEqualTo(4);
        JsonNode rows = data(mockMvc.perform(get(base(s) + "/" + batch.path("id").asText() + "/rows?onlyErrors=true")
                .header("Authorization", bearer(s.accountantA()))).andReturn());
        assertThat(rows).hasSize(4);
        for (JsonNode row : rows) {
            assertThat(row.path("errors").get(0).path("code").asText()).isEqualTo("UNSUPPORTED_ROW_TYPE");
        }
        assertThat(rows.toString()).contains("opening-balance loan migration").contains("rotation period and recipient");
        MvcResult approve = postTo(s, s.president(), "/" + batch.path("id").asText() + "/approve", "{}");
        assertThat(status(approve)).isEqualTo(422);
        assertThat(code(approve)).isEqualTo("HISTORICAL_IMPORT_HAS_ERRORS");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).isZero();
        assertThat(count("select count(*) from loans where cooperative_id = ?", s.id())).isZero();
    }

    // ============================================================ helpers

    private byte[] validWorkbook(Scheme s, String declaredClosing) throws Exception {
        return validWorkbook(s, declaredClosing, null);
    }

    private byte[] validWorkbook(Scheme s, String declaredClosing, String extraNote) throws Exception {
        String a = s.memberA().username();
        String b = s.memberB().username();
        List<String[]> members = new ArrayList<>(List.of(
                row("SETTING", "", "", "", "OPENING_BALANCE", "1000"),
                row("SETTING", "", "", "", "DECLARED_CLOSING_BALANCE", declaredClosing),
                row("MEMBER", a, "", "", "", ""),
                row("MEMBER", b, "", "", "", "")));
        if (extraNote != null) {
            members.add(row("SETTING", "", "", "", "CURRENCY", "RWF"));
        }
        List<String[]> txs = List.of(
                row("CONTRIBUTION", "2023-02-10", a, "5000", "R1", "2023", "2", "", "", ""),
                row("CONTRIBUTION", "2023-02-12", b, "5000", "R2", "2023", "2", "", "", ""),
                row("CONTRIBUTION", "2024-01-20", a, "5000", "R3", "2024", "1", "", "", ""),
                row("FINE_PAYMENT", "2024-02-01", b, "500", "F-1", "", "", "", "", ""),
                row("OTHER_INCOME", "2024-04-01", "", "200", "OI-1", "", "", "", "", ""),
                row("GENERAL_EXPENSE", "2024-05-01", "", "300", "EX-1", "", "", "", "", ""));
        return workbookBytes(members, txs);
    }

    private byte[] workbookBytes(List<String[]> members, List<String[]> txs) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill(workbook.createSheet(HistoricalTxWorkbook.MEMBERS_SHEET), HistoricalTxWorkbook.MEMBER_HEADERS, members);
            fill(workbook.createSheet(HistoricalTxWorkbook.TRANSACTIONS_SHEET), HistoricalTxWorkbook.TX_HEADERS, txs);
            workbook.write(out);
            return out.toByteArray();
        }
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

    private MvcResult upload(Scheme s, Actor who, String name, byte[] bytes) throws Exception {
        return uploadAs(s, who.token(), name, bytes);
    }

    private MvcResult uploadAs(Scheme s, String token, String name, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes);
        return mockMvc.perform(multipart(base(s) + "/preview").file(file).header("Authorization", "Bearer " + token)).andReturn();
    }

    private MvcResult postTo(Scheme s, Actor who, String suffix, String json) throws Exception {
        return mockMvc.perform(post(base(s) + suffix)
                        .header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private static String base(Scheme s) {
        return "/api/v1/cooperatives/" + s.id() + "/historical-transactions";
    }

    private static String bearer(Actor who) {
        return "Bearer " + who.token();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static BigDecimal dec(JsonNode node, String field) {
        return node.path(field).decimalValue();
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
}
