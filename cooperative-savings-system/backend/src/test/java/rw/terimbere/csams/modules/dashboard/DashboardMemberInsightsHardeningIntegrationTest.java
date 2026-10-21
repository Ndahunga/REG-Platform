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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.entity.FineType;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
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
class DashboardMemberInsightsHardeningIntegrationTest {

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
    private FineRepository fineRepository;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private UUID memberBUserId;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZONE);
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "MI Harden Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        memberUserId = registerMember("mih_a_");
        memberBUserId = registerMember("mih_b_");
    }

    private UUID registerMember(String prefix) throws Exception {
        String memberUsername = prefix + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "firstName":"Hard",
                                  "lastName":"Member",
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
        UUID userId = UUID.fromString(memberData.path("userId").asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, userId, 1);
        return userId;
    }

    @Test
    void memberInsights_doesNotMutateActivePastDueLoanStatus() throws Exception {
        Loan pastDue = saveLoan(
                memberUserId,
                LoanStatus.ACTIVE,
                today.minusDays(5),
                money("100000"),
                money("5000"));
        LoanStatus before = pastDue.getStatus();

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/member-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueLoans.length()").value(1))
                .andExpect(jsonPath("$.data.overdueLoans[0].memberId").value(memberUserId.toString()))
                .andExpect(jsonPath("$.data.overdueLoans[0].outstandingPrincipal").value(100000.0));

        Loan after = loanRepository.findById(pastDue.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(before).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void expiredCooperative_memberInsightsReadOnly_doesNotMutateLoans() throws Exception {
        Loan pastDue = saveLoan(
                memberUserId,
                LoanStatus.ACTIVE,
                today.minusDays(2),
                money("80000"),
                money("0"));
        expireTrial(cooperativeId);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/member-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueLoans.length()").value(1));

        Loan after = loanRepository.findById(pastDue.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void overdueQuery_includesPastDueActive_excludesFutureAndRepaid() {
        // past due ACTIVE — include
        saveLoan(memberUserId, LoanStatus.ACTIVE, today.minusDays(1), money("50000"), money("0"));
        // future due ACTIVE — exclude
        saveLoan(memberUserId, LoanStatus.ACTIVE, today.plusDays(30), money("90000"), money("0"));
        // CLOSED repaid — exclude
        saveLoan(memberUserId, LoanStatus.CLOSED, today.minusDays(10), money("0"), money("0"));
        // already OVERDUE with balance — include
        saveLoan(memberBUserId, LoanStatus.OVERDUE, today.minusDays(40), money("200000"), money("1000"));

        List<Object[]> rows = loanRepository.sumOverdueGroupedByMemberOrdered(
                cooperativeId, today, PageRequest.of(0, 5));

        assertThat(rows).hasSize(2);
        // ordered by outstanding principal DESC → memberB (200000) then memberUserId (50000)
        assertThat((UUID) rows.get(0)[0]).isEqualTo(memberBUserId);
        assertThat((BigDecimal) rows.get(0)[2]).isEqualByComparingTo("200000");
        assertThat((UUID) rows.get(1)[0]).isEqualTo(memberUserId);
        assertThat((BigDecimal) rows.get(1)[2]).isEqualByComparingTo("50000");
    }

    @Test
    void fineFollowUp_excludesPaidCancelledWaived_ranksByOutstanding() {
        LocalDate issued = today.minusDays(10);
        // unpaid outstanding — include
        saveFine(memberUserId, FineStatus.UNPAID, money("40000"), money("0"), money("40000"), issued);
        // partially paid — include remaining
        saveFine(memberBUserId, FineStatus.PARTIALLY_PAID, money("50000"), money("20000"), money("30000"), issued);
        // paid — exclude
        saveFine(memberUserId, FineStatus.PAID, money("10000"), money("10000"), money("0"), issued);
        // cancelled with leftover outstanding field — exclude
        saveFine(memberUserId, FineStatus.CANCELLED, money("15000"), money("0"), money("15000"), issued);
        // waived (domain does not clear outstandingAmount) — exclude
        saveFine(memberUserId, FineStatus.WAIVED, money("25000"), money("0"), money("25000"), issued);
        // prior year — exclude
        saveFine(
                memberUserId,
                FineStatus.UNPAID,
                money("99000"),
                money("0"),
                money("99000"),
                LocalDate.of(today.getYear() - 1, 6, 1));

        List<Object[]> rows = fineRepository.sumFineFollowUpGroupedByMemberOrdered(
                cooperativeId, LocalDate.of(today.getYear(), 1, 1), today, PageRequest.of(0, 5));

        assertThat(rows).hasSize(2);
        // memberUserId unpaid 40000 ranks above memberB 30000
        assertThat((UUID) rows.get(0)[0]).isEqualTo(memberUserId);
        assertThat((BigDecimal) rows.get(0)[3]).isEqualByComparingTo("40000");
        assertThat(((Number) rows.get(0)[4]).longValue()).isEqualTo(1L);
        assertThat((UUID) rows.get(1)[0]).isEqualTo(memberBUserId);
        assertThat((BigDecimal) rows.get(1)[1]).isEqualByComparingTo("50000");
        assertThat((BigDecimal) rows.get(1)[2]).isEqualByComparingTo("20000");
        assertThat((BigDecimal) rows.get(1)[3]).isEqualByComparingTo("30000");
    }

    private Loan saveLoan(
            UUID memberId,
            LoanStatus status,
            LocalDate dueDate,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest) {
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .requestedAmount(outstandingPrincipal.max(money("1")))
                .approvedAmount(outstandingPrincipal.max(money("1")))
                .principalAmount(outstandingPrincipal.max(money("1")))
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

    private Fine saveFine(
            UUID memberId,
            FineStatus status,
            BigDecimal total,
            BigDecimal paid,
            BigDecimal outstanding,
            LocalDate issuedDate) {
        return fineRepository.saveAndFlush(Fine.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .fineType(FineType.MANUAL)
                .calculationMode(FineCalculationMode.FIXED)
                .baseAmount(total)
                .totalAmount(total)
                .paidAmount(paid)
                .outstandingAmount(outstanding)
                .reason("test")
                .issuedDate(issuedDate)
                .dueDate(issuedDate.plusDays(7))
                .status(status)
                .issuedBy(memberUserId)
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
