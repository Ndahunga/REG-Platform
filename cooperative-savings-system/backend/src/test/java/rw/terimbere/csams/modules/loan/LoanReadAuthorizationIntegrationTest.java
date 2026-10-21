package rw.terimbere.csams.modules.loan;

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
class LoanReadAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private String memberAToken;
    private String memberBToken;
    private String loanOfficerToken;
    private String presidentToken;
    private String treasurerToken;
    private UUID cooperativeId;
    private UUID otherCooperativeId;
    private UUID memberAId;
    private UUID memberBId;
    private UUID loanAId;
    private UUID loanBId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        cooperativeId = createCooperative("ReadScope Coop ");
        otherCooperativeId = createCooperative("ReadScope Other ");

        JsonNode memberA = registerMember(cooperativeId, unique("mema"), "Alice", "Owner", "MEMBER");
        memberAId = UUID.fromString(memberA.path("userId").asText());
        memberAToken = loginAccessToken(memberA.path("username").asText(), memberA.path("temporaryPassword").asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberAId, 1);

        JsonNode memberB = registerMember(cooperativeId, unique("memb"), "Bob", "Borrower", "MEMBER");
        memberBId = UUID.fromString(memberB.path("userId").asText());
        memberBToken = loginAccessToken(memberB.path("username").asText(), memberB.path("temporaryPassword").asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberBId, 1);

        JsonNode officer = registerMember(cooperativeId, unique("loff"), "Loan", "Officer", "LOAN_OFFICER");
        loanOfficerToken = loginAccessToken(officer.path("username").asText(), officer.path("temporaryPassword").asText());

        JsonNode president = registerMember(cooperativeId, unique("pres"), "Pat", "President", "PRESIDENT");
        presidentToken = loginAccessToken(
                president.path("username").asText(), president.path("temporaryPassword").asText());

        JsonNode treasurer = registerMember(cooperativeId, unique("treas"), "Tess", "Treasurer", "ACCOUNTANT");
        treasurerToken = loginAccessToken(
                treasurer.path("username").asText(), treasurer.path("temporaryPassword").asText());

        putSettings();
        fundGroup();
        loanAId = createApproveDisburse(memberAId, "40000.0000", 1);
        loanBId = createApproveDisburse(memberBId, "50000.0000", 1);
    }

    @Test
    void ordinaryMember_canReadOwnLoanAndNotAnotherMembers() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanAId)
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(loanAId.toString()))
                .andExpect(jsonPath("$.data.memberUserId").value(memberAId.toString()));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId)
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void ordinaryMember_canExportOwnScheduleButNotAnotherMembers() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanAId + "/schedule/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId + "/schedule/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId + "/schedule/export")
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void ordinaryMember_cannotShareOrStatusAnotherMembersSchedule() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId
                                + "/schedule/whatsapp-status")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId
                                + "/schedule/share-whatsapp")
                        .header("Authorization", "Bearer " + memberAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"0788123456\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ordinaryMember_ownWhatsAppSharePassesReadGate() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanAId
                                + "/schedule/whatsapp-status")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanAId
                                + "/schedule/share-whatsapp")
                        .header("Authorization", "Bearer " + memberAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"0788123456\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("WhatsApp sharing is not configured"));
    }

    @Test
    void ordinaryMember_listIsSelfScopedAndIgnoresForeignMemberFilter() throws Exception {
        MvcResult listed = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(loanAId.toString()))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(listed.getResponse().getContentAsString())
                .doesNotContain(loanBId.toString());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .param("memberUserId", memberBId.toString())
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].memberUserId").value(memberAId.toString()));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/my")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(loanAId.toString()));
    }

    @Test
    void ordinaryMember_canListOwnRepaymentsButNotAnotherMembers() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanAId + "/repayments")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId + "/repayments")
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void officersAndSuperAdmin_retainCooperativeWideRead() throws Exception {
        for (String token : new String[] {presidentToken, loanOfficerToken, treasurerToken, superAdminToken}) {
            mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(loanBId.toString()));

            mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId + "/schedule/export")
                            .param("format", "pdf")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId + "/repayments")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + presidentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanBId
                                + "/schedule/share-whatsapp")
                        .header("Authorization", "Bearer " + presidentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"0788123456\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("WhatsApp sharing is not configured"));
    }

    @Test
    void otherCooperativeRemainsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/loans/" + loanBId)
                        .header("Authorization", "Bearer " + memberAToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/loans/" + loanBId)
                        .header("Authorization", "Bearer " + presidentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/loans/" + loanBId + "/schedule/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + loanOfficerToken))
                .andExpect(status().isForbidden());
    }

    private String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID createCooperative(String prefix) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(prefix + UUID.randomUUID().toString().substring(0, 8), "5000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private JsonNode registerMember(UUID coopId, String username, String first, String last, String role)
            throws Exception {
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"%s",
                                  "lastName":"%s",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s",
                                  "shareCount": 1
                                }
                                """.formatted(first, last, username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        return objectMapper.createObjectNode()
                .put("userId", data.path("userId").asText())
                .put("username", username)
                .put("temporaryPassword", data.path("temporaryPassword").asText());
    }

    private UUID createApproveDisburse(UUID memberUserId, String amount, int termMonths) throws Exception {
        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": %s,
                                  "termMonths": %d,
                                  "purpose": "Read scope"
                                }
                                """.formatted(memberUserId, amount, termMonths)))
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
                        .header("Authorization", "Bearer " + presidentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + treasurerToken))
                .andExpect(status().isOk());
        return loanId;
    }

    private void putSettings() throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestType": "FLAT",
                                  "interestRatePercent": 2.0000,
                                  "maxLoanAmount": 1000000.0000,
                                  "maxTermMonths": 12,
                                  "minMembershipMonths": 0,
                                  "allowMemberRequests": true,
                                  "repaymentDateModel": "SAME_DAY_OF_MONTH",
                                  "loanPenaltyEnabled": false
                                }
                                """))
                .andExpect(status().isOk());
    }

    private void fundGroup() throws Exception {
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
                                      "paidAmount": 200000.0000,
                                      "paymentDate": "2026-01-10",
                                      "paymentReference": "FUNDA"
                                    },
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": 200000.0000,
                                      "paymentDate": "2026-01-10",
                                      "paymentReference": "FUNDB"
                                    }
                                  ]
                                }
                                """.formatted(memberAId, memberBId)))
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
