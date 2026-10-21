package rw.terimbere.csams.modules.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.repository.NotificationRepository;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationFacade notificationFacade;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID superAdminUserId;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(login.getResponse().getContentAsString()).path("data");
        superAdminToken = data.path("accessToken").asText();
        superAdminUserId = UUID.fromString(data.path("user").path("id").asText());
    }

    @Test
    void createMarkReadAndUnreadCount() throws Exception {
        notificationFacade.notifyUser(
                superAdminUserId,
                null,
                NotificationType.SYSTEM,
                "Phase 11 test",
                "Hello from facade",
                "System",
                null);

        assertThat(notificationRepository.countByUserIdAndReadFalse(superAdminUserId)).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        MvcResult list = mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("Phase 11 test"))
                .andExpect(jsonPath("$.data.content[0].read").value(false))
                .andReturn();

        UUID notificationId = UUID.fromString(objectMapper
                .readTree(list.getResponse().getContentAsString())
                .path("data")
                .path("content")
                .get(0)
                .path("id")
                .asText());

        mockMvc.perform(patch("/api/v1/notifications/" + notificationId + "/read")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));

        mockMvc.perform(post("/api/v1/notifications/read-all")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void securityHeadersPresentOnAuthenticatedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void userCannotReadOrMarkAnotherUsersNotification() throws Exception {
        notificationFacade.notifyUser(
                superAdminUserId,
                null,
                NotificationType.SYSTEM,
                "Owner only",
                "Secret",
                "System",
                null);

        MvcResult list = mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID notificationId = UUID.fromString(objectMapper
                .readTree(list.getResponse().getContentAsString())
                .path("data")
                .path("content")
                .get(0)
                .path("id")
                .asText());

        CoopUsers users = createCoopWithRoles();

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + users.memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id=='" + notificationId + "')]").isEmpty());

        mockMvc.perform(patch("/api/v1/notifications/" + notificationId + "/read")
                        .header("Authorization", "Bearer " + users.memberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void pendingApprovals_onlyIncludeQueuesCurrentUserCanActOn() throws Exception {
        CoopUsers users = createCoopWithRoles();

        mockMvc.perform(post("/api/v1/cooperatives/" + users.cooperativeId + "/contributions/submissions")
                        .header("Authorization", "Bearer " + users.memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1000.0000,
                                  "paymentDate": "2026-05-05",
                                  "paymentReference": "MOMO-PENDING"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cooperatives/" + users.cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + users.memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 400.0000,
                                  "termMonths": 3,
                                  "purpose": "Pending queue",
                                  "guaranteeMode": "SELF"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications/pending-approvals")
                        .header("Authorization", "Bearer " + users.memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contributionPendingCount").value(0))
                .andExpect(jsonPath("$.data.loanPendingCount").value(0))
                .andExpect(jsonPath("$.data.loanSecondApprovalCount").value(0));

        mockMvc.perform(get("/api/v1/notifications/pending-approvals")
                        .header("Authorization", "Bearer " + users.accountantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contributionPendingCount").value(1))
                .andExpect(jsonPath("$.data.loanPendingCount").value(0))
                .andExpect(jsonPath("$.data.loanSecondApprovalCount").value(0));

        mockMvc.perform(get("/api/v1/notifications/pending-approvals")
                        .header("Authorization", "Bearer " + users.loanOfficerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contributionPendingCount").value(0))
                .andExpect(jsonPath("$.data.loanPendingCount").value(1))
                .andExpect(jsonPath("$.data.loanSecondApprovalCount").value(0));

        mockMvc.perform(get("/api/v1/notifications/pending-approvals")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contributionPendingCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.loanPendingCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    private CoopUsers createCoopWithRoles() throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures.createBody(
                                "Notify Coop " + UUID.randomUUID().toString().substring(0, 8),
                                "1000.0000",
                                1)))
                .andExpect(status().isOk())
                .andReturn();
        UUID cooperativeId = UUID.fromString(objectMapper
                .readTree(create.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

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
                .andExpect(status().isOk());

        String memberToken = registerAndLogin(cooperativeId, "MEMBER");
        String accountantToken = registerAndLogin(cooperativeId, "ACCOUNTANT");
        String loanOfficerToken = registerAndLogin(cooperativeId, "LOAN_OFFICER");
        return new CoopUsers(cooperativeId, memberToken, accountantToken, loanOfficerToken);
    }

    private String registerAndLogin(UUID cooperativeId, String role) throws Exception {
        String username = role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"%s",
                                  "lastName":"User",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s"
                                }
                                """.formatted(role, username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        if ("MEMBER".equals(role)) {
            OpeningShareBalances.set(membershipRepository, cooperativeId, userId, 1);
        }
        String password = objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();
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

    private record CoopUsers(
            UUID cooperativeId, String memberToken, String accountantToken, String loanOfficerToken) {}
}
