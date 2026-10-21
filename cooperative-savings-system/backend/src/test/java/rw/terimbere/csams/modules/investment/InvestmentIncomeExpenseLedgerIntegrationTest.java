package rw.terimbere.csams.modules.investment;

import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvestmentIncomeExpenseLedgerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private UUID otherCooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        cooperativeId = createCooperative("Inv Coop " + UUID.randomUUID().toString().substring(0, 8));
        otherCooperativeId = createCooperative("Other Coop " + UUID.randomUUID().toString().substring(0, 8));

        String memberUsername = "imember_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Inv",
                                  "lastName":"Member",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """.formatted(memberUsername, memberUsername)))
                .andExpect(status().isOk())
                .andReturn();
        memberUserId = UUID.fromString(objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 1);
    }

    @Test
    void activateReducesFundCapitalReturnAndProfitIncreaseFundAndInterest() throws Exception {
        fundGroup(10000.0000);

        UUID investmentId = createInvestment(3000.0000);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId + "/activate")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.remainingCapital").value(3000.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(7000.0))
                .andExpect(jsonPath("$.data.activeInvestmentsCount").value(1))
                .andExpect(jsonPath("$.data.investmentCapital").value(3000.0));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId + "/returns")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "returnDate": "2026-08-01",
                                  "capitalPortion": 1000.0000,
                                  "profitPortion": 200.0000,
                                  "reference": "RET-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capitalPortion").value(1000.0))
                .andExpect(jsonPath("$.data.profitPortion").value(200.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.data.remainingCapital").value(2000.0));

        // fund = 10000 + capitalReturn 1000 + profit 200 − outflow 3000 = 8200
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(8200.0))
                .andExpect(jsonPath("$.data.investmentCapital").value(2000.0))
                .andExpect(jsonPath("$.data.investmentProfits").value(200.0))
                .andExpect(jsonPath("$.data.availableInterest").value(200.0));

        assertThat(ledgerEntryRepository
                        .findBySourceEntityTypeAndSourceEntityIdAndStatus(
                                "INVESTMENT",
                                investmentId,
                                rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED)
                        .stream()
                        .anyMatch(e -> e.getTransactionType() == LedgerTransactionType.INVESTMENT_OUTFLOW))
                .isTrue();
    }

    @Test
    void cannotActivateInvestmentExceedingFund() throws Exception {
        fundGroup(1000.0000);
        UUID investmentId = createInvestment(5000.0000);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId + "/activate")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("insufficient")));
    }

    @Test
    void expenseApproveReducesFundIncomeIncreases() throws Exception {
        fundGroup(5000.0000);

        UUID expenseId = createTransaction("GENERAL_EXPENSE", 500.0000, null);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/transactions/" + expenseId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvalStatus").value("APPROVED"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(4500.0))
                .andExpect(jsonPath("$.data.generalExpensesTotal").value(500.0));

        UUID incomeId = createTransaction("OTHER_INCOME", 300.0000, null);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/transactions/" + incomeId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(4800.0))
                .andExpect(jsonPath("$.data.otherIncomeTotal").value(300.0));
    }

    @Test
    void approvedTransactionCannotBeEditedOrReApproved() throws Exception {
        fundGroup(2000.0000);
        UUID txId = createTransaction("GENERAL_EXPENSE", 100.0000, null);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/transactions/" + txId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        // No PATCH/PUT update endpoint exists; re-approve of APPROVED is rejected.
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/transactions/" + txId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PENDING")));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/transactions/" + txId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.amount").value(100.0));
    }

    @Test
    void ledgerListIsolatedByCooperative() throws Exception {
        fundGroup(1000.0000);
        UUID investmentId = createInvestment(200.0000);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId + "/activate")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        MvcResult listA = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/ledger")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("transactionType", "INVESTMENT_OUTFLOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andReturn();

        UUID entryId = UUID.fromString(objectMapper
                .readTree(listA.getResponse().getContentAsString())
                .path("data")
                .path("content")
                .get(0)
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/ledger")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("transactionType", "INVESTMENT_OUTFLOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/ledger/" + entryId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void fullCapitalReturnCompletesInvestment() throws Exception {
        fundGroup(5000.0000);
        UUID investmentId = createInvestment(1000.0000);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId + "/activate")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId + "/returns")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "returnDate": "2026-08-02",
                                  "capitalPortion": 1000.0000,
                                  "profitPortion": 0
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.remainingCapital").value(0.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(5000.0))
                .andExpect(jsonPath("$.data.activeInvestmentsCount").value(0));
    }

    private UUID createCooperative(String name) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private UUID createInvestment(double amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Agri project",
                                  "description": "Test investment",
                                  "amount": %s
                                }
                                """.formatted(BigDecimal.valueOf(amount).toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PLANNED"))
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private UUID createTransaction(String category, double amount, String ledgerEffect) throws Exception {
        String effectJson = ledgerEffect == null ? "" : ", \"ledgerEffect\": \"" + ledgerEffect + "\"";
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/transactions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "%s",
                                  "amount": %s,
                                  "transactionDate": "2026-08-01",
                                  "description": "test tx"%s
                                }
                                """.formatted(
                                category, BigDecimal.valueOf(amount).toPlainString(), effectJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvalStatus").value("PENDING"))
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private void fundGroup(double amount) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
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
