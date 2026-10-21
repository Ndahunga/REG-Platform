package rw.terimbere.csams.modules.share;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShareValuationAccountingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberToken;
    private String loanOfficerToken;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        String name = "Share Acct " + UUID.randomUUID().toString().substring(0, 8);
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

        String username = "sva_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Val",
                                  "lastName":"Member",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode member = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        memberUserId = UUID.fromString(member.path("userId").asText());
        memberToken = loginAccessToken(username, member.path("temporaryPassword").asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 5);

        String officerUsername = "sva_lo_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult officer = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Loan",
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
    }

    @Test
    void incomeIncreasesShareValueAndExpenseReducesItWithoutIssuingShares() throws Exception {
        fundGroup("500000.0000");
        expectValuation("500000.0", "0.0", "0.0", "0.0", "0.0", "500000.0", 5, "100000.0");

        UUID incomeId = createTransaction("OTHER_INCOME", "120000.0000");
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/transactions/" + incomeId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
        expectValuation("620000.0", "0.0", "0.0", "0.0", "0.0", "620000.0", 5, "124000.0");

        UUID pendingExpense = createTransaction("GENERAL_EXPENSE", "100000.0000");
        expectValuation("620000.0", "0.0", "0.0", "0.0", "0.0", "620000.0", 5, "124000.0");
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/transactions/" + pendingExpense + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
        expectValuation("520000.0", "0.0", "0.0", "0.0", "0.0", "520000.0", 5, "104000.0");
    }

    @Test
    void assessedPenaltyThenPaymentDoesNotDoubleCount() throws Exception {
        fundGroup("500000.0000");
        UUID fineId = createManualFine("50000.0000");
        expectValuation("500000.0", "0.0", "0.0", "50000.0", "0.0", "550000.0", 5, "110000.0");

        MvcResult submit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines/" + fineId
                                + "/payments")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 50000.0000,
                                  "paymentDate": "2026-08-01",
                                  "paymentMethod": "CASH"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID paymentId = UUID.fromString(objectMapper
                .readTree(submit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines/" + fineId + "/payments/"
                                + paymentId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        expectValuation("550000.0", "0.0", "0.0", "0.0", "0.0", "550000.0", 5, "110000.0");
    }

    @Test
    void loanDisbursementTransfersCashToReceivable_andPrincipalRepaymentIsNotProfit() throws Exception {
        fundGroup("1000000.0000");
        configureLoanSettings();
        expectValuation("1000000.0", "0.0", "0.0", "0.0", "0.0", "1000000.0", 5, "200000.0");

        UUID loanId = requestApproveAndDisburse("100000.0000");
        // Principal is an asset swap; FLAT interest is booked as a receivable at disbursement.
        expectValuation("900000.0", "100000.0", "10000.0", "0.0", "0.0", "1010000.0", 5, "202000.0");

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 40000.0000,
                                  "paymentDate": "2026-08-01",
                                  "allocateInterestFirst": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalPortion").value(30000.0))
                .andExpect(jsonPath("$.data.interestPortion").value(10000.0));

        expectValuation("940000.0", "70000.0", "0.0", "0.0", "0.0", "1010000.0", 5, "202000.0");

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 10000.0000,
                                  "paymentDate": "2026-08-02",
                                  "allocateInterestFirst": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalPortion").value(10000.0));

        expectValuation("950000.0", "60000.0", "0.0", "0.0", "0.0", "1010000.0", 5, "202000.0");
    }

    @Test
    void investmentActivationTransfersCashToOtherAssets() throws Exception {
        fundGroup("500000.0000");
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Asset",
                                  "description": "Test",
                                  "amount": 100000.0000
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID investmentId = UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId + "/activate")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
        expectValuation("400000.0", "0.0", "0.0", "0.0", "100000.0", "500000.0", 5, "100000.0");
    }

    private void expectValuation(
            String funds,
            String loans,
            String interest,
            String penalties,
            String assets,
            String total,
            int shares,
            String shareValue)
            throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableFunds").value(Double.parseDouble(funds)))
                .andExpect(jsonPath("$.data.outstandingLoans").value(Double.parseDouble(loans)))
                .andExpect(jsonPath("$.data.unpaidInterest").value(Double.parseDouble(interest)))
                .andExpect(jsonPath("$.data.unpaidPenalties").value(Double.parseDouble(penalties)))
                .andExpect(jsonPath("$.data.otherAssets").value(Double.parseDouble(assets)))
                .andExpect(jsonPath("$.data.liabilities").value(0.0))
                .andExpect(jsonPath("$.data.totalIkiminaValue").value(Double.parseDouble(total)))
                .andExpect(jsonPath("$.data.totalExistingShares").value(shares))
                .andExpect(jsonPath("$.data.currentShareValue").value(Double.parseDouble(shareValue)));
    }

    private void fundGroup(String amount) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId":"%s",
                                      "paidAmount":%s,
                                      "paymentDate":"2026-01-15"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId, amount)))
                .andExpect(status().isOk());
    }

    private UUID createTransaction(String category, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/transactions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"%s",
                                  "amount":%s,
                                  "transactionDate":"2026-08-01",
                                  "description":"valuation test"
                                }
                                """.formatted(category, amount)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private UUID createManualFine(String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId":"%s",
                                  "amount":%s,
                                  "reason":"Late payment"
                                }
                                """.formatted(memberUserId, amount)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private void configureLoanSettings() throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 10.0000,
                                  "interestType": "FLAT",
                                  "maxLoanAmount": 1000000.0000,
                                  "maxTermMonths": 12,
                                  "minMembershipMonths": 0,
                                  "allowMemberRequests": true,
                                  "lateFeeEnabled": false
                                }
                                """))
                .andExpect(status().isOk());
    }

    private UUID requestApproveAndDisburse(String amount) throws Exception {
        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId":"%s",
                                  "amount":%s,
                                  "termMonths":1,
                                  "purpose":"Business"
                                }
                                """.formatted(memberUserId, amount)))
                .andExpect(status().isOk())
                .andReturn();
        UUID loanId = UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + loanOfficerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
        return loanId;
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
