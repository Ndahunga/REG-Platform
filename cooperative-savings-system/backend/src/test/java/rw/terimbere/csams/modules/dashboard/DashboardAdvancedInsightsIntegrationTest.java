package rw.terimbere.csams.modules.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import rw.terimbere.csams.modules.investment.entity.Investment;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardAdvancedInsightsIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kigali");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private InvestmentRepository investmentRepository;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberAId;
    private UUID memberBId;
    private String memberUsername;
    private String memberPassword;
    private String secretaryUsername;
    private String secretaryPassword;
    private String loanOfficerUsername;
    private String loanOfficerPassword;
    private LocalDate today;
    private int year;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZONE);
        year = today.getYear();
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");

        String name = "Adv Insights Coop " + UUID.randomUUID().toString().substring(0, 8);
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

        memberUsername = "adv_m_" + UUID.randomUUID().toString().substring(0, 8);
        MemberReg memberA = registerMember(memberUsername, "Jane", "Borrower", "MEMBER");
        memberAId = memberA.userId();
        memberPassword = memberA.password();

        MemberReg memberB = registerMember(
                "adv_b_" + UUID.randomUUID().toString().substring(0, 8), "Eric", "Borrower", "MEMBER");
        memberBId = memberB.userId();

        secretaryUsername = "adv_s_" + UUID.randomUUID().toString().substring(0, 8);
        MemberReg secretary = registerMember(secretaryUsername, "Sec", "Retary", "SECRETARY");
        secretaryPassword = secretary.password();

        loanOfficerUsername = "adv_l_" + UUID.randomUUID().toString().substring(0, 8);
        MemberReg loanOfficer = registerMember(loanOfficerUsername, "Loan", "Officer", "LOAN_OFFICER");
        loanOfficerPassword = loanOfficer.password();
    }

    @Test
    void loansDisbursedByMonth_aggregatesDisbursedOnly() throws Exception {
        saveLoan(memberAId, LoanStatus.ACTIVE, LocalDate.of(year, 3, 10), "100000");
        saveLoan(memberAId, LoanStatus.CLOSED, LocalDate.of(year, 3, 20), "200000");
        saveLoan(memberBId, LoanStatus.ACTIVE, LocalDate.of(year, 6, 5), "50000");
        saveLoan(memberAId, LoanStatus.PENDING, LocalDate.of(year, 3, 15), "999999");
        saveLoan(memberAId, LoanStatus.REJECTED, LocalDate.of(year, 3, 16), "888888");
        saveLoan(memberAId, LoanStatus.APPROVED, null, "777777");
        saveLoan(memberAId, LoanStatus.ACTIVE, LocalDate.of(year - 1, 12, 31), "400000");
        saveLoan(memberAId, LoanStatus.ACTIVE, LocalDate.of(year + 1, 1, 1), "400000");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/loans-disbursed-by-month?year=" + year)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(12))
                .andExpect(jsonPath("$.data[2].month").value(3))
                .andExpect(jsonPath("$.data[2].loanCount").value(2))
                .andExpect(jsonPath("$.data[2].principalAmount").value(300000.0))
                .andExpect(jsonPath("$.data[5].month").value(6))
                .andExpect(jsonPath("$.data[5].loanCount").value(1))
                .andExpect(jsonPath("$.data[5].principalAmount").value(50000.0))
                .andExpect(jsonPath("$.data[0].loanCount").value(0));
    }

    @Test
    void frequentBorrowers_ordersByCountThenPrincipal_top5() throws Exception {
        for (int i = 0; i < 5; i++) {
            saveLoan(memberAId, LoanStatus.ACTIVE, today.minusDays(i + 1), "100000");
        }
        for (int i = 0; i < 4; i++) {
            saveLoan(memberBId, LoanStatus.CLOSED, today.minusDays(i + 1), "200000");
        }
        saveLoan(memberAId, LoanStatus.REJECTED, today.minusDays(1), "999999");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frequentBorrowers.length()").value(2))
                .andExpect(jsonPath("$.data.frequentBorrowers[0].memberId").value(memberAId.toString()))
                .andExpect(jsonPath("$.data.frequentBorrowers[0].displayName").value("Jane Borrower"))
                .andExpect(jsonPath("$.data.frequentBorrowers[0].numberOfLoansDisbursed").value(5))
                .andExpect(jsonPath("$.data.frequentBorrowers[0].rank").value(1))
                .andExpect(jsonPath("$.data.frequentBorrowers[1].memberId").value(memberBId.toString()))
                .andExpect(jsonPath("$.data.frequentBorrowers[1].numberOfLoansDisbursed").value(4))
                .andExpect(jsonPath("$.data.frequentBorrowers[1].rank").value(2));
    }

    @Test
    void investmentsByMonth_usesActivatedAtAndOriginalAmount() throws Exception {
        Instant march = LocalDate.of(year, 3, 15).atStartOfDay(ZONE).toInstant();
        Instant june = LocalDate.of(year, 6, 10).atStartOfDay(ZONE).toInstant();

        saveInvestment("Active Fund", InvestmentStatus.ACTIVE, march, "1000000", "600000");
        saveInvestment("Completed Fund", InvestmentStatus.COMPLETED, march, "500000", "0");
        saveInvestment("Partial Fund", InvestmentStatus.PARTIALLY_RETURNED, june, "800000", "400000");
        saveInvestment("Planned Fund", InvestmentStatus.PLANNED, null, "900000", "0");
        saveInvestment("Cancelled Fund", InvestmentStatus.CANCELLED, null, "900000", "0");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/investments-by-month?year=" + year)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(12))
                .andExpect(jsonPath("$.data[2].month").value(3))
                .andExpect(jsonPath("$.data[2].investmentCount").value(2))
                .andExpect(jsonPath("$.data[2].capitalDeployed").value(1500000.0))
                .andExpect(jsonPath("$.data[5].investmentCount").value(1))
                .andExpect(jsonPath("$.data[5].capitalDeployed").value(800000.0));
    }

    @Test
    void largestActiveInvestments_excludesCompletedOrdersByRemaining() throws Exception {
        saveInvestment(
                "Big Active",
                InvestmentStatus.ACTIVE,
                today.atStartOfDay(ZONE).toInstant(),
                "5000000",
                "4000000");
        saveInvestment(
                "Partial",
                InvestmentStatus.PARTIALLY_RETURNED,
                today.atStartOfDay(ZONE).toInstant(),
                "3000000",
                "1500000");
        saveInvestment(
                "Done",
                InvestmentStatus.COMPLETED,
                today.atStartOfDay(ZONE).toInstant(),
                "9000000",
                "0");

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.largestActiveInvestments.length()").value(2))
                .andExpect(jsonPath("$.data.largestActiveInvestments[0].name").value("Big Active"))
                .andExpect(jsonPath("$.data.largestActiveInvestments[0].remainingCapital").value(4000000.0))
                .andExpect(jsonPath("$.data.largestActiveInvestments[0].originalCapital").value(5000000.0))
                .andExpect(jsonPath("$.data.largestActiveInvestments[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.largestActiveInvestments[0].rank").value(1))
                .andExpect(jsonPath("$.data.largestActiveInvestments[1].name").value("Partial"))
                .andExpect(jsonPath("$.data.largestActiveInvestments[1].status").value("PARTIALLY_RETURNED"));
    }

    @Test
    void memberAndSecretaryForbidden_loanOfficerLoanOnly() throws Exception {
        String memberToken = loginAccessToken(memberUsername, memberPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/loans-disbursed-by-month?year=" + year)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        String secretaryToken = loginAccessToken(secretaryUsername, secretaryPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + secretaryToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/investments-by-month?year=" + year)
                        .header("Authorization", "Bearer " + secretaryToken))
                .andExpect(status().isForbidden());

        String loanOfficerToken = loginAccessToken(loanOfficerUsername, loanOfficerPassword);
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/loans-disbursed-by-month?year=" + year)
                        .header("Authorization", "Bearer " + loanOfficerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(12));
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/investments-by-month?year=" + year)
                        .header("Authorization", "Bearer " + loanOfficerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + loanOfficerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frequentBorrowers").isArray())
                .andExpect(jsonPath("$.data.largestActiveInvestments").isEmpty());
    }

    @Test
    void cooperativeIsolation() throws Exception {
        saveLoan(memberAId, LoanStatus.ACTIVE, today, "123456");
        saveInvestment(
                "Isolated",
                InvestmentStatus.ACTIVE,
                today.atStartOfDay(ZONE).toInstant(),
                "999000",
                "999000");

        MvcResult other = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "Other Adv " + UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isOk())
                .andReturn();
        UUID otherCoop = UUID.fromString(objectMapper
                .readTree(other.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoop
                                + "/dashboard/charts/loans-disbursed-by-month?year=" + year)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].loanCount").value(0))
                .andExpect(jsonPath("$.data[2].loanCount").value(0))
                .andExpect(jsonPath("$.data[5].loanCount").value(0));

        mockMvc.perform(get("/api/v1/cooperatives/" + otherCoop + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frequentBorrowers").isEmpty())
                .andExpect(jsonPath("$.data.largestActiveInvestments").isEmpty());

        String outsiderMember = "outadv_" + UUID.randomUUID().toString().substring(0, 8);
        MemberReg outsider = registerMemberIn(otherCoop, outsiderMember, "Out", "Sider", "MEMBER");
        String outsiderToken = loginAccessToken(outsiderMember, outsider.password());
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId + "/dashboard/advanced-insights")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyYearReturnsZeroSeries() throws Exception {
        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/loans-disbursed-by-month?year=" + year)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(12))
                .andExpect(jsonPath("$.data[0].loanCount").value(0))
                .andExpect(jsonPath("$.data[0].principalAmount").value(0));

        mockMvc.perform(get("/api/v1/cooperatives/" + cooperativeId
                                + "/dashboard/charts/investments-by-month?year=" + year)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(12))
                .andExpect(jsonPath("$.data[0].investmentCount").value(0));
    }

    private Loan saveLoan(UUID memberId, LoanStatus status, LocalDate disbursementDate, String principal) {
        BigDecimal amount = money(principal);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .requestedAmount(amount)
                .approvedAmount(amount)
                .principalAmount(amount)
                .interestRatePercent(money("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(money("0"))
                .outstandingPrincipal(status == LoanStatus.CLOSED ? money("0") : amount)
                .outstandingInterest(money("0"))
                .requestDate(today.minusMonths(1))
                .disbursementDate(disbursementDate)
                .dueDate(today.plusMonths(3))
                .status(status)
                .build());
    }

    private Investment saveInvestment(
            String name, InvestmentStatus status, Instant activatedAt, String amount, String remaining) {
        return investmentRepository.saveAndFlush(Investment.builder()
                .cooperativeId(cooperativeId)
                .name(name)
                .amount(money(amount))
                .remainingCapital(money(remaining))
                .totalCapitalReturned(money("0"))
                .totalProfitReturned(money("50000"))
                .status(status)
                .activatedAt(activatedAt)
                .createdBy(memberAId)
                .build());
    }

    private MemberReg registerMember(String username, String first, String last, String role) throws Exception {
        return registerMemberIn(cooperativeId, username, first, last, role);
    }

    private MemberReg registerMemberIn(
            UUID coopId, String username, String first, String last, String role) throws Exception {
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + coopId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "firstName":"%s",
                                  "lastName":"%s",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s",
                                  "shareCount": 1
                                }
                                """
                                        .formatted(first, last, username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        UUID userId = UUID.fromString(data.path("userId").asText());
        String password = data.path("temporaryPassword").asText();
        OpeningShareBalances.set(membershipRepository, coopId, userId, 1);
        return new MemberReg(userId, password);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(4);
    }

    private String loginAccessToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
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

    private record MemberReg(UUID userId, String password) {}
}
