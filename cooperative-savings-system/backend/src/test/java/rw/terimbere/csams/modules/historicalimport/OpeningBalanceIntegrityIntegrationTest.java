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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.modules.share.entity.SharePurchase;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;

/**
 * Cutover invariant: opening values are state THROUGH the cutover date, OuWealth transactions are activity AFTER
 * it, and no amount may sit on both sides. Also covers the explicit available-funds position.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpeningBalanceIntegrityIntegrationTest {

    private static final String CUTOVER = "2026-09-30";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CooperativeMembershipRepository membershipRepository;
    @Autowired private MemberOpeningBalanceRepository openingBalanceRepository;
    @Autowired private SharePurchaseRepository sharePurchaseRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private HistoricalImportRepository historicalImportRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String token;
    private UUID cooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readTree(login.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody("Integrity " + id8())))
                .andExpect(status().isOk())
                .andReturn();
        cooperativeId = UUID.fromString(
                objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    // ------------------------------------------------------------------ explicit available funds

    @Test
    void openingFunds_areRequiredNonNegativeNumeric() throws Exception {
        MockMultipartFile file = MigrationWorkbooks.create().member("A B", "fn_" + id8(), "1").file("f.xlsx");
        mockMvc.perform(multipart(base() + "/preview").file(file).param("cutoverDate", CUTOVER)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(previewRequest(file, "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(previewRequest(file, "not-a-number")).andExpect(status().isBadRequest());
        mockMvc.perform(previewRequest(file, "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.migration.openingAvailableFunds").value(0.0));
    }

    @Test
    void explicitCashIsPersisted_andIsNotDerivedFromContributionsCapitalOrLoans() throws Exception {
        String member = "cash_" + id8();
        JsonNode preview = preview(MigrationWorkbooks.create()
                .member("Cash Member", member, "850000")
                .shares(member, "8", "240000")
                .file("cash.xlsx"), "800000.50");
        assertThat(preview.path("migration").path("openingAvailableFunds").decimalValue()).isEqualByComparingTo("800000.50");
        UUID importId = UUID.fromString(preview.path("importId").asText());
        confirm(importId);
        confirmLoans(MigrationWorkbooks.create()
                .loan(member, "MONTHLY", "400000", "2", "2026-06-05", "10", "0", "400000", "")
                .file("cash-loans.xlsx"));

        assertThat(historicalImportRepository.findById(importId).orElseThrow().getOpeningAvailableFunds())
                .isEqualByComparingTo("800000.50");
        // contributions 850,000 + capital 240,000 - loan 400,000 would be 690,000: it must not be used
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableFunds").value(800000.5))
                .andExpect(jsonPath("$.data.outstandingLoans").value(400000.0));
        assertThat(historicalImportRepository.sumOpeningAvailableFunds(cooperativeId, HistoricalImportStatus.CONFIRMED))
                .isEqualByComparingTo("800000.50");
    }

    @Test
    void zeroCashWithLargeMemberBalances_staysZero() throws Exception {
        String member = "zero_" + id8();
        confirm(preview(MigrationWorkbooks.create()
                        .member("Zero Cash", member, "5000000")
                        .shares(member, "10", "1000000")
                        .file("zero.xlsx"), "0")
                .path("importId")
                .asText());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.availableGroupFunds").value(0.0))
                .andExpect(jsonPath("$.data.regularContributionsTotal").value(5000000.0));
    }

    @Test
    void importedLoanReceivableIsNotDeductedFromCashAndIsCountedOnceInValuation() throws Exception {
        String member = "val_" + id8();
        confirm(preview(MigrationWorkbooks.create()
                        .member("Val Member", member, "1000000")
                        .file("val.xlsx"), "800000")
                .path("importId")
                .asText());
        confirmLoans(MigrationWorkbooks.create()
                .loan(member, "END_OF_TERM", "400000", "0", "2026-07-30", "6", "", "", "")
                .file("val-loans.xlsx"));

        // available stays 800,000; the 400,000 receivable adds to total value exactly once
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.availableFunds").value(800000.0))
                .andExpect(jsonPath("$.data.outstandingLoans").value(400000.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(1200000.0));

        // member repays 100,000 principal in OuWealth: cash +100,000, receivable -100,000, value unchanged
        UUID loanId = loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent().get(0).getId();
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 100000, "paymentDate": "2026-10-12"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.availableFunds").value(900000.0))
                .andExpect(jsonPath("$.data.outstandingLoans").value(300000.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(1200000.0));
    }

    // ------------------------------------------------------------------ post-cutover contributions

    @Test
    void postCutoverContributionCombinesWithOpeningLifetimeTotal_andCash() throws Exception {
        String member = "live_" + id8();
        UUID memberId = register(member);
        contribute(memberId, 10, "2026-10-10", 20000);

        JsonNode preview = preview(MigrationWorkbooks.create()
                .member("Live Member", member, member + "@test.local", "0781234567", "", "850000")
                .file("live.xlsx"), "800000");
        assertThat(preview.path("status").asText()).isEqualTo("READY");
        confirm(UUID.fromString(preview.path("importId").asText()));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + memberId + "/financial-summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.openingContributions").value(850000.0))
                .andExpect(jsonPath("$.data.regularContributions").value(870000.0));
        // cash = explicit cutover cash + the post-cutover ledger credit, counted once
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.availableGroupFunds").value(820000.0))
                .andExpect(jsonPath("$.data.regularContributionsTotal").value(870000.0));
    }

    @Test
    void contributionsRecordedOnOrBeforeCutover_areRejected_atPreview_andAtConfirm() throws Exception {
        String early = "early_" + id8();
        UUID earlyId = register(early);
        contribute(earlyId, 9, "2026-09-15", 5000);
        JsonNode blocked = preview(MigrationWorkbooks.create()
                .member("Early Member", early, early + "@test.local", "0781234567", "", "850000")
                .file("early.xlsx"), "800000");
        assertThat(blocked.path("status").asText()).isEqualTo("INVALID");
        assertThat(blocked.path("errors").toString()).contains("CONTRIBUTIONS_BEFORE_CUTOVER");
        assertThat(blocked.path("errors").toString()).contains("LEDGER_ACTIVITY_BEFORE_CUTOVER");
    }

    @Test
    void confirmRechecksLiveState_afterAReadyPreview() throws Exception {
        String member = "recheck_" + id8();
        UUID memberId = register(member);
        JsonNode preview = preview(MigrationWorkbooks.create()
                .member("Re Check", member, member + "@test.local", "0781234567", "", "100000")
                .file("recheck.xlsx"), "800000");
        assertThat(preview.path("status").asText()).isEqualTo("READY");

        // live data dated before the cutover appears between preview and confirm
        contribute(memberId, 9, "2026-09-12", 7000);
        mockMvc.perform(post(base() + "/" + preview.path("importId").asText() + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
        assertThat(openingBalanceRepository.findByCooperativeId(cooperativeId)).isEmpty();
        assertThat(historicalImportRepository
                        .sumOpeningAvailableFunds(cooperativeId, HistoricalImportStatus.CONFIRMED)
                        .signum())
                .isZero();
    }

    // ------------------------------------------------------------------ share count semantics

    @Test
    void postCutoverApprovedSharePurchaseStaysOnTopOfTheAbsoluteOpeningCount() throws Exception {
        String member = "sh_" + id8();
        UUID memberId = register(member);
        approvedPurchase(memberId, 2, "2026-10-05T10:00:00Z", "20000");
        setShares(memberId, 2); // the live purchase already incremented the membership

        JsonNode preview = preview(MigrationWorkbooks.create()
                .member("Share Member", member, member + "@test.local", "0781234567", "", "0")
                .shares(member, "5", "50000")
                .file("shares-live.xlsx"), "800000");
        assertThat(preview.path("status").asText()).isEqualTo("READY");
        confirm(UUID.fromString(preview.path("importId").asText()));

        // absolute opening 5 + 2 bought after the cutover = 7 (not 5, not 2 + 5 counted twice)
        assertThat(membershipRepository.findByCooperativeIdAndUserId(cooperativeId, memberId).orElseThrow().getShareCount())
                .isEqualTo(7);
        assertThat(openingBalanceRepository.findByCooperativeIdAndMemberUserId(cooperativeId, memberId).orElseThrow().getOpeningShareCount())
                .isEqualTo(5);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + memberId + "/financial-summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.sharesHeld").value(7))
                .andExpect(jsonPath("$.data.openingShareCount").value(5))
                .andExpect(jsonPath("$.data.openingShareCapital").value(50000.0))
                .andExpect(jsonPath("$.data.contributedShareCapital").value(70000.0));
    }

    @Test
    void existingShareCountIsNeverBlindlyIncremented_unseparableSharesAreRejected() throws Exception {
        String member = "blind_" + id8();
        UUID memberId = register(member);
        setShares(memberId, 3); // 3 shares with no post-cutover purchase behind them: origin unknown

        JsonNode preview = preview(MigrationWorkbooks.create()
                .member("Blind Member", member, member + "@test.local", "0781234567", "", "0")
                .shares(member, "5", "50000")
                .file("blind.xlsx"), "800000");
        assertThat(preview.path("status").asText()).isEqualTo("INVALID");
        assertThat(preview.path("errors").toString()).contains("SHARES_NOT_SEPARABLE");
        assertThat(membershipRepository.findByCooperativeIdAndUserId(cooperativeId, memberId).orElseThrow().getShareCount())
                .isEqualTo(3);
    }

    @Test
    void sharePurchasesApprovedOnOrBeforeCutover_areRejected() throws Exception {
        String member = "pre_" + id8();
        UUID memberId = register(member);
        approvedPurchase(memberId, 1, "2026-09-20T10:00:00Z", "10000");
        setShares(memberId, 1);
        JsonNode preview = preview(MigrationWorkbooks.create()
                .member("Pre Member", member, member + "@test.local", "0781234567", "", "0")
                .shares(member, "5", "50000")
                .file("pre.xlsx"), "800000");
        assertThat(preview.path("status").asText()).isEqualTo("INVALID");
        assertThat(preview.path("errors").toString()).contains("SHARE_PURCHASES_BEFORE_CUTOVER");
    }

    // ------------------------------------------------------------------ duplicate loans / second migration

    @Test
    void anEquivalentLoanAlreadyInTheSystemIsNotCreatedAgain() throws Exception {
        String member = "dup_" + id8();
        UUID memberId = register(member);
        loanRepository.save(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .requestedAmount(new BigDecimal("100000.0000"))
                .approvedAmount(new BigDecimal("100000.0000"))
                .principalAmount(new BigDecimal("100000.0000"))
                .interestRatePercent(new BigDecimal("2.0000"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .requestDate(LocalDate.of(2026, 5, 1))
                .disbursementDate(LocalDate.of(2026, 5, 1))
                .status(LoanStatus.ACTIVE)
                .outstandingPrincipal(new BigDecimal("60000.0000"))
                .build());

        JsonNode preview = previewLoans(MigrationWorkbooks.create()
                .loan(member, "END_OF_TERM", "100000", "2", "2026-05-01", "6", "", "", "")
                .file("dup-loan.xlsx"));
        assertThat(preview.path("status").asText()).isEqualTo("INVALID");
        assertThat(preview.path("errors").toString()).contains("LOAN_ALREADY_MIGRATED");
        assertThat(loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent()).hasSize(1);
    }

    @Test
    void aSecondUploadCorrectsTheFirst_soTheCutoverCashIsCountedOnce() throws Exception {
        String first = confirm(preview(MigrationWorkbooks.create().member("First One", "one_" + id8(), "10").file("one.xlsx"), "800000")
                .path("importId")
                .asText()).path("importId").asText();
        JsonNode second = preview(MigrationWorkbooks.create().member("Second One", "two_" + id8(), "10").file("two.xlsx"), "500000");
        assertThat(second.path("status").asText()).as(second.path("errors").toString()).isEqualTo("READY");
        assertThat(second.path("revisionOfImportId").asText()).isEqualTo(first);
        // A correction needs the reason for it.
        mockMvc.perform(post(base() + "/" + second.path("importId").asText() + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
        assertThat(historicalImportRepository.sumOpeningAvailableFunds(cooperativeId, HistoricalImportStatus.CONFIRMED))
                .isEqualByComparingTo("800000");
        mockMvc.perform(post(base() + "/" + second.path("importId").asText() + "/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The bank balance and the member were wrong\"}"))
                .andExpect(status().isOk());
        // Only the corrected cash counts; the earlier upload stays on record, replaced.
        assertThat(historicalImportRepository.sumOpeningAvailableFunds(cooperativeId, HistoricalImportStatus.CONFIRMED))
                .isEqualByComparingTo("500000");
        assertThat(historicalImportRepository.findById(UUID.fromString(first)).orElseThrow().getStatus())
                .isEqualTo(HistoricalImportStatus.SUPERSEDED);
        assertThat(historicalImportRepository.findById(UUID.fromString(first)).orElseThrow().getSupersededByImportId())
                .isEqualTo(UUID.fromString(second.path("importId").asText()));
    }

    @Test
    void aLoanCorrectionKeepsUnchangedLoansWithTheirRecords_andRefusesToDropALoanThatHasRecords() throws Exception {
        String member = "rev_" + id8();
        confirm(preview(MigrationWorkbooks.create().member("Rev Member", member, "100").shares(member, "2", "20000").file("rev.xlsx"), "500000")
                .path("importId").asText());
        JsonNode first = confirmLoans(MigrationWorkbooks.create()
                .loan(member, "MONTHLY", "400000", "2", "2026-06-05", "10", "0", "400000", "")
                .loan(member, "END_OF_TERM", "100000", "2", "2026-07-01", "6", "0", "100000", "")
                .file("rev-loans.xlsx"));
        UUID firstImport = UUID.fromString(first.path("importId").asText());
        UUID kept = jdbc.queryForObject("select id from loans where opening_import_id = ? and principal_amount = 400000", UUID.class, firstImport);
        UUID changed = jdbc.queryForObject("select id from loans where opening_import_id = ? and principal_amount = 100000", UUID.class, firstImport);
        UUID memberId = jdbc.queryForObject("select id from users where username = ?", UUID.class, member);
        // Something recorded in the app on the first loan since the import.
        jdbc.update("insert into loan_guarantors (id, cooperative_id, loan_id, guarantor_user_id, guaranteed_amount, status, requested_by, requested_at)"
                + " values (?, ?, ?, ?, 1000, 'ACCEPTED', ?, now())", UUID.randomUUID(), cooperativeId, kept, memberId, memberId);

        JsonNode correction = previewLoans(MigrationWorkbooks.create()
                .loan(member, "MONTHLY", "400000", "2", "2026-06-05", "10", "0", "400000", "")
                .loan(member, "END_OF_TERM", "120000", "2", "2026-07-01", "6", "0", "120000", "")
                .file("rev-loans-2.xlsx"));
        assertThat(correction.path("status").asText()).as(correction.path("errors").toString()).isEqualTo("READY");
        assertThat(correction.path("unchangedLoans").asInt()).isEqualTo(1);
        assertThat(correction.path("replacedLoans").asInt()).isEqualTo(1);
        mockMvc.perform(post(base() + "/" + correction.path("importId").asText() + "/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The second loan was 120,000 not 100,000\"}"))
                .andExpect(status().isOk());
        UUID secondImport = UUID.fromString(correction.path("importId").asText());
        assertThat(jdbc.queryForObject("select opening_import_id from loans where id = ?", UUID.class, kept))
                .as("the unchanged loan stays, with what was recorded on it").isEqualTo(secondImport);
        assertThat(jdbc.queryForObject("select count(*) from loan_guarantors where loan_id = ?", Long.class, kept)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from loans where id = ?", Long.class, changed)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from loans where opening_import_id = ? and principal_amount = 120000",
                Long.class, secondImport)).isEqualTo(1L);
        assertThat(historicalImportRepository.findById(firstImport).orElseThrow().getStatus()).isEqualTo(HistoricalImportStatus.SUPERSEDED);

        // Dropping the loan that has records is refused, and nothing changes.
        MvcResult refused = mockMvc.perform(multipart(base() + "/preview")
                        .file(MigrationWorkbooks.create()
                                .loan(member, "END_OF_TERM", "120000", "2", "2026-07-01", "6", "0", "120000", "")
                                .file("rev-loans-3.xlsx"))
                        .param("cutoverDate", CUTOVER)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isBetween(400, 499);
        assertThat(refused.getResponse().getContentAsString()).contains("LOAN_HAS_ACTIVITY");
        assertThat(jdbc.queryForObject("select count(*) from loans where id = ?", Long.class, kept)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ helpers

    private String base() {
        return "/api/v1/cooperatives/" + cooperativeId + "/historical-imports";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder previewRequest(
            MockMultipartFile file, String funds) {
        return multipart(base() + "/preview")
                .file(file)
                .param("cutoverDate", CUTOVER)
                .param("openingAvailableFunds", funds)
                .header("Authorization", "Bearer " + token);
    }

    private JsonNode preview(MockMultipartFile file, String funds) throws Exception {
        MvcResult result = mockMvc.perform(previewRequest(file, funds)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** The loan workbook is separate and carries no cash position. */
    private JsonNode previewLoans(MockMultipartFile file) throws Exception {
        MvcResult result = mockMvc.perform(multipart(base() + "/preview")
                        .file(file)
                        .param("cutoverDate", CUTOVER)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode confirmLoans(MockMultipartFile file) throws Exception {
        JsonNode preview = previewLoans(file);
        assertThat(preview.path("status").asText()).as(preview.path("errors").toString()).isEqualTo("READY");
        return confirm(preview.path("importId").asText());
    }

    private JsonNode confirm(UUID importId) throws Exception {
        return confirm(importId.toString());
    }

    private JsonNode confirm(String importId) throws Exception {
        MvcResult result = mockMvc.perform(post(base() + "/" + importId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private UUID register(String username) throws Exception {
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Reg","lastName":"User","username":"%s","email":"%s@test.local","roleInCooperative":"MEMBER"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(register.getResponse().getContentAsString()).path("data").path("userId").asText());
    }

    private void contribute(UUID memberId, int month, String paymentDate, int amount) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + token)
                        .param("year", "2026")
                        .param("month", String.valueOf(month))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"memberUserId":"%s","paidAmount":%d,"paymentDate":"%s"}]}
                                """.formatted(memberId, amount, paymentDate)))
                .andExpect(status().isOk());
    }

    private void approvedPurchase(UUID memberId, int shares, String reviewedAt, String total) {
        sharePurchaseRepository.save(SharePurchase.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .numberOfShares(shares)
                .pricePerShare(new BigDecimal(total).divide(BigDecimal.valueOf(shares)))
                .totalAmount(new BigDecimal(total))
                .status(SharePurchaseStatus.APPROVED)
                .requestedBy(memberId)
                .requestedAt(Instant.parse(reviewedAt))
                .reviewedBy(memberId)
                .reviewedAt(Instant.parse(reviewedAt))
                .paymentDate(LocalDate.parse(reviewedAt.substring(0, 10)))
                .build());
    }

    private void setShares(UUID memberId, int count) {
        var membership = membershipRepository.findByCooperativeIdAndUserId(cooperativeId, memberId).orElseThrow();
        membership.setShareCount(count);
        membershipRepository.save(membership);
    }

    private static String id8() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
