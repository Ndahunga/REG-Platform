package rw.terimbere.csams.modules.loan;

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
class LoanReadPathHardeningIntegrationTest {

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
    private String memberUsername;
    private String memberPassword;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZONE);
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Loan RO Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        memberUsername = "lro_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "firstName":"Loan",
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
        memberPassword = memberData.path("temporaryPassword").asText();
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 1);
    }

    @Test
    void getLoanDetail_reportsEffectiveOverdueWithoutMutating() throws Exception {
        Loan pastDue = saveLoan(LoanStatus.ACTIVE, today.minusDays(2), money("100000"), money("0"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + pastDue.getId())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVERDUE"));

        assertThat(loanRepository.findById(pastDue.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void listLoans_doesNotMutateAndFiltersEffectiveOverdue() throws Exception {
        Loan pastDue = saveLoan(LoanStatus.ACTIVE, today.minusDays(1), money("50000"), money("0"));
        Loan future = saveLoan(LoanStatus.ACTIVE, today.plusDays(20), money("60000"), money("0"));
        Loan zeroBalance = saveLoan(LoanStatus.ACTIVE, today.minusDays(5), money("0"), money("0"));
        Loan storedOverdue = saveLoan(LoanStatus.OVERDUE, today.minusDays(40), money("70000"), money("0"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("status", "OVERDUE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));

        assertThat(loanRepository.findById(pastDue.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.ACTIVE);
        assertThat(loanRepository.findById(future.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.ACTIVE);
        assertThat(loanRepository.findById(zeroBalance.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.ACTIVE);
        assertThat(loanRepository.findById(storedOverdue.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.OVERDUE);
    }

    @Test
    void myLoansAndMemberDetail_doNotMutatePastDueActive() throws Exception {
        Loan pastDue = saveLoan(LoanStatus.ACTIVE, today.minusDays(3), money("80000"), money("0"));
        String memberToken = loginAccessToken(memberUsername, memberPassword);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/my")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("OVERDUE"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + memberUserId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loans[0].status").value("OVERDUE"));

        assertThat(loanRepository.findById(pastDue.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void expiredCooperative_loanReadsRemainReadOnlyWithEffectiveOverdue() throws Exception {
        Loan pastDue = saveLoan(LoanStatus.ACTIVE, today.minusDays(4), money("90000"), money("0"));
        expireTrial(cooperativeId);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + pastDue.getId())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVERDUE"));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/my")
                        .header("Authorization", "Bearer " + loginAccessToken(memberUsername, memberPassword)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + memberUserId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        assertThat(loanRepository.findById(pastDue.getId()).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.ACTIVE);
        assertThat(loanRepository.findById(pastDue.getId()).orElseThrow().getOutstandingPrincipal())
                .isEqualByComparingTo("90000");
    }

    private Loan saveLoan(
            LoanStatus status, LocalDate dueDate, BigDecimal outstandingPrincipal, BigDecimal outstandingInterest) {
        BigDecimal amount = outstandingPrincipal.compareTo(BigDecimal.ZERO) > 0
                ? outstandingPrincipal
                : money("1");
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberUserId)
                .requestedAmount(amount)
                .approvedAmount(amount)
                .principalAmount(amount)
                .interestRatePercent(money("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(outstandingInterest)
                .outstandingPrincipal(outstandingPrincipal)
                .outstandingInterest(outstandingInterest)
                .requestDate(today.minusMonths(3))
                .disbursementDate(today.minusMonths(2))
                .dueDate(dueDate)
                .status(status)
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
