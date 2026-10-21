package rw.terimbere.csams.modules.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardReadOnlyHardeningIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kigali");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZONE);
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Dash RO Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        String memberUsername = "dro_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "firstName":"Dash",
                                  "lastName":"Reader",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """
                                        .formatted(memberUsername, memberUsername)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode memberData =
                objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        memberUserId = UUID.fromString(memberData.path("userId").asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 1);
    }

    @Test
    void summary_reportsOverdueWithoutMutatingActivePastDueLoan() throws Exception {
        Loan pastDue = saveActivePastDue(money("100000"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueLoansCount").value(1))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal").value(100000.0));

        Loan after = loanRepository.findById(pastDue.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void expiredCooperative_dashboardGetsAreReadOnly() throws Exception {
        Loan pastDue = saveActivePastDue(money("75000"));
        expireTrial(cooperativeId);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueLoansCount").value(1));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loans.outstandingPrincipal").value(75000.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/member-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueLoans.length()").value(1));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/monthly-contributions?year=" + today.getYear())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        Loan after = loanRepository.findById(pastDue.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(after.getOutstandingPrincipal()).isEqualByComparingTo("75000");
    }

    @Test
    void insights_doesNotMutateLoanStatus() throws Exception {
        Loan pastDue = saveActivePastDue(money("55000"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loans.outstandingPrincipal").value(55000.0));

        assertThat(loanRepository.findById(pastDue.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.ACTIVE);
    }

    private Loan saveActivePastDue(BigDecimal outstandingPrincipal) {
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberUserId)
                .requestedAmount(outstandingPrincipal)
                .approvedAmount(outstandingPrincipal)
                .principalAmount(outstandingPrincipal)
                .interestRatePercent(money("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(money("0"))
                .outstandingPrincipal(outstandingPrincipal)
                .outstandingInterest(money("0"))
                .requestDate(today.minusMonths(3))
                .disbursementDate(today.minusMonths(2))
                .dueDate(today.minusDays(3))
                .status(LoanStatus.ACTIVE)
                .build());
    }

    private void expireTrial(UUID coopId) {
        CooperativeSubscription subscription =
                subscriptionRepository.findByCooperativeId(coopId).orElseThrow();
        Instant ended = Instant.now().minusSeconds(60);
        subscription.setStatus(SubscriptionStatus.TRIAL);
        subscription.setTrialEndsAt(ended);
        if (subscription.getTrialStartedAt() == null) {
            subscription.setTrialStartedAt(ended.minusSeconds(86_400));
        }
        subscriptionRepository.saveAndFlush(subscription);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(4);
    }

    private String loginAccessToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
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
