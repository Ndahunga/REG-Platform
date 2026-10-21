package rw.terimbere.csams.modules.fine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FineControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FineRepository fineRepository;

    @Autowired
    private FinePaymentRepository finePaymentRepository;

    @Autowired
    private ContributionRepository contributionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberUsername;
    private String memberPassword;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Fine Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        memberUsername = "fmember_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Fine",
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

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/fine-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "autoFinesEnabled": true,
                                  "fineMode": "FIXED",
                                  "baseFineAmount": 500.0000,
                                  "dailyIncrement": 25.0000,
                                  "graceDays": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseFineAmount").value(500.0));
    }

    @Test
    void autoFine_forUnpaidContribution_andDuplicatePrevented() throws Exception {
        persistPendingContribution(2026, 1);

        MvcResult generate = mockMvc.perform(post(
                                "/api/v1/cooperatives/" + cooperativeId + "/fines/generate-automatic")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(1))
                .andExpect(jsonPath("$.data.created[0].fineType").value("AUTOMATIC"))
                .andExpect(jsonPath("$.data.created[0].totalAmount").value(500.0))
                .andExpect(jsonPath("$.data.created[0].status").value("UNPAID"))
                .andReturn();

        UUID fineId = UUID.fromString(objectMapper
                .readTree(generate.getResponse().getContentAsString())
                .path("data")
                .path("created")
                .get(0)
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines/generate-automatic")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.skippedDuplicates").value(1));

        assertThat(fineRepository.findByIdAndCooperativeId(fineId, cooperativeId)).isPresent();
        assertThat(fineRepository.countByCooperativeId(cooperativeId)).isEqualTo(1);
    }

    @Test
    void changingFineSettingsDoesNotAlterHistoricalAutomaticFine() throws Exception {
        persistPendingContribution(2026, 1);
        MvcResult first = mockMvc.perform(post(
                                "/api/v1/cooperatives/" + cooperativeId + "/fines/generate-automatic")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created[0].totalAmount").value(500.0))
                .andReturn();
        UUID firstFineId = UUID.fromString(objectMapper
                .readTree(first.getResponse().getContentAsString())
                .path("data")
                .path("created")
                .get(0)
                .path("id")
                .asText());

        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/fine-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "autoFinesEnabled": true,
                                  "fineMode": "FIXED",
                                  "baseFineAmount": 900.0000,
                                  "dailyIncrement": 25.0000,
                                  "graceDays": 0
                                }
                                """))
                .andExpect(status().isOk());

        persistPendingContribution(2026, 2);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines/generate-automatic")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created[0].totalAmount").value(900.0));

        Fine historical = fineRepository.findById(firstFineId).orElseThrow();
        assertThat(historical.getTotalAmount()).isEqualByComparingTo("500.0000");
    }

    @Test
    void manualFine_paymentSubmitApprove_updatesOutstandingAndLedger() throws Exception {
        UUID fineId = createManualFine(1000.0000);

        String memberToken = loginAccessToken(memberUsername, memberPassword);
        MvcResult submit = mockMvc.perform(post(
                                "/api/v1/cooperatives/" + cooperativeId + "/fines/" + fineId + "/payments")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 400.0000,
                                  "paymentDate": "2026-08-01",
                                  "paymentMethod": "MOBILE_MONEY",
                                  "paymentReference": "FP-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.amount").value(400.0))
                .andReturn();
        UUID paymentId = UUID.fromString(objectMapper
                .readTree(submit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        Fine before = fineRepository.findById(fineId).orElseThrow();
        assertThat(before.getOutstandingAmount()).isEqualByComparingTo("1000.0000");
        assertThat(before.getStatus()).isEqualTo(FineStatus.UNPAID);

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/fines/"
                                + fineId
                                + "/payments/"
                                + paymentId
                                + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        Fine after = fineRepository.findById(fineId).orElseThrow();
        assertThat(after.getPaidAmount()).isEqualByComparingTo("400.0000");
        assertThat(after.getOutstandingAmount()).isEqualByComparingTo("600.0000");
        assertThat(after.getStatus()).isEqualTo(FineStatus.PARTIALLY_PAID);

        assertThat(ledgerEntryRepository.findAll().stream()
                        .filter(e -> e.getCooperativeId().equals(cooperativeId))
                        .filter(e -> e.getTransactionType() == LedgerTransactionType.FINE_PAYMENT)
                        .filter(e -> e.getStatus() == LedgerEntryStatus.APPROVED)
                        .map(e -> e.getCreditAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("400.0000");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalFines").value(1))
                .andExpect(jsonPath("$.data.unpaidFines").value(1))
                .andExpect(jsonPath("$.data.approvedFineIncome").value(400.0))
                .andExpect(jsonPath("$.data.pendingFinePayments").value(0));
    }

    @Test
    void rejectPayment_doesNotChangeFineBalance() throws Exception {
        UUID fineId = createManualFine(800.0000);

        MvcResult submit = mockMvc.perform(post(
                                "/api/v1/cooperatives/" + cooperativeId + "/fines/" + fineId + "/payments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 200.0000,
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

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/fines/"
                                + fineId
                                + "/payments/"
                                + paymentId
                                + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewNotes":"Invalid evidence"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        Fine fine = fineRepository.findById(fineId).orElseThrow();
        assertThat(fine.getPaidAmount()).isEqualByComparingTo("0.0000");
        assertThat(fine.getOutstandingAmount()).isEqualByComparingTo("800.0000");
        assertThat(fine.getStatus()).isEqualTo(FineStatus.UNPAID);

        long fineLedger = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getCooperativeId().equals(cooperativeId))
                .filter(e -> e.getTransactionType() == LedgerTransactionType.FINE_PAYMENT)
                .count();
        assertThat(fineLedger).isZero();
    }

    @Test
    void paymentCannotExceedOutstanding() throws Exception {
        UUID fineId = createManualFine(100.0000);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines/" + fineId + "/payments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 150.0000,
                                  "paymentDate": "2026-08-01",
                                  "paymentMethod": "CASH"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cooperativeIsolation_fineNotVisibleInOtherCoop() throws Exception {
        UUID fineId = createManualFine(250.0000);

        String otherName = "Other Fine Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoopId + "/fines/" + fineId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoopId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalFines").value(0))
                .andExpect(jsonPath("$.data.approvedFineIncome").value(0.0));
    }

    @Test
    void progressiveSettings_autoFineUsesFormula() throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/fine-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "autoFinesEnabled": true,
                                  "fineMode": "PROGRESSIVE",
                                  "baseFineAmount": 1000.0000,
                                  "dailyIncrement": 50.0000,
                                  "graceDays": 0
                                }
                                """))
                .andExpect(status().isOk());

        persistPendingContribution(2026, 1);

        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines/generate-automatic")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year":2026,"month":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(1))
                .andExpect(jsonPath("$.data.created[0].calculationMode").value("PROGRESSIVE"))
                .andExpect(jsonPath("$.data.created[0].baseAmount").value(1000.0))
                .andExpect(jsonPath("$.data.created[0].dailyIncrementSnapshot").value(50.0));

        Fine fine = fineRepository.findByCooperativeId(cooperativeId, org.springframework.data.domain.Pageable.unpaged())
                .getContent()
                .get(0);
        // Due 2026-01-01, grace 0; overdue days = days from 2026-01-01 to today (2026-08-04) = 215
        assertThat(fine.getOverdueDays()).isGreaterThan(0);
        BigDecimal expected = new BigDecimal("1000.0000")
                .add(new BigDecimal("50.0000").multiply(BigDecimal.valueOf(fine.getOverdueDays())));
        assertThat(fine.getTotalAmount()).isEqualByComparingTo(expected.setScale(4));
    }

    @Test
    void approvedFinePayment_increasesAvailableGroupFunds() throws Exception {
        // Fund with contribution first so dashboard available is non-zero baseline
        persistPaidContribution(2026, 2, 1000.0000);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(1000.0));

        UUID fineId = createManualFine(300.0000);
        MvcResult submit = mockMvc.perform(post(
                                "/api/v1/cooperatives/" + cooperativeId + "/fines/" + fineId + "/payments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300.0000,
                                  "paymentDate": "2026-08-02",
                                  "paymentMethod": "BANK_TRANSFER"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID paymentId = UUID.fromString(objectMapper
                .readTree(submit.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/cooperatives/"
                                + cooperativeId
                                + "/fines/"
                                + fineId
                                + "/payments/"
                                + paymentId
                                + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableGroupFunds").value(1300.0))
                .andExpect(jsonPath("$.data.approvedFineIncome").value(300.0))
                .andExpect(jsonPath("$.data.unpaidFines").value(0));
    }

    @Test
    void paymentQueue_withNullableFilters_listsAndSearches() throws Exception {
        UUID fineId = createManualFine(1000.0000);
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines/" + fineId + "/payments")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 250.0000,
                                  "paymentDate": "2026-08-01",
                                  "paymentMethod": "MOBILE_MONEY",
                                  "paymentReference": "FP-QUEUE-1"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/fines/payments")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].paymentReference").value("FP-QUEUE-1"));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/fines/payments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("fromDate", "2026-08-01")
                        .param("toDate", "2026-08-26")
                        .param("status", "PENDING")
                        .param("q", "FP-QUEUE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/fines/payments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("q", "Fine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/fines/payments")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("q", "no-such-payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        assertThat(finePaymentRepository
                        .findQueuePage(cooperativeId, null, null, null, null, Pageable.unpaged())
                        .getTotalElements())
                .isEqualTo(1);
        assertThat(finePaymentRepository
                        .findQueuePage(
                                cooperativeId,
                                FinePaymentStatus.PENDING,
                                java.time.LocalDate.of(2026, 8, 1),
                                null,
                                null,
                                Pageable.unpaged())
                        .getTotalElements())
                .isEqualTo(1);
        assertThat(finePaymentRepository
                        .findQueuePage(
                                cooperativeId,
                                null,
                                null,
                                java.time.LocalDate.of(2026, 8, 1),
                                null,
                                Pageable.unpaged())
                        .getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void manualProgressiveFine_withAZeroTotal_isRejected() throws Exception {
        String[] zeroTotals = {
            "\"baseAmount\": 0, \"dailyIncrement\": 0, \"overdueDays\": 5",
            "\"baseAmount\": 0, \"dailyIncrement\": 100, \"overdueDays\": 0",
            "\"baseAmount\": 0, \"dailyIncrement\": 100",
        };
        for (String fragment : zeroTotals) {
            mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines")
                            .header("Authorization", "Bearer " + superAdminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"memberUserId":"%s","calculationMode":"PROGRESSIVE","reason":"Late",%s}
                                    """.formatted(memberUserId, fragment)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void manualProgressiveFine_withAPositiveTotal_isAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberUserId":"%s","calculationMode":"PROGRESSIVE","reason":"Late",
                                 "baseAmount":0,"dailyIncrement":100,"overdueDays":3}
                                """.formatted(memberUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value(300.0));
    }

    private UUID createManualFine(double amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/fines")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": %s,
                                  "reason": "Late payment"
                                }
                                """.formatted(memberUserId, BigDecimal.valueOf(amount).toPlainString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fineType").value("MANUAL"))
                .andExpect(jsonPath("$.data.status").value("UNPAID"))
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
    }

    private void persistPendingContribution(int year, int month) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", String.valueOf(year))
                        .param("month", String.valueOf(month))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": 0.0000
                                    }
                                  ]
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isOk());

        Contribution c = contributionRepository
                .findByCooperativeIdAndMemberUserIdAndYearAndMonth(cooperativeId, memberUserId, year, month)
                .orElseThrow();
        assertThat(c.getStatus()).isEqualTo(ContributionStatus.PENDING);
    }

    private void persistPaidContribution(int year, int month, double amount) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/contributions/period")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("year", String.valueOf(year))
                        .param("month", String.valueOf(month))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lines": [
                                    {
                                      "memberUserId": "%s",
                                      "paidAmount": %s,
                                      "paymentDate": "2026-02-10",
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
