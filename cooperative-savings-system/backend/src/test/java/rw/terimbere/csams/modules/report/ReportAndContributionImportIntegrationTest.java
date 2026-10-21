package rw.terimbere.csams.modules.report;

import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionImportRepository;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.incomeexpense.repository.IncomeExpenseTransactionRepository;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportAndContributionImportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContributionRepository contributionRepository;

    @Autowired
    private ContributionImportRepository contributionImportRepository;

    @Autowired
    private SpecialContributionRepository specialContributionRepository;

    @Autowired
    private LoanRepaymentRepository loanRepaymentRepository;

    @Autowired
    private FinePaymentRepository finePaymentRepository;

    @Autowired
    private IncomeExpenseTransactionRepository incomeExpenseRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberUsername;
    private String memberPassword;
    private UUID otherCooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        cooperativeId = createCooperative("Report Coop " + UUID.randomUUID().toString().substring(0, 8));
        otherCooperativeId = createCooperative("Other Coop " + UUID.randomUUID().toString().substring(0, 8));

        memberUsername = "rmember_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode memberData = registerMember(cooperativeId, memberUsername, "Report", "Member");
        memberUserId = UUID.fromString(memberData.path("userId").asText());
        memberPassword = memberData.path("temporaryPassword").asText();
    }

    @Test
    void templateDownload_returnsXlsx() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/import/template")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(100);
        assertThat(body[0]).isEqualTo((byte) 0x50); // PK zip/xlsx
        assertThat(body[1]).isEqualTo((byte) 0x4B);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Username");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Amount");
        }
    }

    @Test
    void preview_marksInvalidUsername_andDoesNotCreateContributions() throws Exception {
        long before = contributionRepository.count();
        byte[] xlsx = buildImportWorkbook(
                memberUsername, "Report Member", "500.00", "2026-04-01", "REF-OK", "ok",
                "unknown_user_xyz", "Ghost", "100.00", "2026-04-02", "REF-BAD", "bad");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx);

        mockMvc.perform(multipart("/api/v1/cooperatives/" + cooperativeId + "/contributions/import/preview")
                        .file(file)
                        .param("year", "2026")
                        .param("month", "4")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importId").isNotEmpty())
                .andExpect(jsonPath("$.data.validCount").value(1))
                .andExpect(jsonPath("$.data.invalidCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].valid").value(true))
                .andExpect(jsonPath("$.data.rows[1].valid").value(false))
                .andExpect(jsonPath("$.data.rows[1].errors[0]").exists());

        assertThat(contributionRepository.count()).isEqualTo(before);
        assertThat(contributionImportRepository.findByCooperativeIdOrderByCreatedAtDesc(cooperativeId)).isNotEmpty();
    }

    @Test
    void confirm_importsValidRows_andBatchUpsertsDuplicatePeriod() throws Exception {
        byte[] xlsx = buildImportWorkbook(
                memberUsername, "Report Member", "1000.00", "2026-05-03", "REF-MAY", "first");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx);

        MvcResult preview = mockMvc.perform(multipart(
                                "/api/v1/cooperatives/" + cooperativeId + "/contributions/import/preview")
                        .file(file)
                        .param("year", "2026")
                        .param("month", "5")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validCount").value(1))
                .andReturn();

        UUID importId = UUID.fromString(objectMapper
                .readTree(preview.getResponse().getContentAsString())
                .path("data")
                .path("importId")
                .asText());

        long beforeConfirm = contributionRepository.count();
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/import/" + importId + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].memberUserId").value(memberUserId.toString()))
                .andExpect(jsonPath("$.data[0].status").value("PAID"))
                .andExpect(jsonPath("$.data[0].paidAmount").value(1000.0));

        assertThat(contributionRepository.count()).isEqualTo(beforeConfirm + 1);
        assertThat(contributionRepository
                        .findByCooperativeIdAndMemberUserIdAndYearAndMonth(cooperativeId, memberUserId, 2026, 5))
                .isPresent();

        // Second import for same period upserts via batchSave
        byte[] xlsx2 = buildImportWorkbook(
                memberUsername, "Report Member", "400.00", "2026-05-10", "REF-MAY-2", "upsert");
        MockMultipartFile file2 = new MockMultipartFile(
                "file",
                "import2.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx2);

        MvcResult preview2 = mockMvc.perform(multipart(
                                "/api/v1/cooperatives/" + cooperativeId + "/contributions/import/preview")
                        .file(file2)
                        .param("year", "2026")
                        .param("month", "5")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID importId2 = UUID.fromString(objectMapper
                .readTree(preview2.getResponse().getContentAsString())
                .path("data")
                .path("importId")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/import/" + importId2 + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.data[0].paidAmount").value(400.0));

        assertThat(contributionRepository.count()).isEqualTo(beforeConfirm + 1);
    }

    @Test
    void exportContributions_returnsNonEmptyPdfWithHeader() throws Exception {
        // seed a contribution via batch API
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": 750.0000,
                                      "paymentDate": "2026-06-02",
                                      "paymentReference": "EXP-1"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isOk());

        MvcResult export = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .header("Accept", "application/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-06-30",
                                  "year":2026,
                                  "month":6
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] body = export.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(200);
        assertThat(new String(body, 0, 4)).isEqualTo("%PDF");
        assertThat(export.getResponse().getHeader("Content-Disposition")).contains(".pdf");
    }

    @Test
    void exportContributions_withDateRangeAndNullYearMonthStatus_returnsPdf() throws Exception {
        seedContributionPeriod(6, "2026-06-02", "750.0000");

        MvcResult export = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .header("Accept", "application/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-08-26"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] body = export.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(200);
        assertThat(new String(body, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void contributionsSearch_nullableFilters_keepDateMemberYearMonthAndStatus() throws Exception {
        seedContributionPeriod(6, "2026-06-02", "750.0000");
        seedContributionPeriod(7, "2026-07-15", "1000.0000");

        assertThat(contributionRepository
                        .search(cooperativeId, null, null, null, null, null, null, Pageable.unpaged())
                        .getContent())
                .hasSize(2);

        assertThat(contributionRepository
                        .search(
                                cooperativeId,
                                null,
                                null,
                                null,
                                null,
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 6, 30),
                                Pageable.unpaged())
                        .getContent())
                .extracting(Contribution::getMonth)
                .containsExactly(6);

        assertThat(contributionRepository
                        .search(
                                cooperativeId,
                                memberUserId,
                                null,
                                null,
                                null,
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 12, 31),
                                Pageable.unpaged())
                        .getContent())
                .hasSize(2);

        assertThat(contributionRepository
                        .search(
                                cooperativeId,
                                UUID.randomUUID(),
                                null,
                                null,
                                null,
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 12, 31),
                                Pageable.unpaged())
                        .getContent())
                .isEmpty();

        assertThat(contributionRepository
                        .search(cooperativeId, null, 2026, 7, null, null, null, Pageable.unpaged())
                        .getContent())
                .extracting(Contribution::getMonth)
                .containsExactly(7);

        assertThat(contributionRepository
                        .search(
                                cooperativeId,
                                null,
                                null,
                                null,
                                ContributionStatus.PARTIALLY_PAID,
                                null,
                                null,
                                Pageable.unpaged())
                        .getContent())
                .extracting(Contribution::getMonth)
                .containsExactly(6);

        var paged = contributionRepository.search(
                cooperativeId,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                PageRequest.of(0, 1));
        assertThat(paged.getTotalElements()).isEqualTo(2);
        assertThat(paged.getContent()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(ReportType.class)
    void export_eachReportType_withFromAndTo_returnsPdf(ReportType type) throws Exception {
        MvcResult export = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .header("Accept", "application/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"%s",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-08-20"
                                }
                                """.formatted(type.name())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();
        byte[] body = export.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(200);
        assertThat(new String(body, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void filteredReportQueries_acceptAllNullableDateCombinations() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 8, 26);
        LocalDate[][] pairs = {
            {null, null},
            {from, null},
            {null, to},
            {from, to}
        };
        for (LocalDate[] pair : pairs) {
            contributionRepository.search(
                    cooperativeId, null, null, null, null, pair[0], pair[1], Pageable.unpaged());
            contributionRepository.search(
                    cooperativeId, memberUserId, null, null, null, pair[0], pair[1], Pageable.unpaged());
            specialContributionRepository.findFiltered(cooperativeId, null, null, pair[0], pair[1]);
            specialContributionRepository.findFiltered(cooperativeId, memberUserId, null, pair[0], pair[1]);
            loanRepaymentRepository.findFiltered(cooperativeId, null, pair[0], pair[1]);
            loanRepaymentRepository.findFiltered(cooperativeId, memberUserId, pair[0], pair[1]);
            finePaymentRepository.findFiltered(cooperativeId, null, null, pair[0], pair[1]);
            incomeExpenseRepository.findFiltered(cooperativeId, null, null, pair[0], pair[1], Pageable.unpaged());
            ledgerEntryRepository.findFiltered(
                    cooperativeId, null, pair[0], pair[1], null, null, Pageable.unpaged());
            ledgerEntryRepository.findFiltered(
                    cooperativeId, null, pair[0], pair[1], memberUserId, null, Pageable.unpaged());
        }
    }

    @Test
    void export_allowsFromDateBeforeRegistration() throws Exception {
        MvcResult export = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .header("Accept", "application/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"MEMBERS",
                                  "fromDate":"2023-12-01",
                                  "toDate":"2026-06-30"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] body = export.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(200);
        assertThat(new String(body, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void memberCannotExportFullFinancial_andTypesExcludeCoopWideReports() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/reports/types")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code=='CONTRIBUTIONS')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='FULL_FINANCIAL')]").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.code=='FINANCIAL_LEDGER')]").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.code=='INVESTMENTS')]").doesNotExist())
                .andExpect(jsonPath("$.data[0].selfScoped").value(true));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"FULL_FINANCIAL",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-06-30"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-06-30"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void export_rejectsMissingAndFutureTimeline() throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportType":"CONTRIBUTIONS"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2099-01-01",
                                  "toDate":"2099-12-31"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-06-30",
                                  "toDate":"2026-01-01"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void coopIsolation_onImportAndExport() throws Exception {
        byte[] xlsx = buildImportWorkbook(
                memberUsername, "Report Member", "100.00", "2026-07-01", "REF", "x");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx);

        // member exists only in cooperativeId — preview against other coop marks username invalid
        mockMvc.perform(multipart("/api/v1/cooperatives/" + otherCooperativeId + "/contributions/import/preview")
                        .file(file)
                        .param("year", "2026")
                        .param("month", "7")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validCount").value(0))
                .andExpect(jsonPath("$.data.invalidCount").value(1));

        // export for other coop succeeds for superadmin membership-bypass but contains no contrib rows for our member
        MvcResult export = mockMvc.perform(post("/api/v1/cooperatives/" + otherCooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-07-01",
                                  "toDate":"2026-07-31",
                                  "year":2026,
                                  "month":7
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(export.getResponse().getContentAsByteArray().length).isGreaterThan(100);

        // member of coop A cannot access coop B import/export
        String freshUser = "iso_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode fresh = registerMember(cooperativeId, freshUser, "Iso", "Member");
        String freshToken = loginAccessToken(freshUser, fresh.path("temporaryPassword").asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/contributions/import/template")
                        .header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cooperatives/" + otherCooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + freshToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportType":"CONTRIBUTIONS"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportTypes_listsAvailableTypes() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/reports/types")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(15))
                .andExpect(jsonPath("$.data[0].code").isNotEmpty())
                .andExpect(jsonPath("$.data[0].label").isNotEmpty());
    }

    @Test
    void whatsappShare_disabledByDefault_andDoesNotLeakCredentials() throws Exception {
        MvcResult status = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/reports/whatsapp-status")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.phoneNumberId").doesNotExist())
                .andReturn();
        assertThat(status.getResponse().getContentAsString()).doesNotContain("WHATSAPP_ACCESS_TOKEN");
        assertThat(status.getResponse().getContentAsString().toLowerCase()).doesNotContain("bearer ");

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/share-whatsapp")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-06-30",
                                  "recipientPhone":"0788123456"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("WhatsApp sharing is not configured"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void whatsappShare_unauthorizedAndOtherCooperativeAndInvalidPhone() throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/share-whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-06-30",
                                  "recipientPhone":"0788123456"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/reports/whatsapp-status"))
                .andExpect(status().isUnauthorized());

        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(post("/api/v1/cooperatives/" + otherCooperativeId + "/reports/share-whatsapp")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-06-30",
                                  "recipientPhone":"0788123456"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/share-whatsapp")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-06-30",
                                  "recipientPhone":"not-a-phone"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a valid Rwandan mobile number"));

        MvcResult stillPdf = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .header("Accept", "application/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-06-30"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();
        byte[] pdf = stillPdf.getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        assertThat(stillPdf.getResponse().getHeader("Content-Disposition")).contains(".pdf");
    }

    private void seedContributionPeriod(int month, String paymentDate, String paidAmount) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", String.valueOf(month))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": %s,
                                      "paymentDate": "%s",
                                      "paymentReference": "SEED-%s"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId, paidAmount, paymentDate, month)))
                .andExpect(status().isOk());
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

    private JsonNode registerMember(UUID coopId, String username, String first, String last) throws Exception {
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"%s",
                                  "lastName":"%s",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """.formatted(first, last, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        OpeningShareBalances.set(
                membershipRepository, coopId, UUID.fromString(data.path("userId").asText()), 1);
        return data;
    }

    private String loginAccessToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private static byte[] buildImportWorkbook(String... rowTriples) throws Exception {
        // each logical row: username, memberName, amount, paymentDate, reference, notes (6 fields)
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Import");
            Row header = sheet.createRow(0);
            String[] headers = {"Username", "Member Name", "Amount", "Payment Date", "Reference", "Notes"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            int rowNum = 1;
            for (int i = 0; i + 5 < rowTriples.length; i += 6) {
                Row row = sheet.createRow(rowNum++);
                for (int c = 0; c < 6; c++) {
                    row.createCell(c).setCellValue(rowTriples[i + c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
