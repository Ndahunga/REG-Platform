package rw.terimbere.csams.modules.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.share.entity.SharePurchase;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShareReportPeriodCalculationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private SharePurchaseRepository sharePurchaseRepository;

    @Autowired
    private ShareValuationService shareValuationService;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        String name = "Share Report " + UUID.randomUUID().toString().substring(0, 8);
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

        String username = "share_r_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Report",
                                  "lastName":"Member",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        memberUserId = UUID.fromString(objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
    }

    @Test
    void additionalSharesUseSelectedPeriod_andIgnorePendingRejectedAndOutsideDates() {
        Instant inPeriod = LocalDate.of(2026, 6, 15).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant beforePeriod = LocalDate.of(2025, 12, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant afterPeriod = LocalDate.of(2026, 8, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodFrom = LocalDate.of(2026, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodTo = LocalDate.of(2026, 7, 1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .minusMillis(1);

        savePurchase(2, "100000.0000", SharePurchaseStatus.APPROVED, inPeriod);
        savePurchase(1, "100000.0000", SharePurchaseStatus.APPROVED, beforePeriod);
        savePurchase(1, "100000.0000", SharePurchaseStatus.APPROVED, afterPeriod);
        savePurchase(4, "100000.0000", SharePurchaseStatus.PENDING, inPeriod);
        savePurchase(3, "100000.0000", SharePurchaseStatus.REJECTED, inPeriod);

        var membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow();
        membership.setShareCount(5);
        membershipRepository.save(membership);

        int additional = sharePurchaseRepository
                .sumApprovedSharesInPeriod(cooperativeId, memberUserId, periodFrom, periodTo)
                .intValue();
        int after = sharePurchaseRepository
                .sumApprovedSharesAfter(cooperativeId, memberUserId, periodTo)
                .intValue();
        int owned = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow()
                .getShareCount();
        int sharesHeldEnd = Math.max(0, owned - after);
        int sharesAtStart = Math.max(0, sharesHeldEnd - additional);

        assertThat(additional).isEqualTo(2);
        assertThat(after).isEqualTo(1);
        assertThat(sharesHeldEnd).isEqualTo(4);
        assertThat(sharesAtStart).isEqualTo(2);
        assertThat(sharesAtStart + additional).isEqualTo(sharesHeldEnd);
        assertThat(sharePurchaseRepository
                        .sumApprovedSharesInPeriodForCooperative(cooperativeId, periodFrom, periodTo)
                        .intValue())
                .isEqualTo(2);
        assertThat(sharePurchaseRepository.sumApprovedAmountInPeriod(cooperativeId, periodFrom, periodTo))
                .isEqualByComparingTo(new BigDecimal("200000.0000"));

        var valuation = shareValuationService.calculate(cooperativeId);
        BigDecimal totalShareValue =
                MoneyUtils.multiply(valuation.getCurrentShareValue(), BigDecimal.valueOf(sharesHeldEnd));
        assertThat(valuation.getTotalIkiminaValue()).isNotNull();
        assertThat(valuation.getCurrentShareValue()).isNotNull();
        assertThat(totalShareValue).isEqualByComparingTo(
                MoneyUtils.multiply(valuation.getCurrentShareValue(), BigDecimal.valueOf(4)));
    }

    @Test
    void openingBalanceWithoutPurchasesIsNotAdditionalShares() {
        Instant periodFrom = LocalDate.of(2026, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodTo = LocalDate.of(2026, 7, 1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .minusMillis(1);

        var membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow();
        membership.setShareCount(3);
        membershipRepository.save(membership);

        int additional = sharePurchaseRepository
                .sumApprovedSharesInPeriod(cooperativeId, memberUserId, periodFrom, periodTo)
                .intValue();
        int after = sharePurchaseRepository
                .sumApprovedSharesAfter(cooperativeId, memberUserId, periodTo)
                .intValue();
        int owned = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow()
                .getShareCount();
        int sharesHeldEnd = Math.max(0, owned - after);
        int sharesAtStart = Math.max(0, sharesHeldEnd - additional);

        assertThat(additional).isZero();
        assertThat(after).isZero();
        assertThat(sharesHeldEnd).isEqualTo(3);
        assertThat(sharesAtStart).isEqualTo(3);
        assertThat(sharesAtStart + additional).isEqualTo(sharesHeldEnd);
    }

    @Test
    void purchaseOnlyMemberKeepsStartPlusAdditionalEqualsEnd() {
        Instant inPeriod = LocalDate.of(2026, 6, 15).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodFrom = LocalDate.of(2026, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodTo = LocalDate.of(2026, 7, 1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .minusMillis(1);

        savePurchase(2, "100000.0000", SharePurchaseStatus.APPROVED, inPeriod);
        var membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow();
        membership.setShareCount(2);
        membershipRepository.save(membership);

        int additional = sharePurchaseRepository
                .sumApprovedSharesInPeriod(cooperativeId, memberUserId, periodFrom, periodTo)
                .intValue();
        int after = sharePurchaseRepository
                .sumApprovedSharesAfter(cooperativeId, memberUserId, periodTo)
                .intValue();
        int owned = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow()
                .getShareCount();
        int sharesHeldEnd = Math.max(0, owned - after);
        int sharesAtStart = Math.max(0, sharesHeldEnd - additional);

        assertThat(additional).isEqualTo(2);
        assertThat(sharesAtStart).isZero();
        assertThat(sharesHeldEnd).isEqualTo(2);
        assertThat(sharesAtStart + additional).isEqualTo(sharesHeldEnd);
    }

    private void savePurchase(int shares, String price, SharePurchaseStatus status, Instant reviewedAt) {
        BigDecimal unit = new BigDecimal(price);
        sharePurchaseRepository.save(SharePurchase.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberUserId)
                .numberOfShares(shares)
                .pricePerShare(unit)
                .totalAmount(unit.multiply(BigDecimal.valueOf(shares)))
                .status(status)
                .requestedBy(memberUserId)
                .requestedAt(reviewedAt)
                .reviewedBy(status == SharePurchaseStatus.PENDING ? null : memberUserId)
                .reviewedAt(status == SharePurchaseStatus.PENDING ? null : reviewedAt)
                .rejectionReason(status == SharePurchaseStatus.REJECTED ? "Not used" : null)
                .paymentDate(LocalDate.of(2026, 1, 15))
                .build());
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
