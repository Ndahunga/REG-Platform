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
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentComponent;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.LoanPenaltyService;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepaymentAllocation;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentAllocationRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.subscription.SubscriptionClockTestSupport;
import rw.terimbere.csams.modules.subscription.repository.CooperativeSubscriptionRepository;
import rw.terimbere.csams.modules.subscription.service.SubscriptionPricing;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanScheduleAllocationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private LoanInstallmentRepository installmentRepository;

    @Autowired
    private LoanRepaymentAllocationRepository allocationRepository;

    @Autowired
    private FineRepository fineRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private CooperativeSubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionPricing subscriptionPricing;

    @Autowired
    private LoanPenaltyService loanPenaltyService;

    @MockBean
    private Clock clock;

    private String superAdminToken;
    private String loanOfficerToken;
    private UUID cooperativeId;
    private UUID memberUserId;

    @BeforeEach
    void setUp() throws Exception {
        freezeClock(LocalDate.of(2026, 1, 10));
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Sched Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        String memberUsername = "smember_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Sched",
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

        String officerUsername = "sofficer_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult officer = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Sched",
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

        putSettings("""
                "interestRatePercent": 2.0000,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": false
                """);
        fundGroup(400000.0000);
    }

    @Test
    void sameDayUsesFullMonthAndPersistsOpeningBalances() throws Exception {
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 5);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentDateModel").value("SAME_DAY_OF_MONTH"))
                .andExpect(jsonPath("$.data.prorataEnabled").value(false))
                .andExpect(jsonPath("$.data.firstPeriodInterest").value(6000.0))
                .andExpect(jsonPath("$.data.interestAmount").value(30000.0))
                .andExpect(jsonPath("$.data.equalInstallmentAmount").value(66000.0))
                .andExpect(jsonPath("$.data.scheduleFinalized").value(true))
                .andExpect(jsonPath("$.data.repaymentSchedule.length()").value(5))
                .andExpect(jsonPath("$.data.dueDate").value("2026-06-15"))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].dueDate").value("2026-02-15"))
                .andExpect(jsonPath("$.data.repaymentSchedule[1].dueDate").value("2026-03-15"))
                .andExpect(jsonPath("$.data.repaymentSchedule[2].dueDate").value("2026-04-15"))
                .andExpect(jsonPath("$.data.repaymentSchedule[4].dueDate").value("2026-06-15"))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].openingPrincipalBalance").value(300000.0))
                .andExpect(jsonPath("$.data.repaymentSchedule[1].openingPrincipalBalance").value(240000.0))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("PENDING"));

        List<LoanInstallment> rows = installmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loanId);
        assertThat(rows).hasSize(5);
        assertThat(rows.stream().map(LoanInstallment::getPrincipalDue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("300000.00");
        assertThat(rows.stream().map(LoanInstallment::getInterestDue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("30000.00");
    }

    @Test
    void sameDayShortMonthDoesNotPermanentlyDrift() throws Exception {
        freezeClock(LocalDate.of(2026, 1, 31));
        UUID loanId = createApproveDisburse("300000.0000", 5);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstPeriodInterest").value(6000.0))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].dueDate").value("2026-02-28"))
                .andExpect(jsonPath("$.data.repaymentSchedule[1].dueDate").value("2026-03-31"))
                .andExpect(jsonPath("$.data.repaymentSchedule[2].dueDate").value("2026-04-30"))
                .andExpect(jsonPath("$.data.repaymentSchedule[3].dueDate").value("2026-05-31"));
    }

    @Test
    void monthEndUsesCalendarProrataAndMonthEndDates() throws Exception {
        putSettings("""
                "interestRatePercent": 2.0000,
                "repaymentDateModel": "MONTH_END",
                "loanPenaltyEnabled": false
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 5);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentDateModel").value("MONTH_END"))
                .andExpect(jsonPath("$.data.prorataEnabled").value(true))
                .andExpect(jsonPath("$.data.firstPeriodDays").value(16))
                .andExpect(jsonPath("$.data.firstPeriodInterest").value(3096.77))
                .andExpect(jsonPath("$.data.dueDate").value("2026-05-31"))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].dueDate").value("2026-01-31"))
                .andExpect(jsonPath("$.data.repaymentSchedule[1].dueDate").value("2026-02-28"))
                .andExpect(jsonPath("$.data.repaymentSchedule[2].dueDate").value("2026-03-31"))
                .andExpect(jsonPath("$.data.repaymentSchedule[3].dueDate").value("2026-04-30"))
                .andExpect(jsonPath("$.data.repaymentSchedule[4].dueDate").value("2026-05-31"));
    }

    @Test
    void monthEndLeapYearIncludesFebruary29() throws Exception {
        putSettings("""
                "interestRatePercent": 2.0000,
                "repaymentDateModel": "MONTH_END",
                "loanPenaltyEnabled": false
                """);
        freezeClock(LocalDate.of(2028, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 4);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentSchedule[0].dueDate").value("2028-01-31"))
                .andExpect(jsonPath("$.data.repaymentSchedule[1].dueDate").value("2028-02-29"))
                .andExpect(jsonPath("$.data.repaymentSchedule[2].dueDate").value("2028-03-31"))
                .andExpect(jsonPath("$.data.repaymentSchedule[3].dueDate").value("2028-04-30"));
    }

    @Test
    void previewReferenceDateDoesNotOverrideActualDisbursement() throws Exception {
        putSettings("""
                "interestRatePercent": 2.0000,
                "repaymentDateModel": "MONTH_END",
                "loanPenaltyEnabled": false
                """);
        freezeClock(LocalDate.of(2026, 1, 10));

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
                .andExpect(jsonPath("$.data.scheduleFinalized").value(false))
                .andExpect(jsonPath("$.data.firstPeriodDays").value(16))
                .andExpect(jsonPath("$.data.installments[0].dueDate").value("2026-01-31"));

        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": 300000.0000,
                                  "termMonths": 5,
                                  "purpose": "Preview unlock"
                                }
                                """.formatted(memberUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstPeriodDays").value(21))
                .andExpect(jsonPath("$.data.scheduleFinalized").value(false))
                .andReturn();
        UUID loanId = UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        approve(loanId);

        freezeClock(LocalDate.of(2026, 1, 15));
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disbursementDate").value("2026-01-15"))
                .andExpect(jsonPath("$.data.firstPeriodDays").value(16))
                .andExpect(jsonPath("$.data.firstPeriodInterest").value(3096.77))
                .andExpect(jsonPath("$.data.scheduleFinalized").value(true));
    }

    @Test
    void installmentStatusesCoverPendingDueOverduePartialAndPaid() throws Exception {
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 5);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("PENDING"));

        repay(loanId, "2500.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestPortion").value(2500.0))
                .andExpect(jsonPath("$.data.principalPortion").value(0.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("PARTIALLY_PAID"));

        freezeClock(LocalDate.of(2026, 2, 15));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("DUE"));

        freezeClock(LocalDate.of(2026, 2, 16));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("OVERDUE"));

        repay(loanId, "63500.0000").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("PAID"));
    }

    @Test
    void fifoAndPersistedAllocationsReconcileLoanTotals() throws Exception {
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 5);

        repay(loanId, "80000.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestPortion").value(12000.0))
                .andExpect(jsonPath("$.data.principalPortion").value(68000.0));

        List<LoanRepaymentAllocation> lines = allocationRepository.findByLoanIdOrderByCreatedAtAsc(loanId);
        assertThat(lines).hasSize(4);
        assertThat(lines.stream()
                        .filter(line -> line.getComponent() == LoanRepaymentComponent.INTEREST)
                        .map(LoanRepaymentAllocation::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("12000.00");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.repaymentSchedule[1].status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(232000.0))
                .andExpect(jsonPath("$.data.outstandingInterest").value(18000.0));

        for (int i = 0; i < 4; i++) {
            repay(loanId, "62500.0000").andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(0.0))
                .andExpect(jsonPath("$.data.outstandingInterest").value(0.0))
                .andExpect(jsonPath("$.data.totalRepaidPrincipal").value(300000.0))
                .andExpect(jsonPath("$.data.totalRepaidInterest").value(30000.0));
    }

    @Test
    void configuredAllocationOrderIsHonoredForFullAndPartialPayments() throws Exception {
        putSettings("""
                "interestRatePercent": 18.7500,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "FIXED_AMOUNT",
                "penaltyRateOrAmount": 5000.0000,
                "penaltyFrequency": "ONE_TIME",
                "gracePeriodDays": 0,
                "allocationOrder": ["PENALTY", "INTEREST", "PRINCIPAL"]
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("80000.0000", 1);

        freezeClock(LocalDate.of(2026, 2, 16));
        assessPenalties(loanId);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(5000.0))
                .andExpect(jsonPath("$.data.outstandingPenalty").value(5000.0));

        repay(loanId, "100000.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.penaltyPortion").value(5000.0))
                .andExpect(jsonPath("$.data.interestPortion").value(15000.0))
                .andExpect(jsonPath("$.data.principalPortion").value(80000.0));

        Fine paid = latestLoanFine();
        assertThat(paid.getOutstandingAmount()).isEqualByComparingTo("0.0000");
        assertThat(paid.getStatus()).isEqualTo(FineStatus.PAID);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unpaidPenalties").value(0.0));
    }

    @Test
    void partialPaymentStopsAfterPenaltyThenInterest() throws Exception {
        putSettings("""
                "interestRatePercent": 18.7500,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "FIXED_AMOUNT",
                "penaltyRateOrAmount": 5000.0000,
                "penaltyFrequency": "ONE_TIME",
                "gracePeriodDays": 0,
                "allocationOrder": ["PENALTY", "INTEREST", "PRINCIPAL"]
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("80000.0000", 1);
        freezeClock(LocalDate.of(2026, 2, 16));
        getLoan(loanId);

        repay(loanId, "12000.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.penaltyPortion").value(5000.0))
                .andExpect(jsonPath("$.data.interestPortion").value(7000.0))
                .andExpect(jsonPath("$.data.principalPortion").value(0.0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].interestPaid").value(7000.0))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].principalPaid").value(0.0));
    }

    @Test
    void alternativeAllocationOrderIsConfigurable() throws Exception {
        putSettings("""
                "interestRatePercent": 18.7500,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "FIXED_AMOUNT",
                "penaltyRateOrAmount": 5000.0000,
                "penaltyFrequency": "ONE_TIME",
                "gracePeriodDays": 0,
                "allocationOrder": ["PRINCIPAL", "INTEREST", "PENALTY"]
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("80000.0000", 1);
        freezeClock(LocalDate.of(2026, 2, 16));
        getLoan(loanId);

        repay(loanId, "12000.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalPortion").value(12000.0))
                .andExpect(jsonPath("$.data.interestPortion").value(0.0))
                .andExpect(jsonPath("$.data.penaltyPortion").value(0.0));
    }

    @Test
    void disabledPenaltyIsNeverAssessed() throws Exception {
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 1);
        freezeClock(LocalDate.of(2026, 3, 1));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(0.0))
                .andExpect(jsonPath("$.data.outstandingPenalty").value(0.0));
        assertThat(loanFines()).isEmpty();
    }

    @Test
    void gracePeriodDelaysPenaltyButNotOverdueStatus() throws Exception {
        putSettings("""
                "interestRatePercent": 2.0000,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "FIXED_AMOUNT",
                "penaltyRateOrAmount": 1000.0000,
                "penaltyFrequency": "ONE_TIME",
                "gracePeriodDays": 3
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 1);

        freezeClock(LocalDate.of(2026, 2, 16));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(0.0));
        assertThat(loanFines()).isEmpty();

        freezeClock(LocalDate.of(2026, 2, 19));
        assessPenalties(loanId);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(1000.0));
        assertThat(loanFines()).hasSize(1);
    }

    @Test
    void percentageOfOverdueInstallmentUsesUnpaidContractBalance() throws Exception {
        putSettings("""
                "interestRatePercent": 18.7500,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "PERCENTAGE_OF_OVERDUE_INSTALLMENT",
                "penaltyRateOrAmount": 10.0000,
                "penaltyFrequency": "ONE_TIME",
                "gracePeriodDays": 0
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("80000.0000", 1);
        freezeClock(LocalDate.of(2026, 2, 16));
        assessPenalties(loanId);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(9500.0));
    }

    @Test
    void percentageOfOutstandingBalanceUsesOutstandingPrincipal() throws Exception {
        putSettings("""
                "interestRatePercent": 18.7500,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "PERCENTAGE_OF_OUTSTANDING_LOAN_BALANCE",
                "penaltyRateOrAmount": 5.0000,
                "penaltyFrequency": "ONE_TIME",
                "gracePeriodDays": 0
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("80000.0000", 1);
        freezeClock(LocalDate.of(2026, 2, 16));
        assessPenalties(loanId);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(4000.0));
    }

    @Test
    void dailyAssessmentsAreIdempotentAcrossRetries() throws Exception {
        putSettings("""
                "interestRatePercent": 2.0000,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "FIXED_AMOUNT",
                "penaltyRateOrAmount": 1000.0000,
                "penaltyFrequency": "DAILY",
                "gracePeriodDays": 0
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("80000.0000", 1);
        freezeClock(LocalDate.of(2026, 2, 18));
        getLoan(loanId);
        getLoan(loanId);
        assertThat(loanFinesFor(loanId)).hasSize(3);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(3000.0));
    }

    @Test
    void monthlyAssessmentsAreIdempotentAcrossRetries() throws Exception {
        putSettings("""
                "interestRatePercent": 2.0000,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "FIXED_AMOUNT",
                "penaltyRateOrAmount": 2000.0000,
                "penaltyFrequency": "MONTHLY",
                "gracePeriodDays": 0
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("80000.0000", 1);
        freezeClock(LocalDate.of(2026, 2, 16));
        getLoan(loanId);
        freezeClock(LocalDate.of(2026, 3, 16));
        getLoan(loanId);
        getLoan(loanId);
        assertThat(loanFinesFor(loanId)).hasSize(2);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(4000.0));
    }

    @Test
    void penaltyPaymentClearsAuthoritativeFineAndShareValuation() throws Exception {
        putSettings("""
                "interestRatePercent": 2.0000,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "FIXED_AMOUNT",
                "penaltyRateOrAmount": 5000.0000,
                "penaltyFrequency": "ONE_TIME",
                "gracePeriodDays": 0
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 1);
        freezeClock(LocalDate.of(2026, 2, 16));
        getLoan(loanId);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unpaidPenalties").value(5000.0));

        repay(loanId, "5000.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.penaltyPortion").value(5000.0));

        Fine fine = latestLoanFine();
        assertThat(fine.getOutstandingAmount()).isEqualByComparingTo("0.0000");
        assertThat(fine.getPaidAmount()).isEqualByComparingTo("5000.0000");
        assertThat(fine.getStatus()).isEqualTo(FineStatus.PAID);

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/shares/valuation")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unpaidPenalties").value(0.0));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.outstandingPenalty").value(0.0))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyPaid").value(5000.0));
    }

    @Test
    void scheduleExportAndPartialOverdueBalanceUseRemainingAmounts() throws Exception {
        putSettings("""
                "interestRatePercent": 18.7500,
                "repaymentDateModel": "SAME_DAY_OF_MONTH",
                "loanPenaltyEnabled": true,
                "penaltyType": "PERCENTAGE_OF_OVERDUE_INSTALLMENT",
                "penaltyRateOrAmount": 10.0000,
                "penaltyFrequency": "ONE_TIME",
                "gracePeriodDays": 0
                """);
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("80000.0000", 1);
        repay(loanId, "15000.0000").andExpect(status().isOk());

        freezeClock(LocalDate.of(2026, 2, 16));
        assessPenalties(loanId);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.repaymentSchedule[0].penaltyDue").value(8000.0));

        MvcResult pdf = mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId
                                + "/schedule/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Type", org.hamcrest.Matchers.containsString("application/pdf")))
                .andReturn();
        byte[] pdfBytes = pdf.getResponse().getContentAsByteArray();
        assertThat(pdfBytes.length).isGreaterThan(200);
        assertThat(new String(pdfBytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(new String(pdfBytes, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(loanId.toString());

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/schedule/export")
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    void legacyLoanWithoutInstallmentsKeepsLumpSumAllocation() throws Exception {
        freezeClock(LocalDate.of(2026, 1, 15));
        UUID loanId = createApproveDisburse("300000.0000", 5);
        installmentRepository.deleteAll(installmentRepository.findByLoanIdOrderByInstallmentNumberAsc(loanId));
        Loan loan = loanRepository.findById(loanId).orElseThrow();
        loan.setEqualInstallmentAmount(null);
        loan.setInterestAmount(new BigDecimal("6000.0000"));
        loan.setOutstandingInterest(new BigDecimal("6000.0000"));
        loanRepository.save(loan);

        repay(loanId, "70000.0000")
                .andExpect(jsonPath("$.data.interestPortion").value(6000.0))
                .andExpect(jsonPath("$.data.principalPortion").value(64000.0));

        Loan after = loanRepository.findById(loanId).orElseThrow();
        assertThat(after.getEqualInstallmentAmount()).isNull();
        assertThat(installmentRepository.existsByLoanId(loanId)).isFalse();
        assertThat(after.getOutstandingInterest()).isEqualByComparingTo("0.0000");
        assertThat(after.getOutstandingPrincipal()).isEqualByComparingTo("236000.0000");
        assertThat(allocationRepository.findByLoanIdOrderByCreatedAtAsc(loanId)).isEmpty();
    }

    private UUID createApproveDisburse(String amount, int termMonths) throws Exception {
        MvcResult requestResult = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memberUserId": "%s",
                                  "amount": %s,
                                  "termMonths": %d,
                                  "purpose": "Schedule allocation"
                                }
                                """.formatted(memberUserId, amount, termMonths)))
                .andExpect(status().isOk())
                .andReturn();
        UUID loanId = UUID.fromString(objectMapper
                .readTree(requestResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());
        approve(loanId);
        mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/disburse")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        return loanId;
    }

    private void approve(UUID loanId) throws Exception {
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
    }

    private org.springframework.test.web.servlet.ResultActions repay(UUID loanId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId + "/repayments")
                .header("Authorization", "Bearer " + superAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "amount": %s,
                          "paymentDate": "2026-02-01"
                        }
                        """.formatted(amount)));
    }

    private void getLoan(UUID loanId) throws Exception {
        assessPenalties(loanId);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/loans/" + loanId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
    }

    /** Write-side penalty assessment — no longer triggered by loan GET. */
    private void assessPenalties(UUID loanId) {
        Loan loan = loanRepository.findById(loanId).orElseThrow();
        loanPenaltyService.evaluate(loan, LocalDate.now(clock), null);
    }

    private void putSettings(String fields) throws Exception {
        mockMvc.perform(put("/api/v1/cooperatives/" + cooperativeId + "/loan-settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "interestType": "FLAT",
                                  "maxLoanAmount": 1000000.0000,
                                  "maxTermMonths": 12,
                                  "minMembershipMonths": 0,
                                  "allowMemberRequests": true,
                                  %s
                                }
                                """.formatted(fields)))
                .andExpect(status().isOk());
    }

    private List<Fine> loanFines() {
        return fineRepository
                .findByCooperativeIdAndMemberUserIdOrderByIssuedDateDescCreatedAtDesc(cooperativeId, memberUserId)
                .stream()
                .filter(fine -> fine.getSourceLoanId() != null)
                .toList();
    }

    private List<Fine> loanFinesFor(UUID loanId) {
        return loanFines().stream().filter(fine -> loanId.equals(fine.getSourceLoanId())).toList();
    }

    private Fine latestLoanFine() {
        return loanFines().get(0);
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
