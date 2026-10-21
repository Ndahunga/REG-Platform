package rw.terimbere.csams.modules.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;
import rw.terimbere.csams.modules.fine.entity.FinePayment;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.entity.FineType;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
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
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * Unpaid penalties on a WRITTEN_OFF loan are not a recoverable asset: share valuation must leave them out, exactly as
 * it already leaves out the loan's principal and unpaid interest. This is a valuation-eligibility change only: no fine
 * is waived, cancelled or edited, no loan balance moves and no ledger entry is created.
 *
 * <p>Loans and fines are persisted directly (as the existing write-off tests do) because the scenarios need exact
 * balances; the write-off itself always goes through the real endpoint. Runs on H2 in PostgreSQL mode; PostgreSQL
 * behavior is not verified here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShareValuationWrittenOffPenaltyIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final EnumSet<FineStatus> NAV_STATUSES = EnumSet.of(FineStatus.UNPAID, FineStatus.PARTIALLY_PAID);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanRepository loanRepository;
    @Autowired private FineRepository fineRepository;
    @Autowired private FinePaymentRepository finePaymentRepository;
    @Autowired private LoanRepaymentRepository loanRepaymentRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private LedgerFinancialCalculationService fundService;
    @Autowired private ShareValuationService shareValuationService;
    @Autowired private HistoricalImportRepository historicalImportRepository;
    @Autowired private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID scheme;
    private UUID borrower;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        scheme = createScheme("NAV Pen");
        borrower = registerMember(scheme, "MEMBER").id();
        OpeningShareBalances.set(membershipRepository, scheme, borrower, 100);
    }

    // ----------------------------------------------------------------- Test A

    @Test
    void anUnpaidLoanPenaltyLeavesNavWhenItsLoanIsWrittenOff() throws Exception {
        Loan loan = loan(scheme, borrower, "100000", "60000", "4000", "2000", LoanStatus.ACTIVE, null);
        Fine fine = fine(scheme, borrower, loan.getId(), "2000", "0", FineStatus.UNPAID);
        cashLedger(scheme, "560000");
        List<LedgerEntry> ledgerBefore = ledgerRows(scheme);

        ShareValuationResponse before = shareValuationService.calculate(scheme);
        assertThat(before.getAvailableFunds()).isEqualByComparingTo("500000");
        assertThat(before.getUnpaidPenalties()).isEqualByComparingTo("2000");
        assertThat(before.getTotalIkiminaValue()).isEqualByComparingTo("566000");
        assertThat(before.getCurrentShareValue()).isEqualByComparingTo("5660");

        writeOff(scheme, loan.getId(), superAdminToken).andExpect(status().isOk());

        ShareValuationResponse after = shareValuationService.calculate(scheme);
        assertThat(after.getAvailableFunds()).isEqualByComparingTo("500000");
        assertThat(after.getOutstandingLoans()).isEqualByComparingTo("0");
        assertThat(after.getUnpaidInterest()).isEqualByComparingTo("0");
        assertThat(after.getUnpaidPenalties()).isEqualByComparingTo("0");
        assertThat(after.getTotalIkiminaValue()).isEqualByComparingTo("500000");
        assertThat(after.getCurrentShareValue()).isEqualByComparingTo("5000");

        Fine fineAfter = fineRepository.findById(fine.getId()).orElseThrow();
        assertThat(fineAfter.getStatus()).isEqualTo(FineStatus.UNPAID);
        assertThat(fineAfter.getOutstandingAmount()).isEqualByComparingTo("2000");
        assertThat(fineAfter.getTotalAmount()).isEqualByComparingTo("2000");
        assertThat(fineAfter.getVersion()).isEqualTo(fine.getVersion());
        assertThat(ledgerRows(scheme)).hasSameSizeAs(ledgerBefore);
    }

    // ----------------------------------------------------------------- Test B

    @Test
    void aPartiallyPaidLoanFineCountsItsOutstandingPartOnlyAndOnlyBeforeWriteOff() throws Exception {
        Loan loan = loan(scheme, borrower, "100000", "60000", "0", "3000", LoanStatus.ACTIVE, null);
        Fine fine = fine(scheme, borrower, loan.getId(), "3000", "2000", FineStatus.PARTIALLY_PAID); // total 5,000
        FinePayment payment = finePaymentRepository.saveAndFlush(FinePayment.builder()
                .fineId(fine.getId())
                .cooperativeId(scheme)
                .memberUserId(borrower)
                .amount(new BigDecimal("2000"))
                .paymentDate(LocalDate.now(KIGALI).minusDays(5))
                .paymentReference("FP-1")
                .status(FinePaymentStatus.APPROVED)
                .build());
        cashLedger(scheme, "560000");
        // The 2,000 already collected sits in the cash ledger as a fine-payment credit.
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(scheme)
                .transactionType(LedgerTransactionType.FINE_PAYMENT)
                .creditAmount(new BigDecimal("2000"))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now(KIGALI).minusDays(5))
                .sourceEntityType("FinePayment")
                .sourceEntityId(payment.getId())
                .idempotencyKey("nav-fp-" + payment.getId())
                .description("fine payment")
                .build());
        BigDecimal fundsBefore = funds(scheme);
        assertThat(fundsBefore).isEqualByComparingTo("502000");

        assertThat(shareValuationService.calculate(scheme).getUnpaidPenalties()).isEqualByComparingTo("3000");

        writeOff(scheme, loan.getId(), superAdminToken).andExpect(status().isOk());

        ShareValuationResponse after = shareValuationService.calculate(scheme);
        assertThat(after.getUnpaidPenalties()).isEqualByComparingTo("0");
        assertThat(after.getAvailableFunds()).isEqualByComparingTo(fundsBefore);
        Fine fineAfter = fineRepository.findById(fine.getId()).orElseThrow();
        assertThat(fineAfter.getStatus()).isEqualTo(FineStatus.PARTIALLY_PAID);
        assertThat(fineAfter.getPaidAmount()).isEqualByComparingTo("2000");
        assertThat(fineAfter.getOutstandingAmount()).isEqualByComparingTo("3000");
        List<FinePayment> payments = finePaymentRepository.findAll().stream()
                .filter(p -> fine.getId().equals(p.getFineId()))
                .toList();
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getAmount()).isEqualByComparingTo("2000");
        assertThat(payments.get(0).getStatus()).isEqualTo(FinePaymentStatus.APPROVED);
    }

    // ----------------------------------------------------------------- Test C

    @Test
    void ordinaryAndManualFinesStillCountEvenNextToAWrittenOffLoan() throws Exception {
        Loan loan = loan(scheme, borrower, "100000", "60000", "0", "2000", LoanStatus.ACTIVE, null);
        fine(scheme, borrower, loan.getId(), "2000", "0", FineStatus.UNPAID);
        fine(scheme, borrower, null, "1500", "0", FineStatus.UNPAID); // not linked to any loan
        Fine partiallyPaidManual = fine(scheme, borrower, null, "700", "0", FineStatus.PARTIALLY_PAID);
        assertThat(partiallyPaidManual.getSourceLoanId()).isNull();
        cashLedger(scheme, "560000");

        assertThat(shareValuationService.calculate(scheme).getUnpaidPenalties()).isEqualByComparingTo("4200");

        writeOff(scheme, loan.getId(), superAdminToken).andExpect(status().isOk());

        assertThat(shareValuationService.calculate(scheme).getUnpaidPenalties()).isEqualByComparingTo("2200");
    }

    // ----------------------------------------------------------------- Tests D and E

    @Test
    void penaltiesOnActiveAndOverdueLoansStayInNav() {
        Loan active = loan(scheme, borrower, "100000", "60000", "0", "2000", LoanStatus.ACTIVE, null);
        Loan overdue = loan(scheme, registerMemberQuietly(), "50000", "30000", "0", "1000", LoanStatus.OVERDUE, null);
        Loan writtenOff = loan(scheme, registerMemberQuietly(), "40000", "40000", "0", "500", LoanStatus.WRITTEN_OFF, null);
        fine(scheme, borrower, active.getId(), "2000", "0", FineStatus.UNPAID);
        fine(scheme, borrower, overdue.getId(), "1000", "0", FineStatus.UNPAID);
        fine(scheme, borrower, writtenOff.getId(), "500", "0", FineStatus.UNPAID);

        assertThat(shareValuationService.calculate(scheme).getUnpaidPenalties()).isEqualByComparingTo("3000");
    }

    // ----------------------------------------------------------------- Test F

    @Test
    void aWriteOffInOneSchemeNeverMovesAnotherSchemesNav() throws Exception {
        UUID other = createScheme("NAV Other");
        UUID otherBorrower = registerMember(other, "MEMBER").id();
        OpeningShareBalances.set(membershipRepository, other, otherBorrower, 100);
        Loan otherLoan = loan(other, otherBorrower, "100000", "60000", "4000", "2000", LoanStatus.ACTIVE, null);
        fine(other, otherBorrower, otherLoan.getId(), "2000", "0", FineStatus.UNPAID);
        cashLedger(other, "560000");
        Loan loan = loan(scheme, borrower, "100000", "60000", "4000", "2000", LoanStatus.ACTIVE, null);
        fine(scheme, borrower, loan.getId(), "2000", "0", FineStatus.UNPAID);
        cashLedger(scheme, "560000");
        ShareValuationResponse otherBefore = shareValuationService.calculate(other);
        assertThat(otherBefore.getCurrentShareValue()).isEqualByComparingTo("5660");

        writeOff(scheme, loan.getId(), superAdminToken).andExpect(status().isOk());

        ShareValuationResponse otherAfter = shareValuationService.calculate(other);
        assertThat(otherAfter.getUnpaidPenalties()).isEqualByComparingTo("2000");
        assertThat(otherAfter.getTotalIkiminaValue()).isEqualByComparingTo(otherBefore.getTotalIkiminaValue());
        assertThat(otherAfter.getCurrentShareValue()).isEqualByComparingTo("5660");
        assertThat(shareValuationService.calculate(scheme).getCurrentShareValue()).isEqualByComparingTo("5000");
    }

    @Test
    void aFineNeverDisappearsBecauseOfALoanInAnotherScheme() throws Exception {
        UUID other = createScheme("NAV Foreign");
        UUID otherBorrower = registerMember(other, "MEMBER").id();
        Loan foreignWrittenOff =
                loan(other, otherBorrower, "100000", "60000", "0", "0", LoanStatus.WRITTEN_OFF, null);
        // Inconsistent data the application never creates: a fine of this scheme pointing at another scheme's loan.
        fine(scheme, borrower, foreignWrittenOff.getId(), "900", "0", FineStatus.UNPAID);

        // The loan match is scheme-scoped, so the foreign loan cannot hide this scheme's fine: it keeps counting.
        assertThat(fineRepository.sumOutstandingForValuationExcludingWrittenOffLoans(scheme, NAV_STATUSES))
                .isEqualByComparingTo("900");
        assertThat(fineRepository.sumOutstandingForValuationExcludingWrittenOffLoans(other, NAV_STATUSES))
                .isEqualByComparingTo("0");
    }

    // ----------------------------------------------------------------- Test G

    @Test
    void aLoanAlreadyWrittenOffBeforeThisCorrectionIsExcludedWithoutAnyDataUpdate() {
        Loan historical = loan(scheme, borrower, "100000", "60000", "4000", "2000", LoanStatus.WRITTEN_OFF, null);
        fine(scheme, borrower, historical.getId(), "2000", "0", FineStatus.UNPAID);
        cashLedger(scheme, "560000");

        ShareValuationResponse valuation = shareValuationService.calculate(scheme);

        assertThat(valuation.getUnpaidPenalties()).isEqualByComparingTo("0");
        assertThat(valuation.getTotalIkiminaValue()).isEqualByComparingTo("500000");
        assertThat(valuation.getCurrentShareValue()).isEqualByComparingTo("5000");
    }

    // ----------------------------------------------------------------- Test H

    @Test
    void anImportedWrittenOffLoanPenaltyIsExcludedAndFundsStillIgnoreImportedPrincipal() {
        HistoricalImport imported = historicalImportRepository.saveAndFlush(HistoricalImport.builder()
                .cooperativeId(scheme)
                .fileHash("nav-" + UUID.randomUUID().toString().replace("-", ""))
                .status(HistoricalImportStatus.CONFIRMED)
                .cutoverDate(LocalDate.now(KIGALI).minusMonths(3))
                .openingAvailableFunds(new BigDecimal("500000"))
                .build());
        Loan importedLoan =
                loan(scheme, borrower, "100000", "60000", "0", "2000", LoanStatus.WRITTEN_OFF, imported.getId());
        fine(scheme, borrower, importedLoan.getId(), "2000", "0", FineStatus.UNPAID);

        ShareValuationResponse valuation = shareValuationService.calculate(scheme);

        assertThat(valuation.getUnpaidPenalties()).isEqualByComparingTo("0");
        // Task 2E is untouched: imported principal is not subtracted from cutover cash.
        assertThat(valuation.getAvailableFunds()).isEqualByComparingTo("500000");
        assertThat(valuation.getCurrentShareValue()).isEqualByComparingTo("5000");
    }

    // ----------------------------------------------------------------- Test I

    @Test
    void theCorrectionPostsNothingAndChangesNoBalanceOrPermission() throws Exception {
        Loan loan = loan(scheme, borrower, "100000", "60000", "4000", "2000", LoanStatus.ACTIVE, null);
        Fine fine = fine(scheme, borrower, loan.getId(), "2000", "0", FineStatus.UNPAID);
        cashLedger(scheme, "560000");
        long repaymentsBefore = loanRepaymentRepository.count();
        long finesBefore = fineRepository.count();
        long paymentsBefore = finePaymentRepository.count();

        // Valuing alone changes nothing.
        List<LedgerEntry> ledgerBefore = ledgerRows(scheme);
        shareValuationService.calculate(scheme);
        assertThat(ledgerRows(scheme)).hasSameSizeAs(ledgerBefore);

        Actor accountant = registerMember(scheme, "ACCOUNTANT"); // LOAN_WRITE, but not FUND_AUTHORIZE
        writeOff(scheme, loan.getId(), accountant.token()).andExpect(status().isForbidden());
        writeOff(scheme, loan.getId(), superAdminToken).andExpect(status().isOk());
        writeOff(scheme, loan.getId(), superAdminToken).andExpect(status().isUnprocessableEntity());

        assertThat(ledgerRows(scheme)).hasSameSizeAs(ledgerBefore);
        Loan loanAfter = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(loanAfter.getStatus()).isEqualTo(LoanStatus.WRITTEN_OFF);
        assertThat(loanAfter.getOutstandingPrincipal()).isEqualByComparingTo("60000");
        assertThat(loanAfter.getOutstandingInterest()).isEqualByComparingTo("4000");
        assertThat(loanAfter.getOutstandingPenalty()).isEqualByComparingTo("2000");
        assertThat(loanAfter.getTotalRepaidPenalty()).isEqualByComparingTo("0");
        assertThat(fineRepository.findById(fine.getId()).orElseThrow().getOutstandingAmount())
                .isEqualByComparingTo("2000");
        assertThat(loanRepaymentRepository.count()).isEqualTo(repaymentsBefore);
        assertThat(fineRepository.count()).isEqualTo(finesBefore);
        assertThat(finePaymentRepository.count()).isEqualTo(paymentsBefore);
    }

    // ----------------------------------------------------------------- Test J

    @Test
    void theGeneralFineAggregationKeepsItsOriginalBehaviour() {
        Loan writtenOff = loan(scheme, borrower, "100000", "60000", "0", "2000", LoanStatus.WRITTEN_OFF, null);
        fine(scheme, borrower, writtenOff.getId(), "2000", "0", FineStatus.UNPAID);
        fine(scheme, borrower, null, "1500", "0", FineStatus.UNPAID);

        // The original method still counts every unpaid fine of the scheme, written-off loan or not.
        assertThat(fineRepository.sumOutstandingByStatuses(scheme, NAV_STATUSES)).isEqualByComparingTo("3500");
        // The valuation-specific method leaves the written-off loan's fine out.
        assertThat(fineRepository.sumOutstandingForValuationExcludingWrittenOffLoans(scheme, NAV_STATUSES))
                .isEqualByComparingTo("1500");
        // Paid, waived and cancelled fines stay out of both, as before.
        fine(scheme, borrower, null, "0", "800", FineStatus.PAID);
        fine(scheme, borrower, null, "400", "0", FineStatus.WAIVED);
        fine(scheme, borrower, null, "300", "0", FineStatus.CANCELLED);
        assertThat(fineRepository.sumOutstandingForValuationExcludingWrittenOffLoans(scheme, NAV_STATUSES))
                .isEqualByComparingTo("1500");
        // No eligible fines -> zero, not null.
        assertThat(fineRepository.sumOutstandingForValuationExcludingWrittenOffLoans(UUID.randomUUID(), NAV_STATUSES))
                .isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- helpers

    private record Actor(UUID id, String token) {}

    private BigDecimal funds(UUID s) {
        return fundService.calculateAvailableGroupFund(s);
    }

    private ResultActions writeOff(UUID s, UUID loanId, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/cooperatives/" + s + "/loans/" + loanId + "/write-off")
                .header("Authorization", "Bearer " + token));
    }

    private List<LedgerEntry> ledgerRows(UUID s) {
        return ledgerEntryRepository.findAll().stream().filter(e -> s.equals(e.getCooperativeId())).toList();
    }

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
                .idempotencyKey("nav-cash-" + UUID.randomUUID())
                .description("seed")
                .build());
    }

    private Fine fine(UUID s, UUID memberId, UUID sourceLoanId, String outstanding, String paid, FineStatus fineStatus) {
        BigDecimal total = new BigDecimal(outstanding).add(new BigDecimal(paid));
        return fineRepository.saveAndFlush(Fine.builder()
                .cooperativeId(s)
                .memberUserId(memberId)
                .fineType(sourceLoanId == null ? FineType.MANUAL : FineType.AUTOMATIC)
                .calculationMode(FineCalculationMode.FIXED)
                .sourceLoanId(sourceLoanId)
                .baseAmount(total)
                .totalAmount(total)
                .paidAmount(new BigDecimal(paid))
                .outstandingAmount(new BigDecimal(outstanding))
                .issuedDate(LocalDate.now(KIGALI).minusDays(20))
                .status(fineStatus)
                .build());
    }

    private Loan loan(
            UUID s,
            UUID memberId,
            String principal,
            String outstandingPrincipal,
            String outstandingInterest,
            String outstandingPenalty,
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
                .outstandingPenalty(new BigDecimal(outstandingPenalty))
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
        return UUID.fromString(
                objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    private Actor registerMember(UUID s, String role) throws Exception {
        String username = "nv_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + s + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Nv","lastName":"Member","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"%s"}
                                """.formatted(username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return new Actor(
                UUID.fromString(data.path("userId").asText()),
                login(username, data.path("temporaryPassword").asText()));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }
}
