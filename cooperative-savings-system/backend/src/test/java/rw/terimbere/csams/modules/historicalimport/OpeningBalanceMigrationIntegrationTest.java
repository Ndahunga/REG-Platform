package rw.terimbere.csams.modules.historicalimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImport;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportRow;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRowRepository;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.entity.MemberOpeningBalance;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpeningBalanceMigrationIntegrationTest {

    private static final String CUTOVER = "2026-09-30";
    private static final String FUNDS = "800000";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CooperativeMembershipRepository membershipRepository;
    @Autowired private MemberOpeningBalanceRepository openingBalanceRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private SharePurchaseRepository sharePurchaseRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LoanInstallmentRepository loanInstallmentRepository;
    @Autowired private LoanRepaymentRepository loanRepaymentRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private HistoricalImportRepository historicalImportRepository;
    @Autowired private HistoricalImportRowRepository historicalImportRowRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID otherCooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        cooperativeId = createCooperative("Mig Coop " + id8());
        otherCooperativeId = createCooperative("Other Mig " + id8());
    }

    // ------------------------------------------------------------------ template / authorization / cutover

    @Test
    void template_isTheTwoSheetWorkbook_andOnlyLeadershipCanUseIt() throws Exception {
        MvcResult result = mockMvc.perform(get(base(cooperativeId) + "/template")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("Member Registration");
            assertThat(workbook.getSheetName(1)).isEqualTo("Share Capital Registration");
        }
        // loans are imported separately, with their own one-sheet template
        MvcResult loanTemplate = mockMvc.perform(get(base(cooperativeId) + "/loan-template")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(loanTemplate.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("Loan Registration");
        }

        String president = registerAndLogin(cooperativeId, "PRESIDENT");
        String vice = registerAndLogin(cooperativeId, "VICE_PRESIDENT");
        String accountant = registerAndLogin(cooperativeId, "ACCOUNTANT");
        String member = registerAndLogin(cooperativeId, "MEMBER");
        MockMultipartFile file = MigrationWorkbooks.create().member("Au Th", "auth_" + id8(), "0").file("a.xlsx");

        mockMvc.perform(get(base(cooperativeId) + "/template").header("Authorization", "Bearer " + president))
                .andExpect(status().isOk());
        mockMvc.perform(get(base(cooperativeId) + "/template").header("Authorization", "Bearer " + vice))
                .andExpect(status().isOk());
        mockMvc.perform(preview(file, CUTOVER, president)).andExpect(status().isOk());
        mockMvc.perform(preview(file, CUTOVER, accountant)).andExpect(status().isForbidden());
        mockMvc.perform(preview(file, CUTOVER, member)).andExpect(status().isForbidden());
        mockMvc.perform(preview(file, CUTOVER, president).with(r -> r)).andReturn();
        mockMvc.perform(multipart(base(otherCooperativeId) + "/preview")
                        .file(file)
                        .param("cutoverDate", CUTOVER)
                        .param("openingAvailableFunds", FUNDS)
                        .header("Authorization", "Bearer " + president))
                .andExpect(status().isForbidden());
    }

    @Test
    void cutoverDate_isRequired_andCannotBeInTheFuture() throws Exception {
        MockMultipartFile file = MigrationWorkbooks.create().member("A B", "cut_" + id8(), "1").file("c.xlsx");
        mockMvc.perform(multipart(base(cooperativeId) + "/preview")
                        .file(file)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(preview(file, "2099-01-01", superAdminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUTOVER_IN_FUTURE"));
        mockMvc.perform(preview(file, CUTOVER, superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cutoverDate").value(CUTOVER))
                .andExpect(jsonPath("$.data.format").value("OPENING_BALANCE"));
    }

    // ------------------------------------------------------------------ full migration

    @Test
    void fullMigration_storesOpeningBalancesAndLoansButNoFakeTransactions() throws Exception {
        String alice = "alice_" + id8();
        String bob = "bob_" + id8();
        String nid = "1" + String.format("%015d", Math.abs(System.nanoTime()) % 1_000_000_000_000_000L);
        MigrationWorkbooks workbook = fullWorkbook(alice, bob, nid);

        MockMultipartFile upload = workbook.file("full.xlsx");
        JsonNode preview = previewOk(upload);
        assertThat(preview.path("status").asText()).isEqualTo("READY");
        assertThat(preview.path("confirmAllowed").asBoolean()).isTrue();
        assertThat(preview.path("migration").path("cutoverDate").asText()).isEqualTo(CUTOVER);
        assertThat(preview.path("migration").path("members").path("totalRows").asInt()).isEqualTo(2);
        assertThat(preview.path("migration").path("members").path("totalOpeningContributions").decimalValue())
                .isEqualByComparingTo("850000");
        assertThat(preview.path("migration").path("shareCapital").path("totalOpeningShares").asLong()).isEqualTo(8);
        assertThat(preview.path("migration").path("shareCapital").path("totalOpeningShareCapital").decimalValue())
                .isEqualByComparingTo("240000");
        assertThat(preview.path("kind").asText()).isEqualTo("OPENING_BALANCE");
        assertThat(preview.path("migration").path("loans").path("totalRows").asInt()).isZero();
        // preview never persists business data
        assertThat(userRepository.findByUsernameIgnoreCaseAndDeletedFalse(alice)).isEmpty();
        assertThat(openingBalanceRepository.findByCooperativeId(cooperativeId)).isEmpty();

        UUID importId = UUID.fromString(preview.path("importId").asText());
        JsonNode result = confirmOk(importId);
        assertThat(result.path("membersImported").asInt()).isEqualTo(2);
        assertThat(result.path("memberOpeningBalancesImported").asInt()).isEqualTo(2);
        assertThat(result.path("shareOpeningBalancesImported").asInt()).isEqualTo(2);
        assertThat(result.path("loansImported").asInt()).isZero();
        assertThat(result.path("totalHistoricalContributions").decimalValue()).isEqualByComparingTo("850000");
        assertThat(result.path("totalHistoricalShares").asLong()).isEqualTo(8);
        assertThat(result.path("totalHistoricalShareCapital").decimalValue()).isEqualByComparingTo("240000");

        HistoricalImport stored = historicalImportRepository.findById(importId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(HistoricalImportStatus.CONFIRMED);
        assertThat(stored.getCutoverDate()).isEqualTo(LocalDate.parse(CUTOVER));
        assertThat(stored.getOpeningAvailableFunds()).isEqualByComparingTo(FUNDS);

        // members: split names, ordinary MEMBER role only, membership dated at the cutover
        User aliceUser = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(alice).orElseThrow();
        assertThat(aliceUser.getFirstName()).isEqualTo("Alice");
        assertThat(aliceUser.getLastName()).isEqualTo("Mukamana Uwase");
        assertThat(aliceUser.getNationalId()).isNull();
        assertThat(aliceUser.getRoleCodes()).containsExactly("MEMBER");
        User bobUser = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(bob).orElseThrow();
        assertThat(bobUser.getFirstName()).isEqualTo("Bob");
        assertThat(bobUser.getLastName()).isEmpty();
        assertThat(bobUser.getFullName()).isEqualTo("Bob");
        assertThat(bobUser.getNationalId()).isEqualTo(nid);
        assertThat(bobUser.getRoleCodes()).containsExactly("MEMBER");

        CooperativeMembership aliceMembership =
                membershipRepository.findByCooperativeIdAndUserId(cooperativeId, aliceUser.getId()).orElseThrow();
        assertThat(aliceMembership.getMembershipDate()).isEqualTo(LocalDate.parse(CUTOVER));
        assertThat(aliceMembership.getRoleInCooperative()).isEqualTo("MEMBER");
        assertThat(aliceMembership.getShareCount()).isEqualTo(8);
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, bobUser.getId())
                        .orElseThrow()
                        .getShareCount())
                .isZero();

        // opening balances
        MemberOpeningBalance aliceBalance = openingBalanceRepository
                .findByCooperativeIdAndMemberUserId(cooperativeId, aliceUser.getId())
                .orElseThrow();
        assertThat(aliceBalance.getOpeningContributionAmount()).isEqualByComparingTo("850000");
        assertThat(aliceBalance.getOpeningShareCount()).isEqualTo(8);
        assertThat(aliceBalance.getOpeningShareCapitalAmount()).isEqualByComparingTo("240000");
        assertThat(aliceBalance.getCutoverDate()).isEqualTo(LocalDate.parse(CUTOVER));
        assertThat(aliceBalance.getHistoricalImportId()).isEqualTo(importId);
        MemberOpeningBalance bobBalance = openingBalanceRepository
                .findByCooperativeIdAndMemberUserId(cooperativeId, bobUser.getId())
                .orElseThrow();
        assertThat(bobBalance.getOpeningContributionAmount()).isEqualByComparingTo("0");
        assertThat(bobBalance.getOpeningShareCount()).isZero();
        assertThat(bobBalance.getOpeningShareCapitalAmount()).isEqualByComparingTo("0");

        // no fake transactions of any kind
        assertThat(contributionRepository.findAll().stream().filter(c -> cooperativeId.equals(c.getCooperativeId())))
                .isEmpty();
        assertThat(sharePurchaseRepository.findAll().stream().filter(p -> cooperativeId.equals(p.getCooperativeId())))
                .isEmpty();
        assertThat(loanRepaymentRepository.findAll().stream().filter(r -> cooperativeId.equals(r.getCooperativeId())))
                .isEmpty();
        assertThat(ledgerEntryRepository.findAll().stream().filter(l -> cooperativeId.equals(l.getCooperativeId())))
                .isEmpty();

        // the loans outstanding at the cutover come in separately, on top of the confirmed opening balances
        assertThat(loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent()).isEmpty();
        JsonNode loanPreview = previewOk(loanWorkbook(alice, bob).file("loans.xlsx"));
        assertThat(loanPreview.path("status").asText()).as(loanPreview.path("errors").toString()).isEqualTo("READY");
        assertThat(loanPreview.path("kind").asText()).isEqualTo("OPENING_LOANS");
        JsonNode loans = loanPreview.path("migration").path("loans");
        assertThat(loans.path("endOfTermCount").asInt()).isEqualTo(1);
        assertThat(loans.path("monthlyCount").asInt()).isEqualTo(1);
        assertThat(loans.path("totalOriginalPrincipal").decimalValue()).isEqualByComparingTo("1300000");
        assertThat(loans.path("totalOutstandingPrincipal").decimalValue()).isEqualByComparingTo("750000");
        assertThat(loans.path("totalAmountPaid").decimalValue()).isEqualByComparingTo("600000");
        UUID loanImportId = UUID.fromString(loanPreview.path("importId").asText());
        JsonNode loanResult = confirmOk(loanImportId);
        assertThat(loanResult.path("membersImported").asInt()).isZero();
        assertThat(loanResult.path("memberOpeningBalancesImported").asInt()).isZero();
        assertThat(loanResult.path("loansImported").asInt()).isEqualTo(2);
        assertThat(loanResult.path("loanInstallmentsCreated").asInt()).isEqualTo(11); // 10 remaining monthly + 1 end-of-term
        assertThat(loanResult.path("totalOriginalLoanAmount").decimalValue()).isEqualByComparingTo("1300000");
        assertThat(loanResult.path("totalOutstandingLoanPrincipal").decimalValue()).isEqualByComparingTo("750000");
        assertThat(loanResult.path("totalHistoricalLoanPayments").decimalValue()).isEqualByComparingTo("600000");
        assertThat(contributionRepository.findAll().stream().filter(c -> cooperativeId.equals(c.getCooperativeId())))
                .as("importing the loans creates no transactions either").isEmpty();
        assertThat(ledgerEntryRepository.findAll().stream().filter(l -> cooperativeId.equals(l.getCooperativeId()))).isEmpty();

        // imported loans
        List<Loan> imported = loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent();
        assertThat(imported).hasSize(2).allSatisfy(l -> assertThat(l.getOpeningImportId()).isEqualTo(loanImportId));
        Loan monthly = imported.stream().filter(l -> l.getMemberUserId().equals(aliceUser.getId())).findFirst().orElseThrow();
        assertThat(monthly.getPrincipalAmount()).isEqualByComparingTo("1200000");
        assertThat(monthly.getOutstandingPrincipal()).isEqualByComparingTo("650000");
        assertThat(monthly.getOutstandingInterest()).isEqualByComparingTo("238000");
        assertThat(monthly.getTotalRepaidPrincipal()).isEqualByComparingTo("550000");
        assertThat(monthly.getTotalRepaidInterest()).isEqualByComparingTo("50000");
        assertThat(monthly.getInterestAmount()).isEqualByComparingTo("288000");
        assertThat(monthly.getDisbursementDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(monthly.getDueDate()).isEqualTo(LocalDate.of(2027, 1, 5));
        assertThat(monthly.getStatus()).isEqualTo(LoanStatus.OVERDUE); // instalments already due and unpaid
        assertThat(monthly.getApprovedBy()).isNull();
        assertThat(monthly.getDisbursedBy()).isNull();
        List<LoanInstallment> monthlyRows = loanInstallmentRepository.findByLoanIdOrderByInstallmentNumberAsc(monthly.getId());
        assertThat(monthlyRows).hasSize(10);
        assertThat(monthlyRows.get(0).getInstallmentNumber()).isEqualTo(3);
        assertThat(monthlyRows.stream().map(LoanInstallment::getPrincipalDue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("650000");

        Loan endOfTerm = imported.stream().filter(l -> l.getMemberUserId().equals(bobUser.getId())).findFirst().orElseThrow();
        assertThat(endOfTerm.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(endOfTerm.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(endOfTerm.getOutstandingPrincipal()).isEqualByComparingTo("100000");
        assertThat(endOfTerm.getOutstandingInterest()).isEqualByComparingTo("18000");
        List<LoanInstallment> single = loanInstallmentRepository.findByLoanIdOrderByInstallmentNumberAsc(endOfTerm.getId());
        assertThat(single).hasSize(1);
        assertThat(single.get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 30));

        // import rows link back to what they produced
        for (UUID id : List.of(importId, loanImportId)) {
            List<HistoricalImportRow> rows = historicalImportRowRepository.findByImportIdOrderBySheetAscRowNumberAsc(id);
            assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(r.getResultingEntityId()).isNotNull());
        }
        // a second loan file is refused: the loans outstanding at the cutover are imported once
        JsonNode secondLoans = previewOk(loanWorkbook(alice, bob).loan(alice, "END_OF_TERM", "50000", "2", "2026-08-01", "6", "", "", "")
                .file("loans-again.xlsx"));
        assertThat(secondLoans.path("status").asText()).isEqualTo("INVALID");
        assertThat(secondLoans.path("errors").toString()).contains("LOANS_ALREADY_MIGRATED");

        // confirming again / uploading the same confirmed file again is refused
        mockMvc.perform(post(base(cooperativeId) + "/" + importId + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(preview(upload, CUTOVER, superAdminToken)).andExpect(status().is4xxClientError());
        // a re-exported workbook with different content (different hash) is stopped by the member-level protection
        JsonNode again = previewOk(MigrationWorkbooks.create()
                .member("Alice  Mukamana   Uwase", alice, alice + "@test.local", "0781234567", "", "850001")
                .file("full-again.xlsx"));
        assertThat(again.path("status").asText()).isEqualTo("INVALID");
        assertThat(again.path("errors").toString()).contains("OPENING_BALANCE_EXISTS").contains("ALREADY_MIGRATED");
        assertThat(openingBalanceRepository.findByCooperativeId(cooperativeId)).hasSize(2);
        assertThat(loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent()).hasSize(2);

        // history exposes the import, cutover date and result
        mockMvc.perform(get(base(cooperativeId)).header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.status=='CONFIRMED')].kind").value(
                        org.hamcrest.Matchers.containsInAnyOrder("OPENING_BALANCE", "OPENING_LOANS")))
                .andExpect(jsonPath("$.data[?(@.status=='CONFIRMED')].cutoverDate").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(CUTOVER))));
        mockMvc.perform(get(base(cooperativeId) + "/" + loanImportId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.migration.result.loansImported").value(2));
    }

    // ------------------------------------------------------------------ financial totals

    @Test
    void openingBalancesFeedFundValuationAndMemberTotalsExactlyOnce_andLiveActivityAddsOn() throws Exception {
        String alice = "alice_" + id8();
        String bob = "bob_" + id8();
        confirmOk(previewOk(fullWorkbook(alice, bob, "").file("fin.xlsx")).path("importId").asText());
        confirmOk(previewOk(loanWorkbook(alice, bob).file("fin-loans.xlsx")).path("importId").asText());
        UUID aliceId = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(alice).orElseThrow().getId();
        UUID bobId = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(bob).orElseThrow().getId();

        // explicit cutover cash 800,000 (member contributions 850,000 / share capital 240,000 are NOT cash)
        // imported loans (750,000) are not deducted again; valuation adds loans + interest receivable on top.
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableFunds").value(800000.0))
                .andExpect(jsonPath("$.data.outstandingLoans").value(750000.0))
                .andExpect(jsonPath("$.data.unpaidInterest").value(256000.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(1806000.0))
                .andExpect(jsonPath("$.data.totalExistingShares").value(8));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(800000.0))
                .andExpect(jsonPath("$.data.regularContributionsTotal").value(850000.0))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal").value(750000.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + aliceId + "/financial-summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regularContributions").value(850000.0))
                .andExpect(jsonPath("$.data.openingContributions").value(850000.0))
                .andExpect(jsonPath("$.data.openingShareCapital").value(240000.0))
                .andExpect(jsonPath("$.data.contributedShareCapital").value(240000.0))
                .andExpect(jsonPath("$.data.openingShareCount").value(8))
                .andExpect(jsonPath("$.data.sharesHeld").value(8))
                .andExpect(jsonPath("$.data.openingCutoverDate").value(CUTOVER))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal").value(650000.0))
                .andExpect(jsonPath("$.data.loansReceived").value(1200000.0));

        // a live (post-cutover) contribution adds to, and is separate from, the opening balance
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"memberUserId":"%s","paidAmount":1000,"paymentDate":"2026-10-10"}]}
                                """.formatted(aliceId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + aliceId + "/financial-summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regularContributions").value(851000.0))
                .andExpect(jsonPath("$.data.openingContributions").value(850000.0));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.availableFunds").value(801000.0));

        // the October period contains only the October transaction, not the opening balance
        assertThat(contributionRepository
                        .sumPaidForPeriod(cooperativeId, 2026, 10)
                        .compareTo(new BigDecimal("1000")))
                .isZero();
        assertThat(contributionRepository.sumPaidForPeriod(cooperativeId, 2026, 9).signum()).isZero();

        // a normal repayment works against the imported loan and writes only live ledger entries
        UUID endOfTermLoan = loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent().stream()
                .filter(l -> l.getMemberUserId().equals(bobId))
                .findFirst()
                .orElseThrow()
                .getId();
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + endOfTermLoan + "/repayments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 30000, "paymentDate": "2026-10-12", "paymentReference": "LIVE-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestPortion").value(18000.0))
                .andExpect(jsonPath("$.data.principalPortion").value(12000.0));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + endOfTermLoan)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(88000.0))
                .andExpect(jsonPath("$.data.outstandingInterest").value(0.0));
        assertThat(loanRepaymentRepository.findAll().stream().filter(r -> cooperativeId.equals(r.getCooperativeId())))
                .hasSize(1);
        // cash: 800,000 + 1,000 contribution + 18,000 interest + 12,000 principal repaid; value only +1,000
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.availableFunds").value(831000.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(1807000.0));
    }

    // ------------------------------------------------------------------ member validation

    @Test
    void memberRows_reportSheetRowFieldAndMessage_forEveryRule() throws Exception {
        String dup = "dup_" + id8();
        String nid = "2" + String.format("%015d", Math.abs(System.nanoTime()) % 1_000_000_000_000_000L);
        MigrationWorkbooks workbook = MigrationWorkbooks.create()
                .member("Ok One", dup, dup + "@test.local", "0781234567", nid, "100") // row 2 fine
                .member("Dup Name", dup.toUpperCase(), "other_" + id8() + "@test.local", "0781234567", "", "5") // row 3
                .member("Dup Email", "e_" + id8(), dup + "@test.local", "0781234567", "", "5") // row 4
                .member("Dup Nid", "n_" + id8(), "n_" + id8() + "@test.local", "0781234567", nid, "5") // row 5
                .member("Bad Phone", "p_" + id8(), "p_" + id8() + "@test.local", "12345", "", "5") // row 6
                .member("Bad Nid", "b_" + id8(), "b_" + id8() + "@test.local", "0781234567", "123", "5") // row 7
                .member("Negative", "neg_" + id8(), "neg_" + id8() + "@test.local", "0781234567", "", "-1") // row 8
                .member("Bad Email", "be_" + id8(), "not-an-email", "0781234567", "", "5") // row 9
                .member("", "", "x@test.local", "0781234567", "", "") // row 10
                .member("Text Amount", "t_" + id8(), "t_" + id8() + "@test.local", "0781234567", "", "abc"); // row 11
        JsonNode preview = previewOk(workbook.file("members.xlsx"));
        assertThat(preview.path("status").asText()).isEqualTo("INVALID");
        assertThat(preview.path("confirmAllowed").asBoolean()).isFalse();
        JsonNode errors = preview.path("errors");

        assertError(errors, "Member Registration", 3, "Username", "DUPLICATE_USERNAME");
        assertError(errors, "Member Registration", 4, "Email", "DUPLICATE_EMAIL");
        assertError(errors, "Member Registration", 5, "National ID (Optional)", "DUPLICATE_NATIONAL_ID");
        assertError(errors, "Member Registration", 6, "Phone Number", "INVALID_PHONE");
        assertError(errors, "Member Registration", 7, "National ID (Optional)", "INVALID_NATIONAL_ID");
        assertError(errors, "Member Registration", 8, "Total Contributions to Date (RWF)", "NEGATIVE");
        assertError(errors, "Member Registration", 9, "Email", "INVALID_EMAIL");
        assertError(errors, "Member Registration", 10, "Full Name", "REQUIRED");
        assertError(errors, "Member Registration", 10, "Username", "REQUIRED");
        assertError(errors, "Member Registration", 10, "Total Contributions to Date (RWF)", "REQUIRED");
        assertError(errors, "Member Registration", 11, "Total Contributions to Date (RWF)", "INVALID_NUMBER");
        assertThat(findError(errors, "Member Registration", 3, "DUPLICATE_USERNAME").path("message").asText())
                .contains("duplicated in this workbook");
        assertThat(preview.path("migration").path("members").path("invalidRows").asInt()).isEqualTo(9);
    }

    @Test
    void zeroContributionAndBlankNationalId_areValid() throws Exception {
        JsonNode preview = previewOk(MigrationWorkbooks.create()
                .member("Zero Case", "zero_" + id8(), "0")
                .member("Nid Case", "nidok_" + id8(), "nidok_" + id8() + "@test.local", "+250781234568", "", "0.00")
                .file("zero.xlsx"));
        assertThat(preview.path("status").asText()).isEqualTo("READY");
        assertThat(preview.path("invalidRows").asInt()).isZero();
    }

    @Test
    void identityRules_safeReuse_conflictsNoAliasing_noRoleEscalation_noSecondOpeningBalance() throws Exception {
        // an existing accountant of this scheme, plus an unrelated existing user in another scheme
        String existing = "exist_" + id8();
        registerMember(cooperativeId, existing, "ACCOUNTANT");
        String elsewhere = "else_" + id8();
        registerMember(otherCooperativeId, elsewhere, "MEMBER");
        User existingUser = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(existing).orElseThrow();
        int usersBefore = userRepository.findAll().size();

        // alias: different username but an existing user's email  -> conflict
        // same username, different email -> conflict
        // unrelated existing user, safe match on username + email -> allowed
        MigrationWorkbooks conflicts = MigrationWorkbooks.create()
                .member("Alias Person", "alias_" + id8(), existing + "@test.local", "0781234567", "", "1")
                .member("Other Email", existing, "different_" + id8() + "@test.local", "0781234567", "", "1")
                .member("Else Where", elsewhere, elsewhere + "@test.local", "0781234567", "", "7");
        JsonNode preview = previewOk(conflicts.file("conflict.xlsx"));
        assertError(preview.path("errors"), "Member Registration", 2, "Username", "IDENTITY_CONFLICT");
        assertError(preview.path("errors"), "Member Registration", 3, "Email", "IDENTITY_CONFLICT");
        assertThat(preview.path("errors").size()).isEqualTo(2);
        assertThat(preview.path("status").asText()).isEqualTo("INVALID");

        // safe match for an existing member of this scheme (no opening balance yet): reused, role untouched
        JsonNode ok = previewOk(MigrationWorkbooks.create()
                .member("Exist Ing", existing, existing + "@test.local", "0781234567", "", "300")
                .shares(existing, "2", "20000")
                .file("safe.xlsx"));
        assertThat(ok.path("status").asText()).isEqualTo("READY");
        JsonNode result = confirmOk(ok.path("importId").asText());
        assertThat(result.path("membersImported").asInt()).isZero(); // already a member: not a new membership
        assertThat(result.path("memberOpeningBalancesImported").asInt()).isEqualTo(1);
        assertThat(userRepository.findAll().size()).isEqualTo(usersBefore);
        User reloaded = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(existing).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(existingUser.getId());
        assertThat(reloaded.getRoleCodes()).doesNotContain("PRESIDENT", "SUPER_ADMIN", "COOPERATIVE_ADMIN");
        CooperativeMembership membership =
                membershipRepository.findByCooperativeIdAndUserId(cooperativeId, existingUser.getId()).orElseThrow();
        assertThat(membership.getRoleInCooperative()).isEqualTo("ACCOUNTANT");
        assertThat(membership.getShareCount()).isEqualTo(2);

        // a second, different workbook must not stack another opening balance on the same member
        JsonNode second = previewOk(MigrationWorkbooks.create()
                .member("Exist Ing", existing, existing + "@test.local", "0781234567", "", "999")
                .file("second.xlsx"));
        assertThat(second.path("status").asText()).isEqualTo("INVALID");
        assertError(second.path("errors"), "Member Registration", 2, "Username", "OPENING_BALANCE_EXISTS");
        JsonNode secondShares = previewOk(MigrationWorkbooks.create().shares(existing, "1", "10").file("second-s.xlsx"));
        assertError(secondShares.path("errors"), "Share Capital Registration", 2, "Username", "OPENING_BALANCE_EXISTS");
        assertThat(openingBalanceRepository
                        .findByCooperativeIdAndMemberUserId(cooperativeId, existingUser.getId())
                        .orElseThrow()
                        .getOpeningContributionAmount())
                .isEqualByComparingTo("300");
    }

    // ------------------------------------------------------------------ share validation

    @Test
    void shareRows_validateMembershipDuplicatesCountsAndCapital() throws Exception {
        String known = "known_" + id8();
        String existing = "sh_exist_" + id8();
        registerMember(cooperativeId, existing, "MEMBER");
        MigrationWorkbooks workbook = MigrationWorkbooks.create()
                .member("Known Member", known, "10")
                .shares(known.toUpperCase(), "3", "90000") // row 2: ok, case-insensitive match
                .shares(known, "1", "1") // row 3: duplicate
                .shares("ghost_" + id8(), "1", "1") // row 4: unknown
                .shares(existing, "0", "0") // row 5: existing member, zero shares/capital
                .shares(existing + "x", "1", "1") // row 6: unknown
                .shares(existing, "2.5", "1") // row 7: duplicate + non-integer
                .shares("neg_" + id8(), "-1", "5") // row 8
                .shares("max_" + id8(), "1001", "5") // row 9
                .shares("cap_" + id8(), "1", "-5") // row 10
                .shares("blank_" + id8(), "", ""); // row 11
        JsonNode errors = previewOk(workbook.file("shares.xlsx")).path("errors");
        assertThat(findOptional(errors, "Share Capital Registration", 2)).isEmpty();
        assertError(errors, "Share Capital Registration", 3, "Username", "DUPLICATE_USERNAME");
        assertError(errors, "Share Capital Registration", 4, "Username", "UNKNOWN_MEMBER");
        assertThat(findOptional(errors, "Share Capital Registration", 5)).isEmpty();
        assertError(errors, "Share Capital Registration", 7, "Share Count", "NOT_INTEGER");
        assertError(errors, "Share Capital Registration", 8, "Share Count", "NEGATIVE");
        assertError(errors, "Share Capital Registration", 9, "Share Count", "TOO_MANY_SHARES");
        assertError(errors, "Share Capital Registration", 10, "Share Capital Contributed to Date (RWF)", "NEGATIVE");
        assertError(errors, "Share Capital Registration", 11, "Share Count", "REQUIRED");
        assertThat(findError(errors, "Share Capital Registration", 4, "UNKNOWN_MEMBER").path("message").asText())
                .contains("does not exist in Member Registration or this Saving Scheme");
    }

    @Test
    void shareCapital_isAuthoritativeNotCountTimesPrice_maximumSharesAllowed() throws Exception {
        String max = "maxok_" + id8();
        JsonNode preview = previewOk(MigrationWorkbooks.create()
                .member("Max Shares", max, "0")
                .shares(max, "1000", "123456.78")
                .file("max.xlsx"));
        assertThat(preview.path("status").asText()).isEqualTo("READY");
        confirmOk(preview.path("importId").asText());
        UUID id = userRepository.findByUsernameIgnoreCaseAndDeletedFalse(max).orElseThrow().getId();
        MemberOpeningBalance balance =
                openingBalanceRepository.findByCooperativeIdAndMemberUserId(cooperativeId, id).orElseThrow();
        assertThat(balance.getOpeningShareCount()).isEqualTo(1000);
        assertThat(balance.getOpeningShareCapitalAmount()).isEqualByComparingTo("123456.78");
        assertThat(sharePurchaseRepository.findAll().stream().filter(p -> cooperativeId.equals(p.getCooperativeId())))
                .isEmpty();
    }

    // ------------------------------------------------------------------ loan validation

    @Test
    void loanRows_reportClearErrorsForEveryRule() throws Exception {
        String u = "ln_" + id8();
        registerMember(cooperativeId, u, "MEMBER");
        MigrationWorkbooks workbook = MigrationWorkbooks.create()
                // row 2: outstanding above the original amount
                .loan(u, "MONTHLY", "100000", "2", "2026-05-01", "6", "0", "100001", "")
                // row 3: paid > 0 without a last payment date
                .loan(u, "MONTHLY", "200000", "2", "2026-05-01", "6", "50000", "160000", "")
                // row 4: paid smaller than the principal already repaid
                .loan(u, "MONTHLY", "300000", "2", "2026-05-01", "6", "10000", "200000", "2026-08-01")
                // row 5: unknown member
                .loan("ghost_" + id8(), "END_OF_TERM", "100000", "2", "2026-05-01", "6", "", "", "")
                // row 6: bad type
                .loan(u, "WEEKLY", "100000", "2", "2026-05-01", "6", "", "", "")
                // row 7: issued after cutover
                .loan(u, "END_OF_TERM", "100000", "2", "2026-10-05", "6", "", "", "")
                // row 8: zero term, negative rate
                .loan(u, "END_OF_TERM", "100000", "-1", "2026-05-01", "0", "", "", "")
                // row 9: MONTHLY without paid / outstanding
                .loan(u, "MONTHLY", "100000", "2", "2026-05-01", "6", "", "", "")
                // row 10: zero amount
                .loan(u, "END_OF_TERM", "0", "2", "2026-05-01", "6", "", "", "")
                // row 11: last payment after the cutover
                .loan(u, "MONTHLY", "100000", "2", "2026-05-01", "6", "20000", "90000", "2026-10-02")
                // row 12: invalid date text
                .loan(u, "END_OF_TERM", "100000", "2", "soon", "6", "", "", "");
        JsonNode preview = previewOk(workbook.file("loans.xlsx"));
        JsonNode errors = preview.path("errors");
        assertError(errors, "Loan Registration", 2, "Outstanding Principal (RWF)", "INCONSISTENT_LOAN");
        assertThat(findError(errors, "Loan Registration", 2, "INCONSISTENT_LOAN").path("message").asText())
                .isEqualTo("Outstanding principal cannot exceed the original loan amount.");
        assertError(errors, "Loan Registration", 3, "Last Payment Date", "REQUIRED");
        assertThat(findError(errors, "Loan Registration", 3, "REQUIRED").path("message").asText())
                .isEqualTo("Last Payment Date is required when Amount Paid to Date is greater than zero.");
        assertError(errors, "Loan Registration", 4, "Amount Paid to Date (RWF)", "INCONSISTENT_LOAN");
        assertError(errors, "Loan Registration", 5, "Username", "UNKNOWN_MEMBER");
        assertError(errors, "Loan Registration", 6, "Repayment Type", "INVALID_REPAYMENT_TYPE");
        assertError(errors, "Loan Registration", 7, "Loan Issued Date", "AFTER_CUTOVER");
        assertError(errors, "Loan Registration", 8, "Interest Rate (%)", "NEGATIVE");
        assertError(errors, "Loan Registration", 8, "Loan Term (Months)", "NOT_POSITIVE");
        assertError(errors, "Loan Registration", 9, "Amount Paid to Date (RWF)", "REQUIRED");
        assertError(errors, "Loan Registration", 9, "Outstanding Principal (RWF)", "REQUIRED");
        assertError(errors, "Loan Registration", 10, "Loan Amount (RWF)", "NOT_POSITIVE");
        assertError(errors, "Loan Registration", 11, "Last Payment Date", "AFTER_CUTOVER");
        assertError(errors, "Loan Registration", 12, "Loan Issued Date", "INVALID_DATE");
    }

    @Test
    void endOfTermLoan_needsOnlyCoreFields_andRejectsFullyRepaidLoans() throws Exception {
        String u = "eot_" + id8();
        registerMember(cooperativeId, u, "MEMBER");
        JsonNode ok = previewOk(MigrationWorkbooks.create()
                .loan(u, "end_of_term", "500000", "1.5", "2026-04-15", "6", "", "", "")
                .file("eot.xlsx"));
        assertThat(ok.path("status").asText()).isEqualTo("READY");
        assertThat(ok.path("migration").path("loans").path("totalOutstandingPrincipal").decimalValue())
                .isEqualByComparingTo("500000");
        JsonNode repaid = previewOk(MigrationWorkbooks.create()
                .loan(u, "MONTHLY", "100000", "2", "2026-05-01", "6", "112000", "0", "2026-09-01")
                .file("repaid.xlsx"));
        assertThat(repaid.path("status").asText()).isEqualTo("INVALID");
    }

    @Test
    void zeroPaidMonthlyLoan_withBlankLastPaymentDate_isAccepted() throws Exception {
        String u = "zp_" + id8();
        confirmOk(previewOk(MigrationWorkbooks.create().member("Zero Paid", u, "0").file("zp-open.xlsx")).path("importId").asText());
        JsonNode preview = previewOk(MigrationWorkbooks.create()
                .loan(u, "MONTHLY", "300000", "2", "2026-09-15", "5", "0", "300000", "")
                .file("zp.xlsx"));
        assertThat(preview.path("status").asText()).isEqualTo("READY");
        confirmOk(preview.path("importId").asText());
        Loan loan = loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent().get(0);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(loan.getTotalRepaidPrincipal()).isEqualByComparingTo("0");
        assertThat(loanInstallmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loan.getId())).hasSize(5);
    }

    // ------------------------------------------------------------------ workbook structure / legacy

    @Test
    void oldFormatWorkbook_isRejectedWithTheDownloadLatestTemplateMessage() throws Exception {
        mockMvc.perform(preview(MigrationWorkbooks.file("old.xlsx", MigrationWorkbooks.legacyWorkbook()), CUTOVER, superAdminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OLD_TEMPLATE_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.message").value(
                        "This historical import format is no longer supported. Download the latest migration template."));
    }

    @Test
    void missingSheetOrWrongHeader_isReportedAndBlocksConfirm() throws Exception {
        byte[] onlyOne;
        try (Workbook workbook = new XSSFWorkbook();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Member Registration");
            sheet.createRow(0).createCell(0).setCellValue("Wrong");
            workbook.write(out);
            onlyOne = out.toByteArray();
        }
        JsonNode preview = previewOk(MigrationWorkbooks.file("partial.xlsx", onlyOne));
        assertThat(preview.path("status").asText()).isEqualTo("INVALID");
        assertThat(preview.path("confirmAllowed").asBoolean()).isFalse();
        assertError(preview.path("errors"), "Share Capital Registration", null, "Sheet", "MISSING_SHEET");
        assertError(preview.path("errors"), "Member Registration", 1, "Header", "BAD_HEADER");
        mockMvc.perform(post(base(cooperativeId) + "/" + preview.path("importId").asText() + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void confirmedLegacyImport_remainsReadableInHistoryAndDetail() throws Exception {
        HistoricalImport legacy = historicalImportRepository.save(HistoricalImport.builder()
                .cooperativeId(cooperativeId)
                .originalFilename("legacy-2022.xlsx")
                .fileHash("legacyhash" + id8())
                .status(HistoricalImportStatus.CONFIRMED)
                .totalRows(120)
                .validRows(120)
                .invalidRows(0)
                .sheetSummary("[{\"sheet\":\"Members\",\"totalRows\":10,\"validRows\":10,\"invalidRows\":0}]")
                .reconciliationSummary(
                        "{\"projectedAvailableFund\":1000.00,\"blocked\":false,\"yearSummaries\":[{\"year\":2022,\"members\":10}]}")
                .build());
        historicalImportRowRepository.save(HistoricalImportRow.builder()
                .importId(legacy.getId())
                .sheet("Contributions")
                .rowNumber(2)
                .valid(true)
                .resultingEntityId(UUID.randomUUID())
                .resultingEntityType("Contributions")
                .build());

        mockMvc.perform(get(base(cooperativeId)).header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[0].format").value("LEGACY"))
                .andExpect(jsonPath("$.data[0].cutoverDate").doesNotExist());
        mockMvc.perform(get(base(cooperativeId) + "/" + legacy.getId())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.format").value("LEGACY"))
                .andExpect(jsonPath("$.data.totalRows").value(120))
                .andExpect(jsonPath("$.data.yearSummaries[0].year").value(2022))
                .andExpect(jsonPath("$.data.sheets[0].sheet").value("Members"));
        // confirmed history cannot be cancelled and cannot be re-confirmed in the new flow
        mockMvc.perform(post(base(cooperativeId) + "/" + legacy.getId() + "/cancel")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void cancel_worksBeforeConfirmOnly() throws Exception {
        JsonNode preview = previewOk(MigrationWorkbooks.create().member("Can Cel", "can_" + id8(), "5").file("cancel.xlsx"));
        String importId = preview.path("importId").asText();
        mockMvc.perform(post(base(cooperativeId) + "/" + importId + "/cancel")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(post(base(cooperativeId) + "/" + importId + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------ loans are a separate import

    @Test
    void aLoanSheetInsideTheOpeningWorkbookIsRefusedAndNothingCanBeConfirmed() throws Exception {
        String u = "mix_" + id8();
        JsonNode preview = previewOk(MigrationWorkbooks.create()
                .member("Mixed Up", u, "10000")
                .shares(u, "1", "1000")
                .loan(u, "END_OF_TERM", "100000", "2", "2026-05-01", "6", "", "", "")
                .file("mixed.xlsx"));
        assertThat(preview.path("status").asText()).isEqualTo("INVALID");
        assertThat(preview.path("confirmAllowed").asBoolean()).isFalse();
        assertError(preview.path("errors"), "Loan Registration", null, "Sheet", "LOAN_SHEET_NOT_ACCEPTED");
        mockMvc.perform(post(base(cooperativeId) + "/" + preview.path("importId").asText() + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().is4xxClientError());
        assertThat(openingBalanceRepository.findByCooperativeId(cooperativeId)).isEmpty();
        assertThat(loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent()).isEmpty();
    }

    @Test
    void loansAreAddedOnTopOfConfirmedOpeningBalances_neverBeforeThem() throws Exception {
        String u = "first_" + id8();
        registerMember(cooperativeId, u, "MEMBER");
        JsonNode loanPreview = previewOk(MigrationWorkbooks.create()
                .loan(u, "END_OF_TERM", "100000", "2", "2026-05-01", "6", "", "", "")
                .file("loans-first.xlsx"));
        assertThat(loanPreview.path("status").asText()).isEqualTo("READY");
        MvcResult early = mockMvc.perform(post(base(cooperativeId) + "/" + loanPreview.path("importId").asText() + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andReturn();
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(early.getResponse().getContentAsString()).contains("OPENING_BALANCES_FIRST");
        assertThat(loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent()).isEmpty();
        assertThat(historicalImportRepository.findById(UUID.fromString(loanPreview.path("importId").asText())).orElseThrow().getStatus())
                .as("a refused import stays as it was").isEqualTo(HistoricalImportStatus.READY);
    }

    @Test
    void theLoanWorkbookCarriesNoCashPositionAndNeedsNoneToPreview() throws Exception {
        String u = "nocash_" + id8();
        registerMember(cooperativeId, u, "MEMBER");
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(base(cooperativeId) + "/preview")
                        .file(MigrationWorkbooks.create().loan(u, "END_OF_TERM", "100000", "2", "2026-05-01", "6", "", "", "").file("nc.xlsx"))
                        .param("cutoverDate", CUTOVER)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("kind").asText()).isEqualTo("OPENING_LOANS");
        assertThat(data.path("migration").path("openingAvailableFunds").isNull()).isTrue();
        // the opening workbook still needs the cash position
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(base(cooperativeId) + "/preview")
                        .file(MigrationWorkbooks.create().member("Needs Cash", "nc_" + id8(), "1").file("nc2.xlsx"))
                        .param("cutoverDate", CUTOVER)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private MigrationWorkbooks fullWorkbook(String alice, String bob, String bobNationalId) {
        return MigrationWorkbooks.create()
                .member("Alice  Mukamana   Uwase", alice, alice + "@test.local", "0781234567", "", "850000")
                .member("Bob", bob, bob + "@test.local", "0788888888", bobNationalId, "0")
                .shares(alice, "8", "240000")
                .shares(bob, "0", "0");
    }

    /** The separate loan workbook for the members of {@link #fullWorkbook}. */
    private MigrationWorkbooks loanWorkbook(String alice, String bob) {
        return MigrationWorkbooks.create()
                .loan(alice, "MONTHLY", "1200000", "2", "2026-01-05", "12", "600000", "650000", "2026-09-05")
                .loan(bob, "END_OF_TERM", "100000", "3", "2026-06-30", "6", "", "", "");
    }

    private String base(UUID coopId) {
        return "/api/v1/cooperatives/" + coopId + "/historical-imports";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder preview(
            MockMultipartFile file, String cutover, String token) {
        return multipart(base(cooperativeId) + "/preview")
                .file(file)
                .param("cutoverDate", cutover)
                .param("openingAvailableFunds", FUNDS)
                .header("Authorization", "Bearer " + token);
    }

    private JsonNode previewOk(MockMultipartFile file) throws Exception {
        ResultActions actions = mockMvc.perform(preview(file, CUTOVER, superAdminToken));
        MvcResult result = actions.andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode confirmOk(UUID importId) throws Exception {
        return confirmOk(importId.toString());
    }

    private JsonNode confirmOk(String importId) throws Exception {
        MvcResult result = mockMvc.perform(post(base(cooperativeId) + "/" + importId + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static java.util.Optional<JsonNode> findOptional(JsonNode errors, String sheet, int row) {
        for (JsonNode e : errors) {
            if (sheet.equals(e.path("sheet").asText()) && row == e.path("rowNumber").asInt(-1)) {
                return java.util.Optional.of(e);
            }
        }
        return java.util.Optional.empty();
    }

    private static JsonNode findError(JsonNode errors, String sheet, int row, String code) {
        for (JsonNode e : errors) {
            if (sheet.equals(e.path("sheet").asText())
                    && row == e.path("rowNumber").asInt(-1)
                    && code.equals(e.path("code").asText())) {
                return e;
            }
        }
        throw new AssertionError("No " + code + " error at " + sheet + " row " + row + " in " + errors);
    }

    private static void assertError(JsonNode errors, String sheet, Integer row, String field, String code) {
        for (JsonNode e : errors) {
            boolean rowMatches = row == null ? e.path("rowNumber").isNull() : row == e.path("rowNumber").asInt(-1);
            if (sheet.equals(e.path("sheet").asText())
                    && rowMatches
                    && field.equals(e.path("field").asText())
                    && code.equals(e.path("code").asText())) {
                assertThat(e.path("message").asText()).isNotBlank();
                return;
            }
        }
        throw new AssertionError(
                "Expected error " + code + " on " + sheet + " row " + row + " field " + field + " but got " + errors);
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

    private JsonNode registerMember(UUID coopId, String username, String role) throws Exception {
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Reg",
                                  "lastName":"User",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s"
                                }
                                """.formatted(username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
    }

    private String registerAndLogin(UUID coopId, String role) throws Exception {
        String username = role.toLowerCase() + "_" + id8();
        JsonNode member = registerMember(coopId, username, role);
        return login(username, member.path("temporaryPassword").asText());
    }

    private String login(String username, String password) throws Exception {
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

    private static String id8() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
