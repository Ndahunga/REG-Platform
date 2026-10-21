package rw.terimbere.csams.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
class LoanScheduleExportAndShareIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private String loanOfficerToken;
    private String memberToken;
    private UUID cooperativeId;
    private UUID otherCooperativeId;
    private UUID memberUserId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        cooperativeId = createCooperative("Export Coop ");
        otherCooperativeId = createCooperative("Other Export Coop ");

        String memberUsername = "exmember_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode memberData = registerMember(cooperativeId, memberUsername, "Jane", "Doe");
        memberUserId = UUID.fromString(memberData.path("userId").asText());
        memberToken = loginAccessToken(memberUsername, memberData.path("temporaryPassword").asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 1);

        String officerUsername = "exofficer_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode officer = registerMember(cooperativeId, officerUsername, "Sched", "Officer", "LOAN_OFFICER");
        loanOfficerToken = loginAccessToken(officerUsername, officer.path("temporaryPassword").asText());

        putSettings();
        fundGroup(400000.0000);
    }

    @Test
    void disbursedSchedulePdf_isValidAndOmitsLoanUuid() throws Exception {
        UUID loanId = createApproveDisburse("80000.0000", 2);

        MvcResult pdf = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId
                                + "/schedule/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/pdf")))
                .andExpect(header().string(
                        "Content-Disposition", org.hamcrest.Matchers.containsString("repayment-schedule-jane-doe.pdf")))
                .andReturn();

        byte[] bytes = pdf.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(200);
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        String latin1 = new String(bytes, StandardCharsets.ISO_8859_1);
        assertThat(latin1).doesNotContain(loanId.toString());
        assertThat(latin1).doesNotContain("Loan Id");
    }

    @Test
    void previewScheduleWithNullDisbursementFields_doesNotReturn500() throws Exception {
        UUID loanId = requestLoan("80000.0000", 3);

        MvcResult pdf = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId
                                + "/schedule/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/pdf")))
                .andReturn();

        byte[] bytes = pdf.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(200);
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain(loanId.toString());
    }

    @Test
    void scheduleXlsx_stillWorks() throws Exception {
        UUID loanId = createApproveDisburse("80000.0000", 1);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/schedule/export")
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Type",
                        org.hamcrest.Matchers.containsString(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
    }

    @Test
    void scheduleExport_unauthorizedAndWrongCooperative() throws Exception {
        UUID loanId = createApproveDisburse("80000.0000", 1);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/schedule/export")
                        .param("format", "pdf"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/loans/" + loanId + "/schedule/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void whatsappShare_disabledByDefault_andDoesNotLeakCredentials() throws Exception {
        UUID loanId = createApproveDisburse("80000.0000", 1);

        MvcResult status = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId
                                + "/schedule/whatsapp-status")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.phoneNumberId").doesNotExist())
                .andReturn();
        assertThat(status.getResponse().getContentAsString()).doesNotContain("WHATSAPP_ACCESS_TOKEN");
        assertThat(status.getResponse().getContentAsString().toLowerCase()).doesNotContain("bearer ");

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId
                                + "/schedule/share-whatsapp")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"0788123456\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("WhatsApp sharing is not configured"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void whatsappShare_unauthorizedInvalidPhoneAndWrongCooperative() throws Exception {
        UUID loanId = createApproveDisburse("80000.0000", 1);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId
                                + "/schedule/share-whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"0788123456\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId
                        + "/schedule/whatsapp-status"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/cooperatives/" + otherCooperativeId + "/loans/" + loanId
                                + "/schedule/share-whatsapp")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"0788123456\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId
                                + "/schedule/share-whatsapp")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"not-a-phone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a valid Rwandan mobile number"));
    }

    private UUID createCooperative(String prefix) throws Exception {
        String name = prefix + UUID.randomUUID().toString().substring(0, 8);
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(name, "5000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private JsonNode registerMember(UUID coopId, String username, String first, String last) throws Exception {
        return registerMember(coopId, username, first, last, "MEMBER");
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
        return objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
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
                                  "purpose": "Schedule export"
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

    private UUID createApproveDisburse(String amount, int termMonths) throws Exception {
        UUID loanId = requestLoan(amount, termMonths);
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
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
