package rw.terimbere.csams.modules.payout;

import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.payout.entity.PayoutLine;
import rw.terimbere.csams.modules.payout.repository.PayoutLineRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PayoutControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private PayoutLineRepository payoutLineRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID otherCooperativeId;
    private UUID member1Id;
    private UUID member2Id;
    private String member1Username;
    private String member1Password;
    private String member2Username;
    private String member2Password;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        cooperativeId = createCooperative("Payout Coop " + UUID.randomUUID().toString().substring(0, 8));
        otherCooperativeId = createCooperative("Other Payout " + UUID.randomUUID().toString().substring(0, 8));

        member1Username = "pm1_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult reg1 = registerMember(member1Username, "One");
        member1Id = UUID.fromString(objectMapper
                .readTree(reg1.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        member1Password = objectMapper
                .readTree(reg1.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();

        member2Username = "pm2_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult reg2 = registerMember(member2Username, "Two");
        member2Id = UUID.fromString(objectMapper
                .readTree(reg2.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        member2Password = objectMapper
                .readTree(reg2.getResponse().getContentAsString())
                .path("data")
                .path("temporaryPassword")
                .asText();
    }

    @Test
    void previewPercentagesAndAmounts_confirmPostsLedgerAndReducesFund() throws Exception {
        fundMembers(6000.0000, 4000.0000);

        MvcResult preview = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "periodFrom": "2026-01-01",
                                  "periodTo": "2026-01-31",
                                  "includeRegular": true,
                                  "includeSpecial": false,
                                  "payoutPoolAmount": 1000.0000,
                                  "name": "Q1 payout"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PREVIEWED"))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andReturn();

        JsonNode data = objectMapper.readTree(preview.getResponse().getContentAsString()).path("data");
        UUID runId = UUID.fromString(data.path("id").asText());

        BigDecimal pctSum = BigDecimal.ZERO;
        BigDecimal amountSum = BigDecimal.ZERO;
        for (JsonNode line : data.path("lines")) {
            pctSum = pctSum.add(new BigDecimal(line.path("percentage").asText()));
            amountSum = amountSum.add(new BigDecimal(line.path("payoutAmount").asText()));
        }
        assertThat(pctSum).isCloseTo(new BigDecimal("100"), org.assertj.core.data.Offset.offset(new BigDecimal("0.0001")));
        assertThat(amountSum).isEqualByComparingTo("1000.00");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(10000.0))
                .andExpect(jsonPath("$.data.pendingPayoutsCount").value(1))
                .andExpect(jsonPath("$.data.totalConfirmedPayouts").value(0.0));

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + runId + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(9000.0))
                .andExpect(jsonPath("$.data.pendingPayoutsCount").value(0))
                .andExpect(jsonPath("$.data.totalConfirmedPayouts").value(1000.0));

        long payoutDebits = ledgerEntryRepository
                .findAll()
                .stream()
                .filter(e -> e.getCooperativeId().equals(cooperativeId))
                .filter(e -> e.getTransactionType() == LedgerTransactionType.MEMBER_PAYOUT)
                .filter(e -> e.getStatus() == LedgerEntryStatus.APPROVED)
                .count();
        assertThat(payoutDebits).isEqualTo(2);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + runId + "/mark-paid")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + runId + "/statement")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalPayoutAmount").value(1000.0))
                .andExpect(jsonPath("$.data.currency").value("RWF"));
    }

    @Test
    void futureContributionDoesNotChangeConfirmedLineAmounts() throws Exception {
        fundMembers(3000.0000, 1000.0000);

        MvcResult preview = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "periodFrom": "2026-01-01",
                                  "periodTo": "2026-01-31",
                                  "includeRegular": true,
                                  "includeSpecial": false,
                                  "payoutPoolAmount": 400.0000
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(preview.getResponse().getContentAsString()).path("data");
        UUID runId = UUID.fromString(data.path("id").asText());
        BigDecimal confirmedM1 = null;
        for (JsonNode line : data.path("lines")) {
            if (member1Id.toString().equals(line.path("memberUserId").asText())) {
                confirmedM1 = new BigDecimal(line.path("payoutAmount").asText());
            }
        }
        assertThat(confirmedM1).isNotNull();

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + runId + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        // Additional contribution after confirm
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", "2026")
                        .param("month", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": 5000.0000,
                                      "paymentDate": "2026-02-10",
                                      "paymentReference": "AFTER"
                                    }
                                  ]
                                }
                                """.formatted(member1Id)))
                .andExpect(status().isOk());

        MvcResult after = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + runId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();

        for (JsonNode line : objectMapper
                .readTree(after.getResponse().getContentAsString())
                .path("data")
                .path("lines")) {
            if (member1Id.toString().equals(line.path("memberUserId").asText())) {
                assertThat(new BigDecimal(line.path("payoutAmount").asText())).isEqualByComparingTo(confirmedM1);
            }
        }

        PayoutLine stored = payoutLineRepository
                .findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(runId, cooperativeId)
                .stream()
                .filter(l -> l.getMemberUserId().equals(member1Id))
                .findFirst()
                .orElseThrow();
        assertThat(MoneyUtils.scale(stored.getPayoutAmount())).isEqualByComparingTo(confirmedM1);
    }

    @Test
    void cancelPreviewOk_cancelConfirmedNotAllowed() throws Exception {
        fundMembers(2000.0000, 2000.0000);

        MvcResult preview = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromYear": 2026,
                                  "fromMonth": 1,
                                  "toYear": 2026,
                                  "toMonth": 1,
                                  "includeRegular": true,
                                  "includeSpecial": false,
                                  "payoutPoolAmount": 100.0000
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID cancelRunId = UUID.fromString(objectMapper
                .readTree(preview.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + cancelRunId + "/cancel")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        MvcResult preview2 = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "periodFrom": "2026-01-01",
                                  "periodTo": "2026-01-31",
                                  "includeRegular": true,
                                  "includeSpecial": false,
                                  "payoutPoolAmount": 100.0000
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID confirmRunId = UUID.fromString(objectMapper
                .readTree(preview2.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + confirmRunId + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + confirmRunId + "/cancel")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DRAFT or PREVIEWED")));
    }

    @Test
    void poolGreaterThanFundBlockedAtConfirm() throws Exception {
        fundMembers(500.0000, 500.0000);

        MvcResult preview = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "periodFrom": "2026-01-01",
                                  "periodTo": "2026-01-31",
                                  "includeRegular": true,
                                  "includeSpecial": false,
                                  "payoutPoolAmount": 800.0000
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID runId = UUID.fromString(objectMapper
                .readTree(preview.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        // Drain fund via investment so confirm-time check fails
        MvcResult inv = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Drain","amount":500.0000}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID investmentId = UUID.fromString(objectMapper
                .readTree(inv.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/investments/" + investmentId + "/activate")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + runId + "/confirm")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("insufficient")));
    }

    @Test
    void coopIsolationAndMemberMyOnlyOwnLines() throws Exception {
        fundMembers(1000.0000, 1000.0000);

        MvcResult preview = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/payouts/preview")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "periodFrom": "2026-01-01",
                                  "periodTo": "2026-01-31",
                                  "includeRegular": true,
                                  "includeSpecial": false,
                                  "payoutPoolAmount": 200.0000
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID runId = UUID.fromString(objectMapper
                .readTree(preview.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/payouts/" + runId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCooperativeId + "/payouts")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));

        String member1Token = loginAccessToken(member1Username, member1Password);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/payouts/my")
                        .header("Authorization", "Bearer " + member1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].memberUserId").value(member1Id.toString()));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/payouts/" + runId)
                        .header("Authorization", "Bearer " + member1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.lines[0].memberUserId").value(member1Id.toString()));

        String member2Token = loginAccessToken(member2Username, member2Password);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/payouts/my")
                        .header("Authorization", "Bearer " + member2Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].memberUserId").value(member2Id.toString()));
    }

    private MvcResult registerMember(String username, String lastName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Pay",
                                  "lastName":"%s",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"MEMBER"
                                }
                                """.formatted(lastName, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, userId, 1);
        return result;
    }

    private void fundMembers(double amount1, double amount2) throws Exception {
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
                                      "paymentReference": "FUND1"
                                    },
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": %s,
                                      "paymentDate": "2026-01-12",
                                      "paymentReference": "FUND2"
                                    }
                                  ]
                                }
                                """.formatted(
                                member1Id,
                                BigDecimal.valueOf(amount1).toPlainString(),
                                member2Id,
                                BigDecimal.valueOf(amount2).toPlainString())))
                .andExpect(status().isOk());
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
