package rw.terimbere.csams.modules.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SharePurchaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private SharePurchaseRepository sharePurchaseRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        String name = "Share Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name, "10000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        cooperativeId = UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        String username = "share_m_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Share",
                                  "lastName":"Buyer",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(0))
                .andReturn();
        JsonNode member = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        memberUserId = UUID.fromString(member.path("userId").asText());
        memberToken = loginAccessToken(username, member.path("temporaryPassword").asText());
    }

    @Test
    void valuationPurchaseApprovalRejectionAndReports() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalExistingShares").value(0))
                .andExpect(jsonPath("$.data.canPurchase").value(false));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isUnprocessableEntity());

        configureBaseSharePrice("100000.0000");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedBaseSharePrice").value(true))
                .andExpect(jsonPath("$.data.currentShareValue").value(100000.0))
                .andExpect(jsonPath("$.data.canPurchase").value(true));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(0)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numberOfShares\":1}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1, null)))
                .andExpect(status().isBadRequest());

        MvcResult firstSubmit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.numberOfShares").value(1))
                .andExpect(jsonPath("$.data.paymentDate").value("2026-01-15"))
                .andExpect(jsonPath("$.data.paymentReference").value("MOMO-1001"))
                .andExpect(jsonPath("$.data.evidenceFileKey").value("share-evidence/receipt.pdf"))
                .andExpect(jsonPath("$.data.pricePerShare").value(100000.0))
                .andExpect(jsonPath("$.data.currentShareValue").value(100000.0))
                .andExpect(jsonPath("$.data.totalAmount").value(100000.0))
                .andExpect(jsonPath("$.data.pricingValuation.currentShareValue").value(100000.0))
                .andExpect(jsonPath("$.data.pricingValuation.totalExistingShares").value(0))
                .andReturn();
        UUID firstPurchaseId = UUID.fromString(objectMapper
                .readTree(firstSubmit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isZero();

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + firstPurchaseId + "/approve")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + firstPurchaseId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isEqualTo(1);

        long ledgerCount = ledgerEntryRepository.findAll().stream()
                .filter(e -> firstPurchaseId.equals(e.getSourceEntityId()))
                .filter(e -> e.getTransactionType() == LedgerTransactionType.SHARE_PURCHASE)
                .filter(e -> e.getStatus() == LedgerEntryStatus.APPROVED)
                .count();
        assertThat(ledgerCount).isEqualTo(1);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + firstPurchaseId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isUnprocessableEntity());
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isEqualTo(1);
        assertThat(ledgerEntryRepository.findAll().stream()
                        .filter(e -> firstPurchaseId.equals(e.getSourceEntityId()))
                        .filter(e -> e.getTransactionType() == LedgerTransactionType.SHARE_PURCHASE)
                        .filter(e -> e.getStatus() == LedgerEntryStatus.APPROVED)
                        .count())
                .isEqualTo(1);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalExistingShares").value(1))
                .andExpect(jsonPath("$.data.availableFunds").value(100000.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(100000.0))
                .andExpect(jsonPath("$.data.currentShareValue").value(100000.0))
                .andExpect(jsonPath("$.data.usedBaseSharePrice").value(false));

        MvcResult secondSubmit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pricePerShare").value(100000.0))
                .andExpect(jsonPath("$.data.pricingValuation.currentShareValue").value(100000.0))
                .andReturn();
        UUID secondPurchaseId = UUID.fromString(objectMapper
                .readTree(secondSubmit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(secondPurchaseId.toString()))
                .andExpect(jsonPath("$.data.content[0].pricePerShare").value(100000.0))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + secondPurchaseId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pricePerShare").value(100000.0));
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isEqualTo(2);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalExistingShares").value(2))
                .andExpect(jsonPath("$.data.availableFunds").value(200000.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(200000.0))
                .andExpect(jsonPath("$.data.currentShareValue").value(100000.0));

        MvcResult rejectSubmit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andReturn();
        UUID rejectedId = UUID.fromString(objectMapper
                .readTree(rejectSubmit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/" + rejectedId + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectionReason\":\"Incomplete payment proof\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isEqualTo(2);
        assertThat(sharePurchaseRepository.findById(rejectedId).orElseThrow().getStatus())
                .isEqualTo(SharePurchaseStatus.REJECTED);

        String today = LocalDate.now().toString();
        MvcResult membersReport = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportType":"MEMBERS","fromDate":"%s","toDate":"%s"}
                                """.formatted(today, today)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(membersReport.getResponse().getContentAsByteArray()).isNotEmpty();

        MvcResult outsidePeriod = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportType":"MEMBERS","fromDate":"2020-01-01","toDate":"2020-12-31"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(outsidePeriod.getResponse().getContentAsByteArray()).isNotEmpty();

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportType":"FULL_FINANCIAL","fromDate":"%s","toDate":"%s"}
                                """.formatted(today, today)))
                .andExpect(status().isOk());

        java.time.Instant windowFrom = java.time.Instant.now().minusSeconds(86_400);
        java.time.Instant windowTo = java.time.Instant.now().plusSeconds(86_400);
        assertThat(sharePurchaseRepository
                        .sumApprovedSharesInPeriodForCooperative(cooperativeId, windowFrom, windowTo)
                        .intValue())
                .isEqualTo(2);
        assertThat(sharePurchaseRepository.sumApprovedAmountInPeriod(cooperativeId, windowFrom, windowTo))
                .isEqualByComparingTo(new BigDecimal("200000.0000"));
        assertThat(sharePurchaseRepository
                        .sumApprovedSharesInPeriodForCooperative(
                                cooperativeId,
                                java.time.Instant.parse("2020-01-01T00:00:00Z"),
                                java.time.Instant.parse("2020-12-31T23:59:59Z"))
                        .intValue())
                .isZero();
    }

    @Test
    void accountantCanApprove_otherOfficersCannot_andOwnershipCapIsEnforced() throws Exception {
        configureBaseSharePrice("100000.0000");
        String accountantToken = registerOfficer("ACCOUNTANT");
        String secretaryToken = registerOfficer("SECRETARY");
        String loanOfficerToken = registerOfficer("LOAN_OFFICER");

        MvcResult submit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andReturn();
        UUID purchaseId = UUID.fromString(objectMapper
                .readTree(submit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + purchaseId + "/approve")
                        .header("Authorization", "Bearer " + secretaryToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + purchaseId + "/approve")
                        .header("Authorization", "Bearer " + loanOfficerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + purchaseId + "/approve")
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isEqualTo(1);

        recordApprovedContribution("200000.0000", "2026-03-15");

        var membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow();
        membership.setShareCount(999);
        membershipRepository.save(membership);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(2)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isEqualTo(999);
    }

    @Test
    void vicePresidentAndPresidentCanApprove() throws Exception {
        configureBaseSharePrice("100000.0000");
        String vicePresidentToken = registerOfficer("VICE_PRESIDENT");
        String presidentToken = registerOfficer("PRESIDENT");

        MvcResult first = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andReturn();
        UUID firstId = UUID.fromString(objectMapper
                .readTree(first.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/pending")
                        .header("Authorization", "Bearer " + vicePresidentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(firstId.toString()));
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + firstId + "/approve")
                        .header("Authorization", "Bearer " + vicePresidentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isEqualTo(1);

        MvcResult second = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andReturn();
        UUID secondId = UUID.fromString(objectMapper
                .readTree(second.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + secondId + "/approve")
                        .header("Authorization", "Bearer " + presidentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                        .orElseThrow()
                        .getShareCount())
                .isEqualTo(2);
    }

    @Test
    void approvedPurchaseIsValueNeutral() throws Exception {
        configureBaseSharePrice("100000.0000");

        MvcResult first = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andReturn();
        UUID firstId = UUID.fromString(objectMapper
                .readTree(first.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + firstId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalExistingShares").value(1))
                .andExpect(jsonPath("$.data.availableFunds").value(100000.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(100000.0))
                .andExpect(jsonPath("$.data.currentShareValue").value(100000.0))
                .andExpect(jsonPath("$.data.canPurchase").value(true));

        MvcResult second = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseBody(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pricePerShare").value(100000.0))
                .andReturn();
        UUID secondId = UUID.fromString(objectMapper
                .readTree(second.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/shares/purchases/"
                                + secondId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalExistingShares").value(2))
                .andExpect(jsonPath("$.data.availableFunds").value(200000.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(200000.0))
                .andExpect(jsonPath("$.data.currentShareValue").value(100000.0));
    }

    @Test
    void ordinaryRegisterAndUpdateIgnoreRequestedShareCount() throws Exception {
        String username = "ignore_sc_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Ignore",
                                  "lastName":"Shares",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount":5
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(0))
                .andReturn();
        UUID userId = UUID.fromString(objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/members/" + userId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Ignore",
                                  "lastName":"Shares",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "shareCount":10
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(0));
        assertThat(membershipRepository
                        .findByCooperativeIdAndUserId(cooperativeId, userId)
                        .orElseThrow()
                        .getShareCount())
                .isZero();

        String presidentToken = registerOfficer("PRESIDENT");
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/members/" + userId)
                        .header("Authorization", "Bearer " + presidentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Ignore",
                                  "lastName":"Shares",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "shareCount":8
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(0));
    }

    @Test
    void baseSharePriceClearOmitAndInvalidValues() throws Exception {
        configureBaseSharePrice("100000.0000");

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timezone":"Africa/Kigali",
                                  "locale":"en",
                                  "clearBaseSharePrice":true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseSharePrice").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timezone":"Africa/Kigali",
                                  "locale":"en"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseSharePrice").value(org.hamcrest.Matchers.nullValue()));

        configureBaseSharePrice("100000.0000");
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timezone":"Africa/Kigali",
                                  "locale":"en"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseSharePrice").value(100000.0));

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timezone":"Africa/Kigali",
                                  "locale":"en",
                                  "baseSharePrice":0
                                }
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timezone":"Africa/Kigali",
                                  "locale":"en",
                                  "baseSharePrice":-1
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseSharePrice").value(100000.0));
    }

    private static String purchaseBody(int shares) {
        return purchaseBody(shares, "share-evidence/receipt.pdf");
    }

    private static String purchaseBody(int shares, String evidenceFileKey) {
        String evidenceJson = evidenceFileKey == null
                ? ""
                : ",\"evidenceFileKey\":\"" + evidenceFileKey + "\"";
        return """
                {"numberOfShares":%s,"paymentDate":"2026-01-15","paymentReference":"MOMO-1001","notes":"Paid via MoMo"%s}
                """.formatted(shares, evidenceJson);
    }

    private void configureBaseSharePrice(String amount) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timezone":"Africa/Kigali",
                                  "locale":"en",
                                  "notifyContributions":true,
                                  "notifyLoans":true,
                                  "notifyFines":true,
                                  "notifyPayouts":true,
                                  "baseSharePrice":%s
                                }
                                """.formatted(amount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseSharePrice").value(100000.0));
    }

    private void recordApprovedContribution(String paidAmount, String paymentDate) throws Exception {
        LocalDate date = LocalDate.parse(paymentDate);
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", String.valueOf(date.getYear()))
                        .param("month", String.valueOf(date.getMonthValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId":"%s",
                                      "paidAmount":%s,
                                      "paymentDate":"%s"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId, paidAmount, paymentDate)))
                .andExpect(status().isOk());
    }

    private String registerOfficer(String role) throws Exception {
        String username = role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"%s",
                                  "lastName":"Officer",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s"
                                }
                                """.formatted(role, username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        String password = objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();
        return loginAccessToken(username, password);
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
}
