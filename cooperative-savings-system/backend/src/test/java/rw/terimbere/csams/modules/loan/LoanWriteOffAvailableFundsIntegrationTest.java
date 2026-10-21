package rw.terimbere.csams.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImport;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.report.financial.LedgerPeriodAggregationService;
import rw.terimbere.csams.modules.report.financial.LedgerPeriodTotals;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * A loan write-off is an accounting status change, not a cash event: it must neither create nor remove available
 * cash. The unrecovered principal of a loan issued inside OuWealth keeps being subtracted (the money was lent out and
 * has not come back); imported opening-balance loans stay excluded because cutover cash is already net of them.
 *
 * <p>Scope is deliberately narrow: no loss accounting, no change to unpaid interest or penalty treatment, no change
 * to the share-pricing formula. Runs on H2 in PostgreSQL mode; PostgreSQL behavior is not verified here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanWriteOffAvailableFundsIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private LedgerFinancialCalculationService fundService;
    @Autowired private ShareValuationService shareValuationService;
    @Autowired private LedgerPeriodAggregationService aggregationService;
    @Autowired private HistoricalImportRepository historicalImportRepository;
    @Autowired private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID scheme;
    private UUID borrower;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        scheme = createScheme("WO Funds");
        borrower = registerMember(scheme, "MEMBER").id();
    }

    // ----------------------------------------------------------------- Test A

    @Test
    void writingOffAPartiallyRepaidLoanLeavesAvailableFundsUnchanged() throws Exception {
        // Issued 100,000, 40,000 principal repaid, 60,000 outstanding; funds 500,000 (560,000 credits - 60,000 lent).
        Loan loan = loan(scheme, borrower, "100000", "60000", "0", LoanStatus.ACTIVE, null);
        cashLedger(scheme, "560000");
        loanCashRows(loan, "100000", "40000");
        assertThat(funds(scheme)).isEqualByComparingTo("500000");
        List<LedgerEntry> ledgerBefore = ledgerRows(scheme);

        writeOff(scheme, loan.getId()).andExpect(status().isOk());

        assertThat(funds(scheme)).isEqualByComparingTo("500000");
        Loan after = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(LoanStatus.WRITTEN_OFF);
        assertThat(after.getOutstandingPrincipal()).isEqualByComparingTo("60000");
        assertThat(ledgerRows(scheme)).hasSameSizeAs(ledgerBefore);
    }

    // ----------------------------------------------------------------- Test B

    @Test
    void writingOffAFullyOutstandingLoanLeavesAvailableFundsUnchanged() throws Exception {
        Loan loan = loan(scheme, borrower, "100000", "100000", "0", LoanStatus.ACTIVE, null);
        cashLedger(scheme, "600000");
        assertThat(funds(scheme)).isEqualByComparingTo("500000");

        writeOff(scheme, loan.getId()).andExpect(status().isOk());

        assertThat(funds(scheme)).isEqualByComparingTo("500000");
        assertThat(loanRepository.findById(loan.getId()).orElseThrow().getOutstandingPrincipal())
                .isEqualByComparingTo("100000");
    }

    // ----------------------------------------------------------------- Test C

    @Test
    void activeOverdueAndWrittenOffLoansAreAllSubtracted() {
        UUID b2 = registerMemberQuietly();
        UUID b3 = registerMemberQuietly();
        loan(scheme, borrower, "30000", "30000", "0", LoanStatus.ACTIVE, null);
        loan(scheme, b2, "20000", "20000", "0", LoanStatus.OVERDUE, null);
        loan(scheme, b3, "10000", "10000", "0", LoanStatus.WRITTEN_OFF, null);
        cashLedger(scheme, "560000");

        // 560,000 credits - (30,000 + 20,000 + 10,000) outstanding principal
        assertThat(funds(scheme)).isEqualByComparingTo("500000");
    }

    // ----------------------------------------------------------------- Test D

    @Test
    void writingOffAnImportedOpeningBalanceLoanNeverChangesCash() throws Exception {
        HistoricalImport imported = historicalImportRepository.saveAndFlush(HistoricalImport.builder()
                .cooperativeId(scheme)
                .fileHash("wo-" + UUID.randomUUID().toString().replace("-", ""))
                .status(HistoricalImportStatus.CONFIRMED)
                .cutoverDate(LocalDate.now(KIGALI).minusMonths(3))
                .openingAvailableFunds(new BigDecimal("500000"))
                .build());
        Loan importedLoan = loan(scheme, borrower, "100000", "60000", "0", LoanStatus.ACTIVE, imported.getId());

        // Cutover cash is already net of the imported loan: its principal is never subtracted again.
        assertThat(funds(scheme)).isEqualByComparingTo("500000");

        writeOff(scheme, importedLoan.getId()).andExpect(status().isOk());

        assertThat(funds(scheme)).isEqualByComparingTo("500000");
        assertThat(loanRepository.findById(importedLoan.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.WRITTEN_OFF);
    }

    // ----------------------------------------------------------------- Test E

    @Test
    void aWriteOffInOneSchemeDoesNotMoveAnotherSchemesFunds() throws Exception {
        UUID other = createScheme("WO Other");
        UUID otherBorrower = registerMember(other, "MEMBER").id();
        loan(other, otherBorrower, "100000", "60000", "0", LoanStatus.ACTIVE, null);
        cashLedger(other, "560000");
        Loan loan = loan(scheme, borrower, "100000", "60000", "0", LoanStatus.ACTIVE, null);
        cashLedger(scheme, "560000");
        BigDecimal otherBefore = funds(other);
        assertThat(otherBefore).isEqualByComparingTo("500000");

        writeOff(scheme, loan.getId()).andExpect(status().isOk());

        assertThat(funds(other)).isEqualByComparingTo(otherBefore);
        assertThat(funds(scheme)).isEqualByComparingTo("500000");
        // A scheme cannot write off another scheme's loan either.
        Loan otherLoan = loanRepository.findAll().stream()
                .filter(l -> other.equals(l.getCooperativeId()))
                .findFirst()
                .orElseThrow();
        writeOff(scheme, otherLoan.getId()).andExpect(status().isNotFound());
        assertThat(loanRepository.findById(otherLoan.getId()).orElseThrow().getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    // ----------------------------------------------------------------- Test F

    @Test
    void shareValuationConsumesTheCorrectedFundsWithoutChangingItsFormula() throws Exception {
        OpeningShareBalances.set(membershipRepository, scheme, borrower, 100);
        Loan loan = loan(scheme, borrower, "100000", "60000", "4000", LoanStatus.ACTIVE, null);
        cashLedger(scheme, "560000");

        ShareValuationResponse before = shareValuationService.calculate(scheme);
        assertThat(before.getAvailableFunds()).isEqualByComparingTo("500000");
        assertThat(before.getOutstandingLoans()).isEqualByComparingTo("60000");
        assertThat(before.getUnpaidInterest()).isEqualByComparingTo("4000");
        assertThat(before.getTotalIkiminaValue()).isEqualByComparingTo("564000");
        assertThat(before.getCurrentShareValue()).isEqualByComparingTo("5640");

        writeOff(scheme, loan.getId()).andExpect(status().isOk());

        ShareValuationResponse after = shareValuationService.calculate(scheme);
        assertThat(after.getAvailableFunds()).isEqualByComparingTo("500000");
        // The written-off principal is no longer counted as a recoverable loan asset...
        assertThat(after.getOutstandingLoans()).isEqualByComparingTo("0");
        // ...and, under the unchanged existing formula, neither is its unpaid interest.
        assertThat(after.getUnpaidInterest()).isEqualByComparingTo("0");
        assertThat(after.getTotalIkiminaValue()).isEqualByComparingTo("500000");
        assertThat(after.getCurrentShareValue()).isEqualByComparingTo("5000");
        assertThat(after.getTotalExistingShares()).isEqualTo(100);
    }

    // ----------------------------------------------------------------- Test G

    @Test
    void aWriteOffCreatesNoCashLedgerMovement() throws Exception {
        Loan loan = loan(scheme, borrower, "100000", "60000", "0", LoanStatus.ACTIVE, null);
        cashLedger(scheme, "560000");
        loanCashRows(loan, "100000", "40000");
        Map<LedgerTransactionType, Long> before = countsByType(scheme);
        List<String> keysBefore = ledgerRows(scheme).stream().map(LedgerEntry::getIdempotencyKey).sorted().toList();

        writeOff(scheme, loan.getId()).andExpect(status().isOk());

        assertThat(countsByType(scheme)).isEqualTo(before);
        assertThat(ledgerRows(scheme).stream().map(LedgerEntry::getIdempotencyKey).sorted().toList())
                .isEqualTo(keysBefore);
        assertThat(ledgerRows(scheme).stream().map(LedgerEntry::getIdempotencyKey).distinct().count())
                .isEqualTo(ledgerRows(scheme).size());
    }

    // ----------------------------------------------------------------- Test H

    @Test
    void cashFlowReportingInventsNoMovementWhenALoanIsWrittenOff() throws Exception {
        Loan loan = loan(scheme, borrower, "100000", "60000", "0", LoanStatus.ACTIVE, null);
        cashLedger(scheme, "560000");
        loanCashRows(loan, "100000", "40000");
        LocalDate from = LocalDate.now(KIGALI).minusYears(1);
        LocalDate to = LocalDate.now(KIGALI).plusDays(1);
        LedgerPeriodTotals before = aggregationService.aggregateMainFund(scheme, from, to);

        writeOff(scheme, loan.getId()).andExpect(status().isOk());

        LedgerPeriodTotals after = aggregationService.aggregateMainFund(scheme, from, to);
        assertThat(after.getPeriodCredits()).isEqualByComparingTo(before.getPeriodCredits());
        assertThat(after.getPeriodDebits()).isEqualByComparingTo(before.getPeriodDebits());
        assertThat(after.getClosingLedgerBalance()).isEqualByComparingTo(before.getClosingLedgerBalance());
        assertThat(after.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("0");
    }

    // ----------------------------------------------------------------- Test I

    @Test
    void permissionsAndAllowedStatusesAreUnchanged() throws Exception {
        Actor accountant = registerMember(scheme, "ACCOUNTANT"); // LOAN_WRITE, but not FUND_AUTHORIZE
        Actor member = registerMember(scheme, "MEMBER");
        Loan active = loan(scheme, borrower, "100000", "60000", "0", LoanStatus.ACTIVE, null);

        writeOff(scheme, active.getId(), accountant.token()).andExpect(status().isForbidden());
        writeOff(scheme, active.getId(), member.token()).andExpect(status().isForbidden());
        assertThat(loanRepository.findById(active.getId()).orElseThrow().getStatus()).isEqualTo(LoanStatus.ACTIVE);

        writeOff(scheme, active.getId()).andExpect(status().isOk());
        // Already written off, closed and pending loans cannot be written off.
        writeOff(scheme, active.getId()).andExpect(status().isUnprocessableEntity());
        Loan closed = loan(scheme, registerMemberQuietly(), "100000", "0", "0", LoanStatus.CLOSED, null);
        Loan pending = loan(scheme, registerMemberQuietly(), "100000", "100000", "0", LoanStatus.PENDING, null);
        writeOff(scheme, closed.getId()).andExpect(status().isUnprocessableEntity());
        writeOff(scheme, pending.getId()).andExpect(status().isUnprocessableEntity());
    }

    // ----------------------------------------------------------------- Test J

    @Test
    void existingWrittenOffLoansAreSubtractedUnlessTheyWereImported() {
        // A loan already WRITTEN_OFF before this correction (persisted directly, as old data would be).
        loan(scheme, borrower, "100000", "60000", "0", LoanStatus.WRITTEN_OFF, null);
        cashLedger(scheme, "560000");
        assertThat(funds(scheme)).isEqualByComparingTo("500000");

        // An imported WRITTEN_OFF loan stays excluded: cutover cash is already net of it.
        UUID other = scheme;
        HistoricalImport imported = historicalImportRepository.saveAndFlush(HistoricalImport.builder()
                .cooperativeId(other)
                .fileHash("wo-j-" + UUID.randomUUID().toString().replace("-", ""))
                .status(HistoricalImportStatus.CANCELLED)
                .build());
        loan(scheme, registerMemberQuietly(), "100000", "80000", "0", LoanStatus.WRITTEN_OFF, imported.getId());
        assertThat(funds(scheme)).isEqualByComparingTo("500000");
    }

    // ---------------------------------------------------------------- helpers

    private record Actor(UUID id, String token) {}

    private BigDecimal funds(UUID s) {
        return fundService.calculateAvailableGroupFund(s);
    }

    private org.springframework.test.web.servlet.ResultActions writeOff(UUID s, UUID loanId) throws Exception {
        return writeOff(s, loanId, superAdminToken);
    }

    private org.springframework.test.web.servlet.ResultActions writeOff(UUID s, UUID loanId, String token)
            throws Exception {
        return mockMvc.perform(
                post("/api/v1/cooperatives/" + s + "/loans/" + loanId + "/write-off")
                        .header("Authorization", "Bearer " + token));
    }

    private List<LedgerEntry> ledgerRows(UUID s) {
        return ledgerEntryRepository.findAll().stream().filter(e -> s.equals(e.getCooperativeId())).toList();
    }

    private Map<LedgerTransactionType, Long> countsByType(UUID s) {
        Map<LedgerTransactionType, Long> counts = new EnumMap<>(LedgerTransactionType.class);
        ledgerRows(s).forEach(e -> counts.merge(e.getTransactionType(), 1L, Long::sum));
        return counts;
    }

    /** Cash received into the scheme (a credit type the fund formula counts). */
    private void cashLedger(UUID s, String amount) {
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(s)
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .creditAmount(new BigDecimal(amount))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now(KIGALI).minusMonths(2))
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("wo-cash-" + UUID.randomUUID())
                .description("seed")
                .build());
    }

    /** The disbursement debit and principal repayment credit a real loan would have left in the ledger. */
    private void loanCashRows(Loan loan, String disbursed, String principalRepaid) {
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(loan.getCooperativeId())
                .transactionType(LedgerTransactionType.LOAN_DISBURSEMENT)
                .debitAmount(new BigDecimal(disbursed))
                .creditAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now(KIGALI).minusMonths(1))
                .sourceEntityType("Loan")
                .sourceEntityId(loan.getId())
                .idempotencyKey("wo-disb-" + loan.getId())
                .description("disbursement")
                .build());
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(loan.getCooperativeId())
                .transactionType(LedgerTransactionType.LOAN_PRINCIPAL_REPAYMENT)
                .creditAmount(new BigDecimal(principalRepaid))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now(KIGALI).minusDays(10))
                .sourceEntityType("LoanRepayment")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("wo-repay-" + loan.getId())
                .description("repayment")
                .build());
    }

    private Loan loan(
            UUID s,
            UUID memberId,
            String principal,
            String outstandingPrincipal,
            String outstandingInterest,
            LoanStatus loanStatus,
            UUID openingImportId) {
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(s)
                .memberUserId(memberId)
                .requestedAmount(new BigDecimal(principal))
                .approvedAmount(new BigDecimal(principal))
                .principalAmount(new BigDecimal(principal))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(new BigDecimal(outstandingInterest))
                .outstandingPrincipal(new BigDecimal(outstandingPrincipal))
                .outstandingInterest(new BigDecimal(outstandingInterest))
                .requestDate(today.minusMonths(1))
                .disbursementDate(today.minusMonths(1))
                .dueDate(today.plusMonths(5))
                .status(loanStatus)
                .openingImportId(openingImportId)
                .build());
    }

    private UUID registerMemberQuietly() {
        try {
            return registerMember(scheme, "MEMBER").id();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID createScheme(String prefix) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                prefix + " " + UUID.randomUUID().toString().substring(0, 8), "10000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    private Actor registerMember(UUID s, String role) throws Exception {
        String username = "wo_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + s + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Wo","lastName":"Member","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return new Actor(UUID.fromString(data.path("userId").asText()), login(username, data.path("temporaryPassword").asText()));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
}
