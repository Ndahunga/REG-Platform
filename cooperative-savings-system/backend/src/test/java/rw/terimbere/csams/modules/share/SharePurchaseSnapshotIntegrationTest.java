package rw.terimbere.csams.modules.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.modules.report.financial.LedgerPeriodAggregationService;
import rw.terimbere.csams.modules.report.financial.LedgerPeriodTotals;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshot;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshotReason;
import rw.terimbere.csams.modules.share.repository.ShareValuationSnapshotRepository;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * The SHARE_ISSUANCE valuation snapshot written when a share purchase is approved must describe the complete
 * approved transaction (new shares AND the matching cash credit), so it agrees with the live valuation.
 *
 * <p>Scheme used throughout: available funds 500,000, loan assets 112,000 (principal 100,000 + unpaid interest
 * 12,000), total net assets 612,000, 100 shares, NAV 6,120. A purchase of 10 shares costs 61,200.
 *
 * <p>Runs on H2 in PostgreSQL mode; PostgreSQL transaction behavior is not verified here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SharePurchaseSnapshotIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CooperativeRepository cooperativeRepository;
    @Autowired private CooperativeMembershipRepository membershipRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private LoanRepository loanRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private MemberPaymentRepository memberPaymentRepository;
    @Autowired private PayoutRunRepository payoutRunRepository;
    @Autowired private ShareValuationSnapshotRepository snapshotRepository;
    @Autowired private ShareValuationService shareValuationService;
    @Autowired private LedgerPeriodAggregationService aggregationService;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID buyerId;
    private String buyerToken;
    private Loan loan;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        cooperativeId = createScheme("Snap A");
        Member buyer = registerMember(cooperativeId);
        buyerId = buyer.id();
        buyerToken = buyer.token();
        seedScheme(cooperativeId, buyerId);
        loan = loanRepository.findAll().stream()
                .filter(l -> cooperativeId.equals(l.getCooperativeId()))
                .findFirst()
                .orElseThrow();
    }

    // ----------------------------------------------------------------- Test A

    @Test
    void theIssuanceSnapshotDescribesTheCompleteApprovedPurchase() throws Exception {
        ShareValuationResponse before = shareValuationService.calculate(cooperativeId);
        assertThat(before.getAvailableFunds()).isEqualByComparingTo("500000");
        assertThat(before.getTotalIkiminaValue()).isEqualByComparingTo("612000");
        assertThat(before.getTotalExistingShares()).isEqualTo(100);
        assertThat(before.getCurrentShareValue()).isEqualByComparingTo("6120");

        UUID purchaseId = submit(buyerToken, 10);
        approve(superAdminToken, cooperativeId, purchaseId).andExpect(status().isOk());

        assertThat(shares(cooperativeId, buyerId)).isEqualTo(110);
        List<LedgerEntry> credits = shareCredits(purchaseId);
        assertThat(credits).hasSize(1);
        assertThat(credits.get(0).getCreditAmount()).isEqualByComparingTo("61200");

        ShareValuationSnapshot snapshot = issuanceSnapshot(purchaseId);
        assertThat(snapshot.getAvailableFunds()).isEqualByComparingTo("561200");
        assertThat(snapshot.getOutstandingLoans()).isEqualByComparingTo("100000");
        assertThat(snapshot.getUnpaidInterest()).isEqualByComparingTo("12000");
        assertThat(snapshot.getTotalIkiminaValue()).isEqualByComparingTo("673200");
        assertThat(snapshot.getTotalExistingShares()).isEqualTo(110);
        assertThat(snapshot.getCurrentShareValue()).isEqualByComparingTo("6120");

        // The live valuation agrees with the stored snapshot immediately after approval.
        ShareValuationResponse live = shareValuationService.calculate(cooperativeId);
        assertThat(live.getAvailableFunds()).isEqualByComparingTo(snapshot.getAvailableFunds());
        assertThat(live.getTotalIkiminaValue()).isEqualByComparingTo(snapshot.getTotalIkiminaValue());
        assertThat(live.getTotalExistingShares()).isEqualTo(snapshot.getTotalExistingShares());
        assertThat(live.getCurrentShareValue()).isEqualByComparingTo(snapshot.getCurrentShareValue());
    }

    // ----------------------------------------------------------------- Test B

    @Test
    void issuingSharesAtTheCorrectPriceDoesNotChangeTheValueOfExistingShares() throws Exception {
        BigDecimal navBefore = shareValuationService.calculate(cooperativeId).getCurrentShareValue();
        BigDecimal existingValueBefore = navBefore.multiply(BigDecimal.valueOf(100));

        UUID purchaseId = submit(buyerToken, 10);
        approve(superAdminToken, cooperativeId, purchaseId).andExpect(status().isOk());

        ShareValuationSnapshot issuance = issuanceSnapshot(purchaseId);
        assertThat(issuance.getCurrentShareValue()).isEqualByComparingTo(navBefore);
        // The 100 pre-existing shares are still worth exactly what they were worth before the issuance.
        assertThat(issuance.getCurrentShareValue().multiply(BigDecimal.valueOf(100)))
                .isEqualByComparingTo(existingValueBefore);
        assertThat(shareValuationService.calculate(cooperativeId).getCurrentShareValue()).isEqualByComparingTo(navBefore);
    }

    // ----------------------------------------------------------------- Test C

    @Test
    void theShareCreditIsRecordedExactlyOnce() throws Exception {
        UUID purchaseId = submit(buyerToken, 10);
        approve(superAdminToken, cooperativeId, purchaseId).andExpect(status().isOk());

        List<LedgerEntry> credits = shareCredits(purchaseId);
        assertThat(credits).hasSize(1);
        assertThat(credits.get(0).getStatus()).isEqualTo(LedgerEntryStatus.APPROVED);
        assertThat(credits.get(0).getDebitAmount()).isEqualByComparingTo("0");
        assertThat(allShareCreditsInScheme(cooperativeId)).hasSize(1);
    }

    // ----------------------------------------------------------------- Test D

    @Test
    void approvingAnAlreadyApprovedPurchaseAddsNothing() throws Exception {
        UUID purchaseId = submit(buyerToken, 10);
        approve(superAdminToken, cooperativeId, purchaseId).andExpect(status().isOk());
        long snapshots = issuanceSnapshots(purchaseId).size();

        approve(superAdminToken, cooperativeId, purchaseId).andExpect(status().isUnprocessableEntity());

        assertThat(shares(cooperativeId, buyerId)).isEqualTo(110);
        assertThat(shareCredits(purchaseId)).hasSize(1);
        assertThat(issuanceSnapshots(purchaseId)).hasSize(1);
        assertThat(issuanceSnapshots(purchaseId).size()).isEqualTo(snapshots);
    }

    // ----------------------------------------------------------------- Test F

    @Test
    void snapshotConsumersKeepWorkingWithTheCorrectedSnapshot() throws Exception {
        UUID purchaseId = submit(buyerToken, 10);
        approve(superAdminToken, cooperativeId, purchaseId).andExpect(status().isOk());

        // The purchase still exposes its PRICING snapshot (the pre-issuance valuation used to set the price).
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.content[0].pricePerShare").value(6120.0))
                .andExpect(jsonPath("$.data.content[0].totalAmount").value(61200.0))
                .andExpect(jsonPath("$.data.content[0].pricingValuation.totalIkiminaValue").value(612000.0))
                .andExpect(jsonPath("$.data.content[0].pricingValuation.totalExistingShares").value(100));

        // The issuance snapshot is distinct, readable, and converts to a valuation response.
        ShareValuationSnapshot issuance = issuanceSnapshot(purchaseId);
        ShareValuationResponse asResponse =
                shareValuationService.toResponse(issuance, cooperativeRepository.findById(cooperativeId).orElseThrow());
        assertThat(asResponse.getTotalIkiminaValue()).isEqualByComparingTo("673200");
        assertThat(asResponse.getCurrentShareValue()).isEqualByComparingTo("6120");
        assertThat(issuance.getReason()).isEqualTo(ShareValuationSnapshotReason.SHARE_ISSUANCE);
        assertThat(allSnapshots(purchaseId)).hasSize(2); // PURCHASE_PRICING + SHARE_ISSUANCE
    }

    // ----------------------------------------------------------------- Test G

    @Test
    void anApprovalNeverTouchesAnotherSchemeAndCannotBeDrivenThroughIt() throws Exception {
        UUID otherScheme = createScheme("Snap B");
        Member otherBuyer = registerMember(otherScheme);
        seedScheme(otherScheme, otherBuyer.id());
        BigDecimal otherNavBefore = shareValuationService.calculate(otherScheme).getCurrentShareValue();
        int otherShares = shares(otherScheme, otherBuyer.id());
        long otherCreditsBefore = allShareCreditsInScheme(otherScheme).size();
        long otherSnapshotsBefore = snapshotsOfScheme(otherScheme);

        UUID purchaseId = submit(buyerToken, 10);
        // Addressing scheme A's purchase through scheme B is "not found" and changes nothing.
        approve(superAdminToken, otherScheme, purchaseId).andExpect(status().isNotFound());
        assertThat(shares(cooperativeId, buyerId)).isEqualTo(100);
        assertThat(shareCredits(purchaseId)).isEmpty();

        approve(superAdminToken, cooperativeId, purchaseId).andExpect(status().isOk());

        assertThat(issuanceSnapshot(purchaseId).getCooperativeId()).isEqualTo(cooperativeId);
        assertThat(shareCredits(purchaseId).get(0).getCooperativeId()).isEqualTo(cooperativeId);
        assertThat(shares(otherScheme, otherBuyer.id())).isEqualTo(otherShares);
        assertThat(allShareCreditsInScheme(otherScheme)).hasSize((int) otherCreditsBefore);
        assertThat(snapshotsOfScheme(otherScheme)).isEqualTo(otherSnapshotsBefore);
        assertThat(shareValuationService.calculate(otherScheme).getCurrentShareValue()).isEqualByComparingTo(otherNavBefore);
    }

    // ----------------------------------------------------------------- Test H

    @Test
    void theCorrectionLeavesUnrelatedBalancesAndFormulasUntouched() throws Exception {
        long contributions = contributionRepository.count();
        long payments = memberPaymentRepository.count();
        long payouts = payoutRunRepository.count();
        ShareValuationResponse before = shareValuationService.calculate(cooperativeId);

        UUID purchaseId = submit(buyerToken, 10);
        approve(superAdminToken, cooperativeId, purchaseId).andExpect(status().isOk());

        Loan after = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(after.getOutstandingPrincipal()).isEqualByComparingTo("100000");
        assertThat(after.getOutstandingInterest()).isEqualByComparingTo("12000");
        assertThat(after.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(contributionRepository.count()).isEqualTo(contributions);
        assertThat(memberPaymentRepository.count()).isEqualTo(payments);
        assertThat(payoutRunRepository.count()).isEqualTo(payouts);

        // Formula inputs other than the issued shares and the new cash are unchanged.
        ShareValuationResponse live = shareValuationService.calculate(cooperativeId);
        assertThat(live.getOutstandingLoans()).isEqualByComparingTo(before.getOutstandingLoans());
        assertThat(live.getUnpaidInterest()).isEqualByComparingTo(before.getUnpaidInterest());
        assertThat(live.getUnpaidPenalties()).isEqualByComparingTo(before.getUnpaidPenalties());
        assertThat(live.getOtherAssets()).isEqualByComparingTo(before.getOtherAssets());
        assertThat(live.getAvailableFunds()).isEqualByComparingTo(before.getAvailableFunds().add(new BigDecimal("61200")));

        // Period-accurate reversal reporting is unaffected by the new credit.
        LocalDate today = LocalDate.now();
        LedgerPeriodTotals totals = aggregationService.aggregateMainFund(cooperativeId, today.minusYears(1), today.plusDays(1));
        assertThat(totals.credit(LedgerTransactionType.SHARE_PURCHASE)).isEqualByComparingTo("61200");
        assertThat(totals.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- helpers

    private record Member(UUID id, String token) {}

    private UUID submit(String token, int shares) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numberOfShares":%d,"paymentDate":"2026-01-15","paymentReference":"MOMO-%s",
                                 "evidenceFileKey":"share-evidence/receipt.pdf"}
                                """.formatted(shares, UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pricePerShare").value(6120.0))
                .andExpect(jsonPath("$.data.totalAmount").value(61200.0))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions approve(String token, UUID scheme, UUID purchaseId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/shares/purchases/" + purchaseId + "/approve")
                .header("Authorization", "Bearer " + token));
    }

    private int shares(UUID scheme, UUID memberId) {
        return membershipRepository.findByCooperativeIdAndUserId(scheme, memberId).orElseThrow().getShareCount();
    }

    private List<LedgerEntry> shareCredits(UUID purchaseId) {
        return ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getTransactionType() == LedgerTransactionType.SHARE_PURCHASE
                        && purchaseId.equals(e.getSourceEntityId()))
                .toList();
    }

    private List<LedgerEntry> allShareCreditsInScheme(UUID scheme) {
        return ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getTransactionType() == LedgerTransactionType.SHARE_PURCHASE
                        && scheme.equals(e.getCooperativeId()))
                .toList();
    }

    private List<ShareValuationSnapshot> allSnapshots(UUID purchaseId) {
        return snapshotRepository.findAll().stream()
                .filter(s -> purchaseId.equals(s.getSourceEntityId()))
                .toList();
    }

    private List<ShareValuationSnapshot> issuanceSnapshots(UUID purchaseId) {
        return allSnapshots(purchaseId).stream()
                .filter(s -> s.getReason() == ShareValuationSnapshotReason.SHARE_ISSUANCE)
                .toList();
    }

    private ShareValuationSnapshot issuanceSnapshot(UUID purchaseId) {
        List<ShareValuationSnapshot> found = issuanceSnapshots(purchaseId);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private long snapshotsOfScheme(UUID scheme) {
        return snapshotRepository.findAll().stream().filter(s -> scheme.equals(s.getCooperativeId())).count();
    }

    /** Funds 500,000, loan 100,000 principal + 12,000 unpaid interest, 100 shares held by {@code holder}. */
    private void seedScheme(UUID scheme, UUID holder) {
        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(scheme)
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .creditAmount(new BigDecimal("600000"))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now(KIGALI).minusMonths(2))
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("snap-seed-" + UUID.randomUUID())
                .description("seed")
                .build());
        LocalDate today = LocalDate.now(KIGALI);
        loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(scheme)
                .memberUserId(holder)
                .requestedAmount(new BigDecimal("100000"))
                .approvedAmount(new BigDecimal("100000"))
                .principalAmount(new BigDecimal("100000"))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(new BigDecimal("12000"))
                .outstandingPrincipal(new BigDecimal("100000"))
                .outstandingInterest(new BigDecimal("12000"))
                .requestDate(today.minusMonths(1))
                .disbursementDate(today.minusMonths(1))
                .dueDate(today.plusMonths(5))
                .status(LoanStatus.ACTIVE)
                .build());
        OpeningShareBalances.set(membershipRepository, scheme, holder, 100);
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

    private Member registerMember(UUID scheme) throws Exception {
        String username = "snap_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Snap","lastName":"Buyer","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"MEMBER"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return new Member(
                UUID.fromString(data.path("userId").asText()), login(username, data.path("temporaryPassword").asText()));
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
