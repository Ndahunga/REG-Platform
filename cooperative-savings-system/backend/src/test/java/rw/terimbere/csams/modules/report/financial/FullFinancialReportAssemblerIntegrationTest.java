package rw.terimbere.csams.modules.report.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.modules.report.export.FullFinancialPdfWriter;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FullFinancialReportAssemblerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FullFinancialReportAssembler assembler;

    @Autowired
    private CooperativeRepository cooperativeRepository;

    @Autowired
    private ContributionRepository contributionRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private LedgerService ledgerService;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        cooperativeId = createCooperative("FF D2 " + UUID.randomUUID().toString().substring(0, 8));
        memberId = registerMember(cooperativeId, "ffd2_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void operatingStatement_excludesContributionsAndPrincipalRepayments() {
        credit(LedgerTransactionType.LOAN_INTEREST_PAYMENT, "1200", LocalDate.of(2026, 3, 1));
        credit(LedgerTransactionType.FINE_PAYMENT, "300", LocalDate.of(2026, 3, 2));
        credit(LedgerTransactionType.INVESTMENT_PROFIT, "400", LocalDate.of(2026, 3, 3));
        credit(LedgerTransactionType.OTHER_INCOME, "100", LocalDate.of(2026, 3, 4));
        debit(LedgerTransactionType.GENERAL_EXPENSE, "500", LocalDate.of(2026, 3, 5));
        debit(LedgerTransactionType.INTEREST_EXPENSE, "50", LocalDate.of(2026, 3, 6));
        credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "10000", LocalDate.of(2026, 3, 7));
        credit(LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT, "2000", LocalDate.of(2026, 3, 8));

        FullFinancialReportModel model = assemble(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        FullFinancialReportModel.OperatingStatement op = model.getOperatingStatement();

        assertThat(op.getTotalOperatingIncome()).isEqualByComparingTo("2000.00");
        assertThat(op.getTotalOperatingExpenses()).isEqualByComparingTo("550.00");
        assertThat(op.getNetOperatingResult()).isEqualByComparingTo("1450.00");
        assertThat(op.getNetResultLabel()).isEqualTo("Net Operating Surplus");
        assertThat(op.getIncomeLines().stream().map(FullFinancialReportModel.StatementLine::getLabel))
                .noneMatch(l -> l.toLowerCase().contains("contribution") || l.toLowerCase().contains("principal"));
    }

    @Test
    void operatingDeficit_whenExpensesExceedIncome() {
        credit(LedgerTransactionType.OTHER_INCOME, "100", LocalDate.of(2026, 5, 1));
        debit(LedgerTransactionType.GENERAL_EXPENSE, "400", LocalDate.of(2026, 5, 2));

        FullFinancialReportModel model = assemble(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        assertThat(model.getOperatingStatement().getNetOperatingResult()).isEqualByComparingTo("-300.00");
        assertThat(model.getOperatingStatement().getNetResultLabel()).isEqualTo("Net Operating Deficit");
    }

    @Test
    void cashFlow_inflowsOutflowsOpeningClosingReconcile() {
        credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "5000", LocalDate.of(2025, 12, 1));
        debit(LedgerTransactionType.GENERAL_EXPENSE, "1000", LocalDate.of(2025, 12, 2));

        credit(LedgerTransactionType.SPECIAL_CONTRIBUTION, "800", LocalDate.of(2026, 1, 5));
        credit(LedgerTransactionType.SHARE_PURCHASE, "1200", LocalDate.of(2026, 1, 6));
        credit(LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT, "600", LocalDate.of(2026, 1, 7));
        credit(LedgerTransactionType.LOAN_INTEREST_PAYMENT, "100", LocalDate.of(2026, 1, 8));
        credit(LedgerTransactionType.FINE_PAYMENT, "50", LocalDate.of(2026, 1, 9));
        credit(LedgerTransactionType.INVESTMENT_CAPITAL_RETURN, "200", LocalDate.of(2026, 1, 10));
        credit(LedgerTransactionType.INVESTMENT_PROFIT, "75", LocalDate.of(2026, 1, 11));
        credit(LedgerTransactionType.OTHER_INCOME, "25", LocalDate.of(2026, 1, 12));
        debit(LedgerTransactionType.LOAN_DISBURSEMENT, "1500", LocalDate.of(2026, 1, 13));
        debit(LedgerTransactionType.INVESTMENT_OUTFLOW, "700", LocalDate.of(2026, 1, 14));
        debit(LedgerTransactionType.GENERAL_EXPENSE, "90", LocalDate.of(2026, 1, 15));
        debit(LedgerTransactionType.MEMBER_PAYOUT, "110", LocalDate.of(2026, 1, 16));

        FullFinancialReportModel model = assemble(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        FullFinancialReportModel.CashFlowStatement cf = model.getCashFlow();

        assertThat(cf.getOpeningLedgerBalance()).isEqualByComparingTo("4000.00");
        assertThat(cf.getTotalInflows()).isEqualByComparingTo("3050.00");
        assertThat(cf.getTotalOutflows()).isEqualByComparingTo("2400.00");
        assertThat(cf.getNetCashMovement()).isEqualByComparingTo("650.00");
        assertThat(cf.getClosingLedgerBalance()).isEqualByComparingTo("4650.00");
        assertThat(cf.isReconciliationHolds()).isTrue();
        assertThat(cf.getOpeningLedgerBalance().add(cf.getNetCashMovement()))
                .isEqualByComparingTo(cf.getClosingLedgerBalance());
    }

    @Test
    void financialPosition_isCurrentSnapshot_withoutFakeLiabilities() {
        FullFinancialReportModel model = assemble(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        FullFinancialReportModel.FinancialPosition pos = model.getCurrentPosition();

        assertThat(pos.getSnapshotAsOfDate()).isEqualTo(LocalDate.ofInstant(pos.getSnapshotAt(), ReportTimelineZone.ZONE));
        assertThat(pos.getLiabilitiesNote()).containsIgnoringCase("not currently modeled");
        assertThat(pos.getAssetLines().stream().map(FullFinancialReportModel.StatementLine::getLabel))
                .noneMatch(l -> l.equalsIgnoreCase("Liabilities"));
        assertThat(model.getHeader().getPeriodTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(model.getNotes().get(0)).contains("current snapshot");
        assertThat(model.getNotes().get(0)).doesNotContain("as of June 30");
    }

    @Test
    void memberContributionSummary_preservedFromD1() {
        for (int m = 1; m <= 3; m++) {
            contributionRepository.saveAndFlush(Contribution.builder()
                    .cooperativeId(cooperativeId)
                    .memberUserId(memberId)
                    .year(2026)
                    .month(m)
                    .shareCount(1)
                    .expectedAmount(new BigDecimal("10000.0000"))
                    .paidAmount(new BigDecimal("10000.0000"))
                    .outstandingAmount(BigDecimal.ZERO.setScale(4))
                    .status(ContributionStatus.PAID)
                    .paymentDate(LocalDate.of(2026, m, 10))
                    .ledgerRevision(0)
                    .build());
        }

        FullFinancialReportModel model = assemble(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        assertThat(model.getMemberContributions().isSingleMonth()).isFalse();
        assertThat(model.getMemberContributions().getSheetTitle()).isEqualTo("Member Contribution Summary");
        assertThat(model.getMemberContributions().getRows()).hasSize(1);
        assertThat(model.getMemberContributions().getRows().get(0).getPeriodsCounted()).isEqualTo(3);
        assertThat(model.getMemberContributions().getTotalExpected()).isEqualByComparingTo("30000.00");
    }

    @Test
    void pdfExport_containsStatementSectionsAndPreservesAccess() throws Exception {
        credit(LedgerTransactionType.LOAN_INTEREST_PAYMENT, "500", LocalDate.of(2026, 2, 1));
        debit(LedgerTransactionType.GENERAL_EXPENSE, "100", LocalDate.of(2026, 2, 2));

        FullFinancialReportModel model = assemble(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
        byte[] pdf = FullFinancialPdfWriter.write(model);
        assertThat(pdf.length).isGreaterThan(500);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("Candara");

        // Section order is encoded in model; assert model order contract for PDF writer
        assertThat(model.getExecutiveSummary()).isNotEmpty();
        assertThat(model.getCurrentPosition()).isNotNull();
        assertThat(model.getOperatingStatement()).isNotNull();
        assertThat(model.getCashFlow()).isNotNull();
        assertThat(model.getMemberContributions()).isNotNull();
        assertThat(model.getSupportingSchedules()).isNotEmpty();
        assertThat(model.getNotes()).isNotEmpty();

        MvcResult export = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "reportType":"FULL_FINANCIAL",
                                  "fromDate":"2026-02-01",
                                  "toDate":"2026-02-28"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();
        assertThat(export.getResponse().getContentAsByteArray().length).isGreaterThan(500);
    }

    private FullFinancialReportModel assemble(LocalDate from, LocalDate to) {
        return assembler.assemble(
                cooperativeRepository.findById(cooperativeId).orElseThrow(),
                ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(from)
                        .toDate(to)
                        .build(),
                "superadmin",
                Instant.now());
    }

    private void credit(LedgerTransactionType type, String amount, LocalDate date) {
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(type)
                .creditAmount(new BigDecimal(amount))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(date)
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("ff-c-" + UUID.randomUUID())
                .description("test")
                .build());
    }

    private void debit(LedgerTransactionType type, String amount, LocalDate date) {
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(type)
                .debitAmount(new BigDecimal(amount))
                .creditAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(date)
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("ff-d-" + UUID.randomUUID())
                .description("test")
                .build());
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

    private UUID registerMember(UUID coopId, String username) throws Exception {
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "firstName":"Vicky",
                                  "lastName":"Delta",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """
                                        .formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        OpeningShareBalances.set(membershipRepository, coopId, userId, 1);
        return userId;
    }

    private String login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    /** Local alias so the snapshot date assertion stays readable without importing ZONE twice. */
    private static final class ReportTimelineZone {
        static final java.time.ZoneId ZONE = rw.terimbere.csams.modules.report.service.ReportTimelineValidator.ZONE;
    }
}
