package rw.terimbere.csams.modules.contribution;

import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.notification.channel.EmailNotificationPublisher;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContributionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContributionRepository contributionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private SpecialContributionRepository specialContributionRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @SpyBean
    private EmailNotificationPublisher emailNotificationPublisher;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberUsername;
    private String memberPassword;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Contrib Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        memberUsername = "cmember_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Contrib",
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
    }

    @Test
    void batchUpsert_uniquePeriod_andLedger_andDashboard() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].memberUserId").value(memberUserId.toString()))
                .andExpect(jsonPath("$.data[0].expectedAmount").value(1000.0))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].persisted").value(false));

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": 1000.0000,
                                      "paymentDate": "2026-03-05",
                                      "paymentReference": "REF-MARCH-1",
                                      "notes": "Full payment"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PAID"))
                .andExpect(jsonPath("$.data[0].outstandingAmount").value(0.0))
                .andExpect(jsonPath("$.data[0].persisted").value(true));

        assertThat(contributionRepository.existsByCooperativeIdAndMemberUserIdAndYearAndMonth(
                        cooperativeId, memberUserId, 2026, 3))
                .isTrue();

        var contribution = contributionRepository
                .findByCooperativeIdAndMemberUserIdAndYearAndMonth(cooperativeId, memberUserId, 2026, 3)
                .orElseThrow();
        assertThat(contribution.getStatus()).isEqualTo(ContributionStatus.PAID);
        assertThat(MoneyUtils.scale(contribution.getPaidAmount())).isEqualByComparingTo("1000.00");

        long ledgerCredits = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getSourceEntityId().equals(contribution.getId()))
                .filter(e -> e.getTransactionType() == LedgerTransactionType.REGULAR_CONTRIBUTION)
                .filter(e -> e.getStatus() == LedgerEntryStatus.APPROVED)
                .count();
        assertThat(ledgerCredits).isEqualTo(1);

        // Upsert same period again with partial amount — unique period, correction via reversal
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": 400.5000,
                                      "paymentDate": "2026-03-06",
                                      "paymentReference": "REF-MARCH-2"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.data[0].outstandingAmount").value(599.5));

        assertThat(contributionRepository.findByCooperativeIdAndYearAndMonth(cooperativeId, 2026, 3))
                .hasSize(1);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeMembers").value(1))
                .andExpect(jsonPath("$.data.regularContributionsTotal").value(400.5))
                .andExpect(jsonPath("$.data.specialContributionsTotal").value(0.0))
                .andExpect(jsonPath("$.data.actualContributionsTotal").value(400.5))
                .andExpect(jsonPath("$.data.availableGroupFunds").value(400.5))
                .andExpect(jsonPath("$.data.currency").value("RWF"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/charts/monthly-contributions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].month").value(3))
                .andExpect(jsonPath("$.data[2].totalPaid").value(400.5));
    }

    @Test
    void specialApproveWritesLedger_rejectDoesNot() throws Exception {
        MvcResult campaignResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/special-campaigns")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roof Fund","purpose":"Repair","suggestedAmount":500.0000}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID campaignId = UUID.fromString(objectMapper
                .readTree(campaignResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(patch("/api/v1/cooperatives/" + cooperativeId + "/special-campaigns/" + campaignId + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        String memberToken = loginAccessToken(memberUsername, memberPassword);

        MvcResult submitApproved = mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/special-campaigns/"
                                + campaignId
                                + "/contributions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":250.2500,"paymentReference":"SP-1","contributionDate":"2026-04-01"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        UUID approvedId = UUID.fromString(objectMapper
                .readTree(submitApproved.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        MvcResult submitRejected = mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/special-campaigns/"
                                + campaignId
                                + "/contributions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100.0000,"paymentReference":"SP-2"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID rejectedId = UUID.fromString(objectMapper
                .readTree(submitRejected.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/special-campaigns/"
                                + campaignId
                                + "/contributions/"
                                + approvedId
                                + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/special-campaigns/"
                                + campaignId
                                + "/contributions/"
                                + rejectedId
                                + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewNotes":"Incomplete reference"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(specialContributionRepository.findById(approvedId).orElseThrow().getStatus())
                .isEqualTo(SpecialContributionStatus.APPROVED);
        assertThat(specialContributionRepository.findById(rejectedId).orElseThrow().getStatus())
                .isEqualTo(SpecialContributionStatus.REJECTED);

        long specialCredits = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getTransactionType() == LedgerTransactionType.SPECIAL_CONTRIBUTION)
                .filter(e -> e.getStatus() == LedgerEntryStatus.APPROVED)
                .filter(e -> e.getSourceEntityId().equals(approvedId) || e.getSourceEntityId().equals(rejectedId))
                .count();
        assertThat(specialCredits).isEqualTo(1);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.specialContributionsTotal").value(250.25))
                .andExpect(jsonPath("$.data.pendingSpecialApprovals").value(0));
    }

    @Test
    void memberOfCoopA_cannotAccessCoopBContributions() throws Exception {
        String otherName = "Other Coop " + UUID.randomUUID().toString().substring(0, 8);
        MvcResult otherCreate = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(otherName, "500", 1)))
                .andExpect(status().isOk())
                .andReturn();
        UUID otherCoopId = UUID.fromString(objectMapper
                .readTree(otherCreate.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        String memberToken = loginAccessToken(memberUsername, memberPassword);

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoopId + "/contributions/period")
                        .header("Authorization", "Bearer " + memberToken)
                        .param("year", "2026")
                        .param("month", "1"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoopId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberCanReadOwnHistory_andPatchCorrectsWithBigDecimal() throws Exception {
        MvcResult save = mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": 10.1250,
                                      "paymentDate": "2026-05-01"
                                    }
                                  ]
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isOk())
                .andReturn();
        UUID contributionId = UUID.fromString(objectMapper
                .readTree(save.getResponse().getContentAsString())
                .path("data")
                .path(0)
                .path("id")
                .asText());

        // storage scale 4; API response scaled to 2 for display → 10.13
        assertThat(MoneyUtils.scale(new BigDecimal("10.1250"))).isEqualByComparingTo("10.13");

        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/my")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(contributionId.toString()));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/my")
                        .header("Authorization", "Bearer " + memberToken)
                        .param("year", "2026")
                        .param("month", "5")
                        .param("status", "PARTIALLY_PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(contributionId.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/my")
                        .header("Authorization", "Bearer " + memberToken)
                        .param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/my")
                        .header("Authorization", "Bearer " + memberToken)
                        .param("fromDate", "2026-06-01")
                        .param("toDate", "2026-05-01"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/cooperatives/" + cooperativeId + "/contributions/" + contributionId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paidAmount":10.1240}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidAmount").value(10.12));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/members/" + memberUserId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contributions[0].id").value(contributionId.toString()));
    }

    @Test
    void periodGrid_returnsMemberName_thatTheFrontendGridMapsToTheDisplayName() throws Exception {
        // The grid row carries memberName (not fullName/username); the frontend maps memberName -> fullName.
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].memberName").value("Contrib Member"))
                .andExpect(jsonPath("$.data[0].fullName").doesNotExist());
    }

    @Test
    void batchUpsert_rejectsOverlongReferenceOrNotes_andPersistsNothing() throws Exception {
        long before = contributionRepository.count();
        String tooLongReference = "r".repeat(129);
        String tooLongNotes = "n".repeat(2001);

        for (String field : new String[] {
            "\"paymentReference\": \"" + tooLongReference + "\"", "\"notes\": \"" + tooLongNotes + "\""
        }) {
            mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                            .header("Authorization", "Bearer " + superAdminToken)
                            .param("year", "2026")
                            .param("month", "6")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lines":[{"memberUserId":"%s","paidAmount":500,"paymentDate":"2026-06-01",%s}]}
                                    """.formatted(memberUserId, field)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(contributionRepository.count()).isEqualTo(before);
    }

    @Test
    void batchUpsert_acceptsReferenceAndNotesAtTheLimit() throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"memberUserId":"%s","paidAmount":500,"paymentDate":"2026-07-01",
                                "paymentReference":"%s","notes":"%s"}]}
                                """.formatted(memberUserId, "r".repeat(128), "n".repeat(2000))))
                .andExpect(status().isOk());
    }

    @Test
    void patch_rejectsOverlongReference() throws Exception {
        MvcResult save = mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"memberUserId":"%s","paidAmount":500,"paymentDate":"2026-08-01"}]}
                                """.formatted(memberUserId)))
                .andExpect(status().isOk())
                .andReturn();
        UUID contributionId = UUID.fromString(objectMapper
                .readTree(save.getResponse().getContentAsString())
                .path("data")
                .path(0)
                .path("id")
                .asText());

        mockMvc.perform(patch("/api/v1/cooperatives/" + cooperativeId + "/contributions/" + contributionId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentReference\":\"" + "r".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ batch must not reopen or overwrite locked rows

    private String batchBody(String extraLineFields, String paidAmount) {
        return """
                {"lines":[{"memberUserId":"%s","paidAmount":%s,"paymentDate":"2026-09-01"%s}]}
                """.formatted(memberUserId, paidAmount, extraLineFields);
    }

    private void putBatch(int month, String body, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", String.valueOf(month))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    private Contribution stored(int month) {
        return contributionRepository
                .findByCooperativeIdAndMemberUserIdAndYearAndMonth(cooperativeId, memberUserId, 2026, month)
                .orElseThrow();
    }

    @Test
    void batch_cannotImplicitlyReopenAWaivedContribution() throws Exception {
        putBatch(9, batchBody(",\"status\":\"WAIVED\"", "0"), 200);
        assertThat(stored(9).getStatus()).isEqualTo(ContributionStatus.WAIVED);

        // no status in the request: previously this silently turned the contribution back into PAID
        putBatch(9, batchBody("", "1000"), 422);
        // an explicit attempt to set an ordinary status is not a reopen path either
        putBatch(9, batchBody(",\"status\":\"PAID\"", "1000"), 422);

        Contribution after = stored(9);
        assertThat(after.getStatus()).isEqualTo(ContributionStatus.WAIVED);
        assertThat(after.getPaidAmount()).isEqualByComparingTo("0");
    }

    @Test
    void batch_cannotImplicitlyReopenACancelledContribution() throws Exception {
        putBatch(10, batchBody(",\"status\":\"CANCELLED\"", "0"), 200);
        assertThat(stored(10).getStatus()).isEqualTo(ContributionStatus.CANCELLED);

        putBatch(10, batchBody("", "1000"), 422);

        Contribution after = stored(10);
        assertThat(after.getStatus()).isEqualTo(ContributionStatus.CANCELLED);
        assertThat(after.getPaidAmount()).isEqualByComparingTo("0");
    }

    @Test
    void batch_restatingTheSameTerminalStatusStillWorks() throws Exception {
        putBatch(11, batchBody(",\"status\":\"WAIVED\"", "0"), 200);
        putBatch(11, batchBody(",\"status\":\"WAIVED\"", "0"), 200);
        assertThat(stored(11).getStatus()).isEqualTo(ContributionStatus.WAIVED);
    }

    @Test
    void batch_cannotOverwriteAContributionAwaitingReview() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        MvcResult submit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/submissions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 1000.0000, "paymentDate": "2026-04-05", "paymentReference": "MOMO-9"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andReturn();
        JsonNode submitted = objectMapper.readTree(submit.getResponse().getContentAsString()).path("data");
        int year = submitted.path("year").asInt();
        int month = submitted.path("month").asInt();

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", String.valueOf(year))
                        .param("month", String.valueOf(month))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody("", "250")))
                .andExpect(status().isUnprocessableEntity());

        Contribution after = contributionRepository
                .findByCooperativeIdAndMemberUserIdAndYearAndMonth(cooperativeId, memberUserId, year, month)
                .orElseThrow();
        assertThat(after.getReviewStatus()).isEqualTo(rw.terimbere.csams.modules.contribution.entity.ContributionReviewStatus.PENDING);
        assertThat(after.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(after.getPaymentReference()).isEqualTo("MOMO-9");
    }

    @Test
    void batch_ordinaryRecordingAndCorrectionStillSucceed() throws Exception {
        putBatch(12, batchBody("", "500"), 200);
        assertThat(stored(12).getStatus()).isEqualTo(ContributionStatus.PARTIALLY_PAID);

        putBatch(12, batchBody("", "1000"), 200);
        Contribution after = stored(12);
        assertThat(after.getStatus()).isEqualTo(ContributionStatus.PAID);
        assertThat(after.getPaidAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void memberSubmit_pendingThenAccountantApprove_postsLedgerAndRejectDoesNot() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);

        MvcResult submit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/submissions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1000.0000,
                                  "paymentDate": "2026-04-05",
                                  "paymentReference": "MOMO-1",
                                  "notes": "April savings"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.paidAmount").value(0.0))
                .andReturn();
        UUID contributionId = UUID.fromString(objectMapper
                .readTree(submit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/pending-review")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(contributionId.toString()));

        Mockito.clearInvocations(emailNotificationPublisher);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/" + contributionId + "/approve")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        Mockito.verify(emailNotificationPublisher, Mockito.never())
                .publish(
                        Mockito.eq(memberUserId),
                        Mockito.any(),
                        Mockito.eq(NotificationType.CONTRIBUTION),
                        Mockito.eq("Contribution approved"),
                        Mockito.any(),
                        Mockito.eq("Contribution"),
                        Mockito.eq(contributionId));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/" + contributionId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.paidAmount").value(1000.0));
        Mockito.verify(emailNotificationPublisher)
                .publish(
                        Mockito.eq(memberUserId),
                        Mockito.eq(cooperativeId),
                        Mockito.eq(NotificationType.CONTRIBUTION),
                        Mockito.eq("Contribution approved"),
                        Mockito.any(),
                        Mockito.eq("Contribution"),
                        Mockito.eq(contributionId));

        MvcResult approvedList = mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode approvedContent = objectMapper
                .readTree(approvedList.getResponse().getContentAsString())
                .path("data")
                .path("content");
        long approvedCount = 0;
        for (JsonNode notification : approvedContent) {
            if ("Contribution approved".equals(notification.path("title").asText())) {
                approvedCount++;
            }
        }
        assertThat(approvedCount).isEqualTo(1);

        Mockito.clearInvocations(emailNotificationPublisher);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/" + contributionId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isUnprocessableEntity());
        Mockito.verify(emailNotificationPublisher, Mockito.never())
                .publish(
                        Mockito.eq(memberUserId),
                        Mockito.any(),
                        Mockito.eq(NotificationType.CONTRIBUTION),
                        Mockito.eq("Contribution approved"),
                        Mockito.any(),
                        Mockito.eq("Contribution"),
                        Mockito.eq(contributionId));

        long ledgerCredits = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getSourceEntityId().equals(contributionId))
                .filter(e -> e.getTransactionType() == LedgerTransactionType.REGULAR_CONTRIBUTION)
                .filter(e -> e.getStatus() == LedgerEntryStatus.APPROVED)
                .count();
        assertThat(ledgerCredits).isEqualTo(1);

        MvcResult second = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/submissions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 500.0000,
                                  "paymentDate": "2026-05-05",
                                  "paymentReference": "MOMO-2"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andReturn();
        UUID rejectedId = UUID.fromString(objectMapper
                .readTree(second.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/" + rejectedId + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectionReason\":\"Unclear proof\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.paidAmount").value(0.0));
    }

    @Test
    void rejectSubmission_notifiesMemberWithRejectorRole() throws Exception {
        String accountantUsername = "acc_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult accountant = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Ann",
                                  "lastName":"Counter",
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
        String memberToken = loginAccessToken(memberUsername, memberPassword);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/submissions")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1000.0000,
                                  "paymentDate": "2026-05-05",
                                  "paymentReference": "MOMO-REJECT"
                                }
                                """))
                .andExpect(status().isOk());

        MvcResult pending = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/pending-review")
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isOk())
                .andReturn();
        UUID contributionId = UUID.fromString(objectMapper
                .readTree(pending.getResponse().getContentAsString())
                .path("data")
                .path("content")
                .get(0)
                .path("id")
                .asText());

        Mockito.clearInvocations(emailNotificationPublisher);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/" + contributionId + "/reject")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectionReason\":\"Unclear proof\"}"))
                .andExpect(status().isOk());
        Mockito.verify(emailNotificationPublisher)
                .publish(
                        Mockito.eq(memberUserId),
                        Mockito.eq(cooperativeId),
                        Mockito.eq(NotificationType.CONTRIBUTION),
                        Mockito.eq("Contribution rejected"),
                        Mockito.any(),
                        Mockito.eq("Contribution"),
                        Mockito.eq(contributionId));

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
            if ("Contribution rejected".equals(notification.path("title").asText())) {
                assertThat(notification.path("type").asText()).isEqualTo("CONTRIBUTION");
                assertThat(notification.path("entityType").asText()).isEqualTo("Contribution");
                assertThat(notification.path("entityId").asText()).isEqualTo(contributionId.toString());
                assertThat(notification.path("body").asText()).contains("2026-05");
                assertThat(notification.path("body").asText()).contains("ACCOUNTANT");
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void shareCountScalesExpectedAmount_andPartialPaymentRemaining() throws Exception {
        String twoShareUser = "shares_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Two",
                                  "lastName":"Shares",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 2
                                }
                                """.formatted(twoShareUser, twoShareUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(0))
                .andReturn();
        UUID twoShareId = UUID.fromString(objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, twoShareId, 2);
        String twoSharePassword = objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();
        String token = loginAccessToken(twoShareUser, twoSharePassword);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/contributions/my/period-preview")
                        .header("Authorization", "Bearer " + token)
                        .param("year", "2026")
                        .param("month", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(2))
                .andExpect(jsonPath("$.data.requiredAmount").value(2000.0))
                .andExpect(jsonPath("$.data.remainingAmount").value(2000.0));

        MvcResult submit = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/submissions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "year": 2026,
                                  "month": 6,
                                  "amount": 800.0000,
                                  "paymentDate": "2026-06-04",
                                  "paymentReference": "PARTIAL-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(2))
                .andExpect(jsonPath("$.data.expectedAmount").value(2000.0))
                .andExpect(jsonPath("$.data.submittedAmount").value(800.0))
                .andExpect(jsonPath("$.data.remainingAmount").value(2000.0))
                .andReturn();
        UUID contributionId = UUID.fromString(objectMapper
                .readTree(submit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/" + contributionId + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.data.paidAmount").value(800.0))
                .andExpect(jsonPath("$.data.remainingAmount").value(1200.0));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/submissions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "year": 2026,
                                  "month": 6,
                                  "amount": 1200.0000,
                                  "paymentDate": "2026-06-08"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/contributions/submissions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "year": 2026,
                                  "month": 6,
                                  "amount": 1.0000,
                                  "paymentDate": "2026-06-09"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());

        assertThat(twoShareId).isNotNull();
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
