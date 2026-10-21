package rw.terimbere.csams.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.subscription.SubscriptionClockTestSupport;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanApprovalMaturityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private LoanInstallmentRepository installmentRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionPricing subscriptionPricing;

    @MockBean
    private Clock clock;

    private String superAdminToken;
    private String loanOfficerToken;
    private UUID cooperativeId;
    private UUID memberUserId;

    @BeforeEach
    void setUp() throws Exception {
        freezeClock(LocalDate.of(2026, 1, 15));
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Maturity Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name, "5000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        cooperativeId = UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        String memberUsername = "mmember_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Maturity",
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
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 1);

        String officerUsername = "mofficer_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult officer = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Maturity",
                                  "lastName":"Officer",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"LOAN_OFFICER"
                                }
                                """.formatted(officerUsername, officerUsername)))
                .andExpect(status().isOk())
                .andReturn();
        loanOfficerToken = loginAccessToken(
                officerUsername,
                objectMapper
                        .readTree(officer.getResponse().getContentAsString())
                        .path("data")
                        .path("temporaryPassword")
                        .asText());

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 2.0000,
                                  "interestType": "FLAT",
                                  "maxLoanAmount": 1000000.0000,
                                  "maxTermMonths": 12,
                                  "minMembershipMonths": 0,
                                  "allowMemberRequests": true,
                                  "repaymentDateModel": "SAME_DAY_OF_MONTH",
                                  "lateFeeEnabled": false
                                }
                                """))
                .andExpect(status().isOk());
        fundGroup(400000.0000);
    }

    @Test
    void firstApprovalSucceedsWithoutDueDate() throws Exception {
        UUID loanId = requestLoan("300000.0000", 6);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + loanOfficerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_SECOND_APPROVAL"))
                .andExpect(jsonPath("$.data.dueDate").value(org.hamcrest.Matchers.nullValue()));

        Loan loan = loanRepository.findById(loanId).orElseThrow();
        assertThat(loan.getDueDate()).isNull();
    }

    @Test
    void secondApprovalSucceedsWithoutDueDate() throws Exception {
        UUID loanId = requestLoan("300000.0000", 6);
        approveFirst(loanId);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.dueDate").value(org.hamcrest.Matchers.nullValue()));

        Loan loan = loanRepository.findById(loanId).orElseThrow();
        assertThat(loan.getDueDate()).isNull();
    }

    @Test
    void officerDueDateIsIgnoredEvenWhenSent() throws Exception {
        UUID loanId = requestLoan("300000.0000", 6);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + loanOfficerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dueDate": "2020-01-01"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_SECOND_APPROVAL"));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dueDate": "2099-12-31"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.dueDate").value(org.hamcrest.Matchers.nullValue()));

        Loan beforeDisbursement = loanRepository.findById(loanId).orElseThrow();
        assertThat(beforeDisbursement.getDueDate()).isNull();

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.dueDate").value("2026-07-15"));

        List<LoanInstallment> installments =
                installmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loanId);
        assertThat(installments).isNotEmpty();
        LocalDate lastInstallmentDue = installments.get(installments.size() - 1).getDueDate();
        Loan afterDisbursement = loanRepository.findById(loanId).orElseThrow();
        assertThat(afterDisbursement.getDueDate()).isEqualTo(lastInstallmentDue);
        assertThat(afterDisbursement.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(afterDisbursement.getDueDate()).isNotEqualTo(LocalDate.of(2099, 12, 31));
    }

    @Test
    void reducingLoanUsesDisbursementPlusTermFallback() throws Exception {
        UUID loanId = requestLoan("300000.0000", 6);
        approveBoth(loanId);

        Loan loan = loanRepository.findById(loanId).orElseThrow();
        loan.setInterestType(InterestType.REDUCING);
        loan.setEqualInstallmentAmount(null);
        loan.setDueDate(null);
        loanRepository.saveAndFlush(loan);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.disbursementDate").value("2026-01-15"))
                .andExpect(jsonPath("$.data.dueDate").value("2026-07-15"));

        assertThat(installmentRepository.existsByLoanId(loanId)).isFalse();
        Loan disbursed = loanRepository.findById(loanId).orElseThrow();
        assertThat(disbursed.getDueDate()).isEqualTo(LocalDate.of(2026, 1, 15).plusMonths(6));
    }

    @Test
    void markOverdueStillUsesLoanDueDate() throws Exception {
        UUID loanId = requestLoan("300000.0000", 6);
        approveBoth(loanId);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        Loan loan = loanRepository.findById(loanId).orElseThrow();
        loan.setDueDate(LocalDate.of(2026, 1, 14));
        loanRepository.saveAndFlush(loan);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVERDUE"));
    }

    private UUID requestLoan(String amount, int termMonths) throws Exception {
        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": %s,
                                  "termMonths": %d,
                                  "purpose": "Maturity"
                                }
                                """.formatted(memberUserId, amount, termMonths)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private void approveFirst(UUID loanId) throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + loanOfficerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private void approveBoth(UUID loanId) throws Exception {
        approveFirst(loanId);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private void fundGroup(double amount) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": %s,
                                      "paymentDate": "2026-01-10",
                                      "paymentReference": "FUND"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId, BigDecimal.valueOf(amount).toPlainString())))
                .andExpect(status().isOk());
    }

    private void freezeClock(LocalDate date) {
        Instant instant = SubscriptionClockTestSupport.freeze(clock, date);
        SubscriptionClockTestSupport.keepUsable(
                subscriptionRepository, subscriptionPricing, cooperativeId, instant);
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
