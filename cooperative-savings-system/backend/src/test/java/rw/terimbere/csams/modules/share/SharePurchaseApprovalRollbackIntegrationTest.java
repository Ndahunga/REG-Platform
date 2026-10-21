package rw.terimbere.csams.modules.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshot;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshotReason;
import rw.terimbere.csams.modules.share.entity.SharePurchase;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;
import rw.terimbere.csams.modules.share.repository.ShareValuationSnapshotRepository;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * Approval is one transaction: if the ledger credit or the issuance snapshot cannot be written, the purchase status,
 * the member's share count, the ledger credit and the snapshot must all roll back together.
 *
 * <p>Failures are injected by spying on existing collaborators (no production-only hooks). The spied calls are
 * matched narrowly so that setup and the pricing snapshot at submission behave normally.
 *
 * <p>Runs on H2 in PostgreSQL mode; PostgreSQL transaction behavior is not verified here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SharePurchaseApprovalRollbackIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CooperativeMembershipRepository membershipRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private SharePurchaseRepository sharePurchaseRepository;
    @Autowired private ShareValuationSnapshotRepository snapshotRepository;

    @SpyBean private ShareValuationService shareValuationService;
    @SpyBean private LedgerService ledgerService;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID buyerId;
    private String buyerToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login("superadmin", "ChangeMe@123!");
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "Snap Rollback " + UUID.randomUUID().toString().substring(0, 8), "10000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        cooperativeId = UUID.fromString(objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());

        String username = "snaprb_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Snap","lastName":"Rollback","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"MEMBER"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode member = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        buyerId = UUID.fromString(member.path("userId").asText());
        buyerToken = login(username, member.path("temporaryPassword").asText());

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .creditAmount(new BigDecimal("600000"))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(LocalDate.now().minusMonths(2))
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("rb-seed-" + UUID.randomUUID())
                .description("seed")
                .build());
        loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(buyerId)
                .requestedAmount(new BigDecimal("100000"))
                .approvedAmount(new BigDecimal("100000"))
                .principalAmount(new BigDecimal("100000"))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(new BigDecimal("12000"))
                .outstandingPrincipal(new BigDecimal("100000"))
                .outstandingInterest(new BigDecimal("12000"))
                .requestDate(LocalDate.now().minusMonths(1))
                .disbursementDate(LocalDate.now().minusMonths(1))
                .dueDate(LocalDate.now().plusMonths(5))
                .status(LoanStatus.ACTIVE)
                .build());
        OpeningShareBalances.set(membershipRepository, cooperativeId, buyerId, 100);
    }

    @Test
    void aFailingSnapshotRollsBackStatusSharesAndLedgerCreditTogether() throws Exception {
        UUID purchaseId = submit();
        doThrow(new IllegalStateException("snapshot store unavailable"))
                .when(shareValuationService)
                .persist(any(), eq(ShareValuationSnapshotReason.SHARE_ISSUANCE), any(), any());

        mockMvc.perform(post(approvePath(purchaseId)).header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isInternalServerError());

        assertNothingFromTheApprovalSurvives(purchaseId);
    }

    @Test
    void aFailingLedgerPostingRollsBackStatusSharesAndSnapshotTogether() throws Exception {
        UUID purchaseId = submit();
        doThrow(new IllegalStateException("ledger unavailable"))
                .when(ledgerService)
                .appendApproved(argThat(req -> req != null && req.getTransactionType() == LedgerTransactionType.SHARE_PURCHASE));

        mockMvc.perform(post(approvePath(purchaseId)).header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isInternalServerError());

        assertNothingFromTheApprovalSurvives(purchaseId);
    }

    @Test
    void aFailureAfterTheSnapshotRollsEverythingBackIncludingTheSnapshot() throws Exception {
        UUID purchaseId = submit();
        // Let the real snapshot be written, then fail right after it: the snapshot must not survive.
        doAnswer(invocation -> {
                    Object saved = invocation.callRealMethod();
                    throw new IllegalStateException("failure after the snapshot was written");
                })
                .when(shareValuationService)
                .persist(any(), eq(ShareValuationSnapshotReason.SHARE_ISSUANCE), any(), any());

        mockMvc.perform(post(approvePath(purchaseId)).header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isInternalServerError());

        assertNothingFromTheApprovalSurvives(purchaseId);
    }

    private void assertNothingFromTheApprovalSurvives(UUID purchaseId) {
        SharePurchase purchase = sharePurchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getStatus()).isEqualTo(SharePurchaseStatus.PENDING);
        assertThat(purchase.getReviewedBy()).isNull();
        assertThat(membershipRepository.findByCooperativeIdAndUserId(cooperativeId, buyerId).orElseThrow().getShareCount())
                .isEqualTo(100);
        List<LedgerEntry> credits = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getTransactionType() == LedgerTransactionType.SHARE_PURCHASE
                        && purchaseId.equals(e.getSourceEntityId()))
                .toList();
        assertThat(credits).isEmpty();
        List<ShareValuationSnapshot> issuance = snapshotRepository.findAll().stream()
                .filter(s -> purchaseId.equals(s.getSourceEntityId())
                        && s.getReason() == ShareValuationSnapshotReason.SHARE_ISSUANCE)
                .toList();
        assertThat(issuance).isEmpty();
    }

    private String approvePath(UUID purchaseId) {
        return "/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/" + purchaseId + "/approve";
    }

    private UUID submit() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numberOfShares":10,"paymentDate":"2026-01-15","paymentReference":"MOMO-RB",
                                 "evidenceFileKey":"share-evidence/receipt.pdf"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
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
