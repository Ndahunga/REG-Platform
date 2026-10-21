package rw.terimbere.csams.modules.loan;

import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.mockito.Mockito;
import rw.terimbere.csams.modules.notification.channel.EmailNotificationPublisher;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private LoanService loanService;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @SpyBean
    private EmailNotificationPublisher emailNotificationPublisher;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberUsername;
    private String memberPassword;
    private UUID guarantorUserId;
    private String guarantorUsername;
    private String guarantorPassword;
    private String loanOfficerToken;
    private UUID officerUserId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Loan Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        memberUsername = "lmember_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Loan",
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

        guarantorUsername = "gmember_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult guarantor = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Gua",
                                  "lastName":"Rantor",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """.formatted(guarantorUsername, guarantorUsername)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode guarantorData =
                objectMapper.readTree(guarantor.getResponse().getContentAsString()).path("data");
        guarantorUserId = UUID.fromString(guarantorData.path("userId").asText());
        guarantorPassword = guarantorData.path("temporaryPassword").asText();
        OpeningShareBalances.set(membershipRepository, cooperativeId, guarantorUserId, 1);

        String officerUsername = "loofficer_" + UUID.randomUUID().toString().substring(0, 8);
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
        JsonNode officerData =
                objectMapper.readTree(officer.getResponse().getContentAsString()).path("data");
        officerUserId = UUID.fromString(officerData.path("userId").asText());
        loanOfficerToken = loginAccessToken(officerUsername, officerData.path("temporaryPassword").asText());

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 10.0000,
                                  "interestType": "FLAT",
                                  "maxLoanAmount": 100000.0000,
                                  "maxTermMonths": 12,
                                  "minMembershipMonths": 0,
                                  "allowMemberRequests": true,
                                  "lateFeeEnabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRatePercent").value(10.0));
    }

    @Test
    void requestApproveDisbursePartialAndFullRepayCloses() throws Exception {
        fundGroup(10000.0000);

        UUID loanId = requestAndApprove(2000.0000, 1);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.interestAmount").value(200.0))
                .andExpect(jsonPath("$.data.interestRatePercent").value(10.0));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.principalAmount").value(2000.0))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(2000.0))
                .andExpect(jsonPath("$.data.outstandingInterest").value(200.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(8000.0))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal").value(2000.0))
                .andExpect(jsonPath("$.data.totalLoanPrincipal").value(2000.0));

        // Partial: 300 — interest first → 200 interest + 100 principal
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300.0000,
                                  "paymentDate": "2026-08-01",
                                  "paymentReference": "RP-1",
                                  "allocateInterestFirst": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestPortion").value(200.0))
                .andExpect(jsonPath("$.data.principalPortion").value(100.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(1900.0))
                .andExpect(jsonPath("$.data.outstandingInterest").value(0.0));

        // Full remaining principal
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1900.0000,
                                  "paymentDate": "2026-08-02",
                                  "paymentReference": "RP-2"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(0.0))
                .andExpect(jsonPath("$.data.outstandingInterest").value(0.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                // Phase 8: availableInterest (loan interest earned) is included in available group fund
                .andExpect(jsonPath("$.data.availableGroupFunds").value(10200.0))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal").value(0.0))
                .andExpect(jsonPath("$.data.loanInterestEarned").value(200.0))
                .andExpect(jsonPath("$.data.availableInterest").value(200.0));
    }

    @Test
    void repaymentCannotExceedOutstanding() throws Exception {
        fundGroup(5000.0000);
        UUID loanId = requestApproveDisburse(1000.0000);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 2000.0000,
                                  "paymentDate": "2026-08-01"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void disburseBlockedWhenFundInsufficient() throws Exception {
        fundGroup(500.0000);
        UUID loanId = requestAndApprove(1000.0000, 6);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void interestSnapshotPreservedWhenSettingsChange() throws Exception {
        fundGroup(5000.0000);

        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": 1000.0000,
                                  "termMonths": 6,
                                  "purpose": "Snapshot test"
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRatePercent").value(10.0))
                .andReturn();
        UUID loanId = UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 25.0000,
                                  "interestType": "FLAT",
                                  "maxTermMonths": 12,
                                  "allowMemberRequests": true
                                }
                                """))
                .andExpect(status().isOk());

        completeTwoStepApproval(loanId);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRatePercent").value(10.0))
                .andExpect(jsonPath("$.data.interestAmount").value(600.0))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void coopIsolation_memberCannotAccessOtherCoopLoans() throws Exception {
        String otherName = "Other Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoopId + "/loans")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void overdueTransitionOnRefresh() throws Exception {
        fundGroup(5000.0000);
        UUID loanId = requestApproveDisburse(1000.0000);

        Loan loan = loanRepository.findById(loanId).orElseThrow();
        loan.setDueDate(LocalDate.now().minusDays(1));
        loanRepository.save(loan);

        loanService.refreshOverdueStatuses(cooperativeId);

        Loan refreshed = loanRepository.findById(loanId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(LoanStatus.OVERDUE);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OVERDUE"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdueLoansCount").value(1))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal").value(1000.0));
    }

    @Test
    void memberCanRequestOwnLoanAndListMy() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 500.0000,
                                  "termMonths": 3,
                                  "purpose": "Self request",
                                  "guarantorUserId": "%s",
                                  "guaranteedAmount": 500.0000
                                }
                                """.formatted(guarantorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.memberUserId").value(memberUserId.toString()));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/my")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    void memberCannotApproveLoan() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 500.0000,
                                  "termMonths": 3,
                                  "purpose": "Self request",
                                  "guarantorUserId": "%s",
                                  "guaranteedAmount": 500.0000
                                }
                                """.formatted(guarantorUserId)))
                .andExpect(status().isOk())
                .andReturn();
        UUID loanId = UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void guarantorMustAcceptBeforeApproval_andRejectBlocksWorkflow() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        String guarantorToken = loginAccessToken(guarantorUsername, guarantorPassword);

        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 700.0000,
                                  "termMonths": 3,
                                  "purpose": "Guarantor test",
                                  "guarantorUserId": "%s",
                                  "guaranteedAmount": 700.0000
                                }
                                """.formatted(guarantorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guarantor.status").value("PENDING"))
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
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/loans/"
                                + loanId
                                + "/guarantor/respond")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":true}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/loans/"
                                + loanId
                                + "/guarantor/respond")
                        .header("Authorization", "Bearer " + guarantorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/loans/"
                                + loanId
                                + "/guarantor/respond")
                        .header("Authorization", "Bearer " + guarantorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":true}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void outstandingLoanBlocksNewRequest() throws Exception {
        fundGroup(5000.0000);
        requestApproveDisburse(1000.0000);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": 400.0000,
                                  "termMonths": 3,
                                  "purpose": "Second loan"
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void memberCanRequestOwnLoanWithoutGuarantor() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 500.0000,
                                  "termMonths": 3,
                                  "purpose": "Own loan",
                                  "guaranteeMode": "SELF"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.guaranteeMode").value("SELF"))
                .andExpect(jsonPath("$.data.guarantor").doesNotExist());
    }

    @Test
    void guaranteedLoanRequiresGuarantorDetails() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 500.0000,
                                  "termMonths": 3,
                                  "purpose": "Needs guarantor",
                                  "guaranteeMode": "GUARANTOR"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sharePercentTiersCapLoanAmount() throws Exception {
        OpeningShareBalances.set(membershipRepository, cooperativeId, memberUserId, 4);
        OpeningShareBalances.set(membershipRepository, cooperativeId, guarantorUserId, 2);
        OpeningShareBalances.set(membershipRepository, cooperativeId, officerUserId, 94);

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 10.0000,
                                  "interestType": "FLAT",
                                  "maxLoanAmount": 100000.0000,
                                  "maxTermMonths": 12,
                                  "minMembershipMonths": 0,
                                  "allowMemberRequests": true,
                                  "lateFeeEnabled": false,
                                  "shareTiers": [
                                    {"minSharePercent": 4.0000, "maxLoanAmount": 20000.0000},
                                    {"minSharePercent": 2.0000, "maxLoanAmount": 3000.0000}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareTiers.length()").value(2));

        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 20000.0000,
                                  "termMonths": 3,
                                  "purpose": "Four percent level",
                                  "guaranteeMode": "SELF"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guaranteeMode").value("SELF"))
                .andExpect(jsonPath("$.data.shareCount").value(4));

        String guarantorToken = loginAccessToken(guarantorUsername, guarantorPassword);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + guarantorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 20000.0000,
                                  "termMonths": 3,
                                  "purpose": "Too high for two percent",
                                  "guaranteeMode": "SELF"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + guarantorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 3000.0000,
                                  "termMonths": 3,
                                  "purpose": "Two percent level",
                                  "guaranteeMode": "SELF"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(2))
                .andExpect(jsonPath("$.data.maxLoanByShares").value(3000.0));
    }

    @Test
    void loanOfficerReject_notifiesRequesterWithRole() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 400.0000,
                                  "termMonths": 3,
                                  "purpose": "Reject notify",
                                  "guaranteeMode": "SELF"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID loanId = UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        Mockito.clearInvocations(emailNotificationPublisher);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/reject")
                        .header("Authorization", "Bearer " + loanOfficerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectionReason\":\"Insufficient savings\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        MvcResult list = mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper
                .readTree(list.getResponse().getContentAsString())
                .path("data")
                .path("content");
        boolean found = false;
        for (JsonNode notification : content) {
            if ("Loan rejected".equals(notification.path("title").asText())) {
                assertThat(notification.path("type").asText()).isEqualTo("LOAN");
                assertThat(notification.path("entityType").asText()).isEqualTo("Loan");
                assertThat(notification.path("entityId").asText()).isEqualTo(loanId.toString());
                assertThat(notification.path("body").asText()).contains("LOAN_OFFICER");
                found = true;
            }
        }
        assertThat(found).isTrue();
        Mockito.verify(emailNotificationPublisher)
                .publish(
                        Mockito.eq(memberUserId),
                        Mockito.eq(cooperativeId),
                        Mockito.eq(NotificationType.LOAN),
                        Mockito.eq("Loan rejected"),
                        Mockito.any(),
                        Mockito.eq("Loan"),
                        Mockito.eq(loanId));
    }

    @Test
    void firstApprovalDoesNotNotifyFinalApproval_secondApprovalDoes() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 400.0000,
                                  "termMonths": 3,
                                  "purpose": "Two step notify",
                                  "guaranteeMode": "SELF"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID loanId = UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        Mockito.clearInvocations(emailNotificationPublisher);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + loanOfficerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_SECOND_APPROVAL"));
        Mockito.verify(emailNotificationPublisher, Mockito.never())
                .publish(
                        Mockito.eq(memberUserId),
                        Mockito.eq(cooperativeId),
                        Mockito.eq(NotificationType.LOAN),
                        Mockito.eq("Loan approved"),
                        Mockito.any(),
                        Mockito.eq("Loan"),
                        Mockito.eq(loanId));

        MvcResult afterFirst = mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstContent = objectMapper
                .readTree(afterFirst.getResponse().getContentAsString())
                .path("data")
                .path("content");
        for (JsonNode notification : firstContent) {
            assertThat(notification.path("title").asText()).isNotEqualTo("Loan approved");
        }

        mockMvc.perform(get("/api/v1/notifications/pending-approvals")
                        .header("Authorization", "Bearer " + loanOfficerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanSecondApprovalCount").value(0));

        mockMvc.perform(get("/api/v1/notifications/pending-approvals")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanSecondApprovalCount")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        Mockito.clearInvocations(emailNotificationPublisher);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        Mockito.verify(emailNotificationPublisher)
                .publish(
                        Mockito.eq(memberUserId),
                        Mockito.eq(cooperativeId),
                        Mockito.eq(NotificationType.LOAN),
                        Mockito.eq("Loan approved"),
                        Mockito.any(),
                        Mockito.eq("Loan"),
                        Mockito.eq(loanId));

        MvcResult afterSecond = mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondContent = objectMapper
                .readTree(afterSecond.getResponse().getContentAsString())
                .path("data")
                .path("content");
        boolean foundApproved = false;
        for (JsonNode notification : secondContent) {
            if ("Loan approved".equals(notification.path("title").asText())) {
                assertThat(notification.path("type").asText()).isEqualTo("LOAN");
                assertThat(notification.path("entityId").asText()).isEqualTo(loanId.toString());
                foundApproved = true;
            }
        }
        assertThat(foundApproved).isTrue();
    }

    @Test
    void accountantCannotChangeShareTiers() throws Exception {
        String accountantUsername = "acc_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult accountant = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Acc",
                                  "lastName":"Ountant",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"ACCOUNTANT"
                                }
                                """.formatted(accountantUsername, accountantUsername)))
                .andExpect(status().isOk())
                .andReturn();
        String accountantToken = loginAccessToken(
                accountantUsername,
                objectMapper
                        .readTree(accountant.getResponse().getContentAsString())
                        .path("data")
                        .path("temporaryPassword")
                        .asText());

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 10.0000,
                                  "interestType": "FLAT",
                                  "maxTermMonths": 12,
                                  "allowMemberRequests": true,
                                  "shareTiers": [
                                    {"minSharePercent": 4.0000, "maxLoanAmount": 20000.0000}
                                  ]
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 11.0000,
                                  "interestType": "FLAT",
                                  "maxTermMonths": 12,
                                  "allowMemberRequests": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRatePercent").value(11.0));
    }

    @Test
    void repaymentPreviewMatchesFlatAndProrataExamples() throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 2.0000,
                                  "interestType": "FLAT",
                                  "maxLoanAmount": 1000000.0000,
                                  "maxTermMonths": 12,
                                  "allowMemberRequests": true
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/repayment-preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300000.0000,
                                  "termMonths": 5,
                                  "referenceDate": "2026-01-15"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentDateModel").value("SAME_DAY_OF_MONTH"))
                .andExpect(jsonPath("$.data.regularMonthlyInterest").value(6000.0))
                .andExpect(jsonPath("$.data.totalInterest").value(30000.0))
                .andExpect(jsonPath("$.data.totalRepayment").value(330000.0))
                .andExpect(jsonPath("$.data.equalInstallmentAmount").value(66000.0))
                .andExpect(jsonPath("$.data.scheduleFinalized").value(false))
                .andExpect(jsonPath("$.data.installments[0].dueDate").value("2026-02-15"));

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 2.0000,
                                  "interestType": "FLAT",
                                  "maxLoanAmount": 1000000.0000,
                                  "maxTermMonths": 12,
                                  "allowMemberRequests": true,
                                  "repaymentDateModel": "MONTH_END"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/repayment-preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300000.0000,
                                  "termMonths": 5,
                                  "referenceDate": "2026-01-15"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentDateModel").value("MONTH_END"))
                .andExpect(jsonPath("$.data.firstPeriodDays").value(16))
                .andExpect(jsonPath("$.data.firstPeriodInterest").value(3096.77))
                .andExpect(jsonPath("$.data.scheduleFinalized").value(false))
                .andExpect(jsonPath("$.data.installments[0].dueDate").value("2026-01-31"));
    }

    @Test
    void prorataLoanPersistsContractAndDoesNotTreatPrincipalAsIncome() throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestRatePercent": 2.0000,
                                  "interestType": "FLAT",
                                  "maxLoanAmount": 1000000.0000,
                                  "maxTermMonths": 12,
                                  "allowMemberRequests": true,
                                  "repaymentDateModel": "MONTH_END"
                                }
                                """))
                .andExpect(status().isOk());
        fundGroup(400000.0000);

        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": 300000.0000,
                                  "termMonths": 5,
                                  "purpose": "Prorata business"
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prorataEnabled").value(true))
                .andExpect(jsonPath("$.data.repaymentDateModel").value("MONTH_END"))
                .andExpect(jsonPath("$.data.scheduleFinalized").value(false))
                .andReturn();
        UUID loanId = UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        completeTwoStepApproval(loanId);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prorataEnabled").value(true))
                .andExpect(jsonPath("$.data.scheduleFinalized").value(false));

        MvcResult disburseResult = mockMvc.perform(
                        post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                                .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(300000.0))
                .andExpect(jsonPath("$.data.scheduleFinalized").value(true))
                .andReturn();
        JsonNode disbursed = objectMapper
                .readTree(disburseResult.getResponse().getContentAsString())
                .path("data");
        LocalDate today = LocalDate.now();
        int expectedDays = (int) java.time.temporal.ChronoUnit.DAYS.between(
                today, today.withDayOfMonth(today.lengthOfMonth()));
        assertThat(disbursed.path("firstPeriodDays").asInt()).isEqualTo(expectedDays);
        assertThat(disbursed.path("repaymentDateModel").asText()).isEqualTo("MONTH_END");
        BigDecimal firstInterest = new BigDecimal(disbursed.path("repaymentSchedule").get(0)
                .path("interestComponent")
                .asText());
        BigDecimal firstPrincipal = new BigDecimal(disbursed.path("repaymentSchedule").get(0)
                .path("principalComponent")
                .asText());
        BigDecimal totalInterest = new BigDecimal(disbursed.path("outstandingInterest").asText());
        assertThat(firstInterest).isEqualByComparingTo(
                new BigDecimal("300000").multiply(new BigDecimal("0.02"))
                        .multiply(BigDecimal.valueOf(expectedDays))
                        .divide(BigDecimal.valueOf(today.lengthOfMonth()), 2, java.math.RoundingMode.HALF_UP));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(100000.0))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal").value(300000.0));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": %s,
                                  "paymentDate": "2026-08-01"
                                }
                                """.formatted(firstInterest.toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestPortion").value(firstInterest.doubleValue()))
                .andExpect(jsonPath("$.data.principalPortion").value(0.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds")
                        .value(new BigDecimal("100000").add(firstInterest).doubleValue()))
                .andExpect(jsonPath("$.data.loanInterestEarned").value(firstInterest.doubleValue()))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal").value(300000.0));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": %s,
                                  "paymentDate": "2026-08-02"
                                }
                                """.formatted(firstPrincipal.toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalPortion").value(firstPrincipal.doubleValue()))
                .andExpect(jsonPath("$.data.interestPortion").value(0.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outstandingPrincipal")
                        .value(new BigDecimal("300000").subtract(firstPrincipal).doubleValue()))
                .andExpect(jsonPath("$.data.outstandingInterest")
                        .value(totalInterest.subtract(firstInterest).doubleValue()));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds")
                        .value(new BigDecimal("100000").add(firstPrincipal).add(firstInterest).doubleValue()))
                .andExpect(jsonPath("$.data.loanInterestEarned").value(firstInterest.doubleValue()))
                .andExpect(jsonPath("$.data.outstandingLoanPrincipal")
                        .value(new BigDecimal("300000").subtract(firstPrincipal).doubleValue()));
    }

    @Test
    void zeroInstallmentsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/repayment-preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300000.0000,
                                  "termMonths": 0
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void memberDetailIncludesLoans() throws Exception {
        fundGroup(3000.0000);
        UUID loanId = requestApproveDisburse(500.0000);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + memberUserId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loans[0].id").value(loanId.toString()))
                .andExpect(jsonPath("$.data.loans[0].status").value("ACTIVE"));
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

    private UUID requestAndApprove(double amount, int termMonths) throws Exception {
        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": %s,
                                  "termMonths": %d,
                                  "purpose": "Business"
                                }
                                """.formatted(
                                memberUserId,
                                BigDecimal.valueOf(amount).toPlainString(),
                                termMonths)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_SECOND_APPROVAL"))
                .andExpect(jsonPath("$.data.approvalHistory.length()").value(2));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + loanOfficerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvalHistory.length()").value(3));
        return loanId;
    }

    private void completeTwoStepApproval(UUID loanId) throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + loanOfficerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_SECOND_APPROVAL"));
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    private UUID requestApproveDisburse(double amount) throws Exception {
        UUID loanId = requestAndApprove(amount, 6);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
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
