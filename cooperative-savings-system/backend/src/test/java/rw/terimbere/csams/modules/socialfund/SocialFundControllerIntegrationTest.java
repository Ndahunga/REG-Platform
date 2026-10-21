package rw.terimbere.csams.modules.socialfund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.socialfund.repository.SocialContributionRepository;
import rw.terimbere.csams.modules.socialfund.repository.SocialDisbursementRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialFundControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SocialContributionRepository contributionRepository;

    @Autowired
    private SocialDisbursementRepository disbursementRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberUsername;
    private String memberPassword;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Social Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn();
        cooperativeId = UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        memberUsername = "smember_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Social",
                                  "lastName":"Member",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """.formatted(memberUsername, memberUsername)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode memberData =
                objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        memberUserId = UUID.fromString(memberData.path("userId").asText());
        memberPassword = memberData.path("temporaryPassword").asText();
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 1);
    }

    @Test
    void approveContribution_increasesBalance() throws Exception {
        UUID contributionId = submitAndApproveContribution(500.0000);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(500.0))
                .andExpect(jsonPath("$.data.totalApprovedContributions").value(500.0))
                .andExpect(jsonPath("$.data.totalApprovedDisbursements").value(0.0))
                .andExpect(jsonPath("$.data.pendingContributions").value(0));

        assertThat(contributionRepository.findById(contributionId)).isPresent();
        assertThat(ledgerEntryRepository
                        .findBySourceEntityTypeAndSourceEntityIdAndStatus(
                                "SOCIAL_CONTRIBUTION", contributionId, LedgerEntryStatus.APPROVED))
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    assertThat(e.getTransactionType()).isEqualTo(LedgerTransactionType.SOCIAL_CONTRIBUTION);
                    assertThat(e.getCreditAmount()).isEqualByComparingTo("500.0000");
                });
    }

    @Test
    void disbursementGreaterThanBalance_isBlocked() throws Exception {
        submitAndApproveContribution(200.0000);

        UUID disbursementId = requestDisbursement(500.0000, "Emergency");

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/social-fund/disbursements/"
                                + disbursementId
                                + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("insufficient")));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(200.0));
    }

    @Test
    void approveDisbursement_decreasesBalanceAndPostsLedgerDebit() throws Exception {
        submitAndApproveContribution(1000.0000);
        UUID disbursementId = requestDisbursement(400.0000, "Medical support");

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/social-fund/disbursements/"
                                + disbursementId
                                + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(600.0))
                .andExpect(jsonPath("$.data.totalApprovedDisbursements").value(400.0));

        assertThat(ledgerEntryRepository
                        .findBySourceEntityTypeAndSourceEntityIdAndStatus(
                                "SOCIAL_DISBURSEMENT", disbursementId, LedgerEntryStatus.APPROVED))
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    assertThat(e.getTransactionType()).isEqualTo(LedgerTransactionType.SOCIAL_DISBURSEMENT);
                    assertThat(e.getDebitAmount()).isEqualByComparingTo("400.0000");
                });
    }

    @Test
    void rejectContribution_doesNotChangeBalance() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        MvcResult submit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/social-fund/contributions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300.0000,
                                  "contributionDate": "2026-08-01"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        UUID contributionId = UUID.fromString(objectMapper
                .readTree(submit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/social-fund/contributions/"
                                + contributionId
                                + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewNotes\":\"Incomplete\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0.0))
                .andExpect(jsonPath("$.data.totalApprovedContributions").value(0.0));

        assertThat(ledgerEntryRepository
                        .findBySourceEntityTypeAndSourceEntityIdAndStatus(
                                "SOCIAL_CONTRIBUTION", contributionId, LedgerEntryStatus.APPROVED))
                .isEmpty();
    }

    @Test
    void socialAmounts_notInAvailableGroupFunds() throws Exception {
        persistPaidContribution(2026, 3, 1000.0000);
        submitAndApproveContribution(500.0000);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(1000.0))
                .andExpect(jsonPath("$.data.socialFundBalance").value(500.0))
                .andExpect(jsonPath("$.data.socialContributionsTotal").value(500.0))
                .andExpect(jsonPath("$.data.socialDisbursementsTotal").value(0.0));
    }

    @Test
    void coopIsolation_andMemberCanSubmitOwnContribution() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/social-fund/contributions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 150.0000,
                                  "contributionDate": "2026-08-02",
                                  "paymentReference": "SF-SELF"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberUserId").value(memberUserId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/contributions/my")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        String otherName = "Other Social " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult other = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(otherName)))
                .andExpect(status().isOk())
                .andReturn();
        UUID otherCoopId = UUID.fromString(objectMapper
                .readTree(other.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        submitAndApproveContribution(250.0000);

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoopId + "/social-fund/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0.0))
                .andExpect(jsonPath("$.data.totalApprovedContributions").value(0.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(250.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + memberUserId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.social.length()").value(2));
    }

    @Test
    void settings_getOrCreateAndUpdate() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/settings")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.suggestedContributionAmount").value(0.0));

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/social-fund/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "suggestedContributionAmount": 100.0000,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestedContributionAmount").value(100.0));
    }

    @Test
    void report_returnsApprovedInPeriod() throws Exception {
        submitAndApproveContribution(700.0000);
        UUID disbursementId = requestDisbursement(200.0000, "Funeral support");
        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/social-fund/disbursements/"
                                + disbursementId
                                + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/report")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.balance").value(500.0))
                .andExpect(jsonPath("$.data.approvedContributions.length()").value(1))
                .andExpect(jsonPath("$.data.approvedDisbursements.length()").value(1));
    }

    @Test
    void cancelPendingDisbursement() throws Exception {
        submitAndApproveContribution(500.0000);
        UUID disbursementId = requestDisbursement(100.0000, "Cancel me");

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/social-fund/disbursements/"
                                + disbursementId
                                + "/cancel")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(disbursementRepository.findById(disbursementId)).isPresent();
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/social-fund/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(500.0))
                .andExpect(jsonPath("$.data.pendingDisbursements").value(0));
    }

    private UUID submitAndApproveContribution(double amount) throws Exception {
        MvcResult submit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/social-fund/contributions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": %s,
                                  "contributionDate": "2026-08-01",
                                  "paymentReference": "SF-1"
                                }
                                """.formatted(memberUserId, BigDecimal.valueOf(amount).toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        UUID contributionId = UUID.fromString(objectMapper
                .readTree(submit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/social-fund/contributions/"
                                + contributionId
                                + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        return contributionId;
    }

    private UUID requestDisbursement(double amount, String reason) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/social-fund/disbursements")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "beneficiaryMemberUserId": "%s",
                                  "amount": %s,
                                  "disbursementDate": "2026-08-03",
                                  "reason": "%s"
                                }
                                """.formatted(
                                        memberUserId, BigDecimal.valueOf(amount).toPlainString(), reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private void persistPaidContribution(int year, int month, double amount) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", String.valueOf(year))
                        .param("month", String.valueOf(month))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": %s,
                                      "paymentDate": "2026-03-10",
                                      "paymentReference": "FUND"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId, BigDecimal.valueOf(amount).toPlainString())))
                .andExpect(status().isOk());
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
