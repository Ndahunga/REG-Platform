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
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentComponent;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepaymentAllocation;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentAllocationRepository;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.subscription.entity.CooperativeSubscription;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionStatus;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardRepaymentReliabilityIntegrationTest {

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
    private LoanInstallmentRepository loanInstallmentRepository;

    @Autowired
    private LoanRepaymentRepository loanRepaymentRepository;

    @Autowired
    private LoanRepaymentAllocationRepository loanRepaymentAllocationRepository;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberAId;
    private UUID memberBId;
    private String memberUsername;
    private String memberPassword;
    private String secretaryUsername;
    private String secretaryPassword;
    private String loanOfficerUsername;
    private String loanOfficerPassword;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZONE);
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "Rel Coop " + UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andReturn();
        cooperativeId = UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        memberUsername = "rel_m_" + UUID.randomUUID().toString().substring(0, 8);
        MemberReg a = registerMember(memberUsername, "Jane", "Reliable", "MEMBER");
        memberAId = a.userId();
        memberPassword = a.password();

        MemberReg b = registerMember(
                "rel_b_" + UUID.randomUUID().toString().substring(0, 8), "Eric", "Late", "MEMBER");
        memberBId = b.userId();

        secretaryUsername = "rel_s_" + UUID.randomUUID().toString().substring(0, 8);
        secretaryPassword = registerMember(secretaryUsername, "Sec", "Retary", "SECRETARY").password();

        loanOfficerUsername = "rel_l_" + UUID.randomUUID().toString().substring(0, 8);
        loanOfficerPassword =
                registerMember(loanOfficerUsername, "Loan", "Officer", "LOAN_OFFICER").password();
    }

    @Test
    void ranksReliableMemberAndUsesCompletionDateNotFirstPayment() throws Exception {
        Loan loanA = saveLoan(memberAId);
        // 3 on-time installments for Jane
        for (int i = 1; i <= 3; i++) {
            LocalDate due = today.minusMonths(4 - i);
            LoanInstallment inst = saveInstallment(loanA, i, due, LoanInstallmentStatus.PAID, "100000", "100000");
            saveRepaymentWithAllocation(loanA, memberAId, inst, due.minusDays(1), "100000");
        }

        Loan loanB = saveLoan(memberBId);
        // Two payments on one installment: early partial + late completing → LATE
        LoanInstallment lateInst =
                saveInstallment(loanB, 1, today.minusMonths(2), LoanInstallmentStatus.PAID, "100000", "100000");
        saveRepaymentWithAllocation(loanB, memberBId, lateInst, today.minusMonths(2).minusDays(5), "40000");
        saveRepaymentWithAllocation(loanB, memberBId, lateInst, today.minusMonths(2).plusDays(3), "60000");
        // two more late
        for (int i = 2; i <= 3; i++) {
            LocalDate due = today.minusMonths(4 - i);
            LoanInstallment inst = saveInstallment(loanB, i, due, LoanInstallmentStatus.PAID, "100000", "100000");
            saveRepaymentWithAllocation(loanB, memberBId, inst, due.plusDays(2), "100000");
        }

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentReliability.period").value("LIFETIME"))
                .andExpect(jsonPath("$.data.repaymentReliability.minimumSample").value(3))
                .andExpect(jsonPath("$.data.repaymentReliability.members.length()").value(2))
                .andExpect(jsonPath("$.data.repaymentReliability.members[0].memberId").value(memberAId.toString()))
                .andExpect(jsonPath("$.data.repaymentReliability.members[0].displayName").value("Jane Reliable"))
                .andExpect(jsonPath("$.data.repaymentReliability.members[0].onTimeRate").value(100.0))
                .andExpect(jsonPath("$.data.repaymentReliability.members[0].rank").value(1))
                .andExpect(jsonPath("$.data.repaymentReliability.members[1].memberId").value(memberBId.toString()))
                .andExpect(jsonPath("$.data.repaymentReliability.members[1].onTimeRate").value(0.0))
                .andExpect(jsonPath("$.data.repaymentReliability.members[1].installmentsPaidLate").value(3));
    }

    @Test
    void excludesInsufficientSampleAndFutureInstallments() throws Exception {
        Loan loan = saveLoan(memberAId);
        LocalDate due = today.minusMonths(1);
        LoanInstallment paid = saveInstallment(loan, 1, due, LoanInstallmentStatus.PAID, "50000", "50000");
        saveRepaymentWithAllocation(loan, memberAId, paid, due, "50000");
        saveInstallment(loan, 2, today.plusMonths(1), LoanInstallmentStatus.PENDING, "50000", "0");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentReliability.members").isEmpty());
    }

    @Test
    void authorization_memberSecretaryForbidden_loanOfficerAllowed() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        String secretaryToken = loginAccessToken(secretaryUsername, secretaryPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + secretaryToken))
                .andExpect(status().isForbidden());

        String loanOfficerToken = loginAccessToken(loanOfficerUsername, loanOfficerPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + loanOfficerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentReliability").exists())
                .andExpect(jsonPath("$.data.repaymentReliability.members").isArray());
    }

    @Test
    void readOnly_doesNotMutateInstallmentOrLoan_andWorksWhenExpired() throws Exception {
        Loan loan = saveLoan(memberAId);
        LoanInstallment overdue = saveInstallment(
                loan, 1, today.minusDays(10), LoanInstallmentStatus.OVERDUE, "80000", "0");
        LoanStatus loanBefore = loan.getStatus();
        LoanInstallmentStatus instBefore = overdue.getStatus();

        expireTrial(cooperativeId);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        Loan loanAfter = loanRepository.findById(loan.getId()).orElseThrow();
        LoanInstallment instAfter = loanInstallmentRepository.findById(overdue.getId()).orElseThrow();
        assertThat(loanAfter.getStatus()).isEqualTo(loanBefore);
        assertThat(instAfter.getStatus()).isEqualTo(instBefore);
        assertThat(instAfter.getPrincipalPaid()).isEqualByComparingTo("0");
    }

    @Test
    void cooperativeIsolation() throws Exception {
        Loan loan = saveLoan(memberAId);
        for (int i = 1; i <= 3; i++) {
            LocalDate due = today.minusMonths(i);
            LoanInstallment inst = saveInstallment(loan, i, due, LoanInstallmentStatus.PAID, "10000", "10000");
            saveRepaymentWithAllocation(loan, memberAId, inst, due, "10000");
        }

        MvcResult other = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "Other Rel " + UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isOk())
                .andReturn();
        UUID otherCoop = UUID.fromString(objectMapper
                .readTree(other.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoop + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentReliability.members").isEmpty());
    }

    private Loan saveLoan(UUID memberId) {
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .requestedAmount(money("300000"))
                .approvedAmount(money("300000"))
                .principalAmount(money("300000"))
                .interestRatePercent(money("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(money("0"))
                .outstandingPrincipal(money("300000"))
                .outstandingInterest(money("0"))
                .requestDate(today.minusMonths(6))
                .disbursementDate(today.minusMonths(5))
                .dueDate(today.plusMonths(1))
                .status(LoanStatus.ACTIVE)
                .build());
    }

    private LoanInstallment saveInstallment(
            Loan loan,
            int number,
            LocalDate dueDate,
            LoanInstallmentStatus status,
            String due,
            String paid) {
        return loanInstallmentRepository.saveAndFlush(LoanInstallment.builder()
                .loanId(loan.getId())
                .cooperativeId(cooperativeId)
                .installmentNumber(number)
                .dueDate(dueDate)
                .openingPrincipalBalance(money(due))
                .principalDue(money(due))
                .interestDue(money("0"))
                .penaltyDue(money("0"))
                .principalPaid(money(paid))
                .interestPaid(money("0"))
                .penaltyPaid(money("0"))
                .scheduledInstallmentAmount(money(due))
                .status(status)
                .build());
    }

    private void saveRepaymentWithAllocation(
            Loan loan, UUID memberId, LoanInstallment installment, LocalDate paymentDate, String amount) {
        LoanRepayment repayment = loanRepaymentRepository.saveAndFlush(LoanRepayment.builder()
                .loanId(loan.getId())
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .paymentDate(paymentDate)
                .amountTotal(money(amount))
                .principalPortion(money(amount))
                .interestPortion(money("0"))
                .penaltyPortion(money("0"))
                .build());
        loanRepaymentAllocationRepository.saveAndFlush(LoanRepaymentAllocation.builder()
                .repaymentId(repayment.getId())
                .installmentId(installment.getId())
                .loanId(loan.getId())
                .cooperativeId(cooperativeId)
                .component(LoanRepaymentComponent.PRINCIPAL)
                .amount(money(amount))
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

    private MemberReg registerMember(String username, String first, String last, String role) throws Exception {
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "firstName":"%s",
                                  "lastName":"%s",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s",
                                  "shareCount": 1
                                }
                                """
                                        .formatted(first, last, username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        UUID userId = UUID.fromString(data.path("userId").asText());
        String password = data.path("temporaryPassword").asText();
        OpeningShareBalances.set(membershipRepository, cooperativeId, userId, 1);
        return new MemberReg(userId, password);
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

    private record MemberReg(UUID userId, String password) {}
}
