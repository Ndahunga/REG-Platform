package rw.terimbere.csams.modules.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
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
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.report.dto.MemberContributionAggregate;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.modules.report.support.ContributionObligationPeriod;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FullFinancialContributionAggregationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContributionRepository contributionRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @Autowired
    private ReportService reportService;

    private String superAdminToken;
    private UUID cooperativeId;
    private UUID memberA;
    private UUID memberB;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = loginAccessToken("superadmin", "ChangeMe@123!");
        cooperativeId = createCooperative("FF Contrib " + UUID.randomUUID().toString().substring(0, 8));
        memberA = registerMember(cooperativeId, "ffa_" + UUID.randomUUID().toString().substring(0, 8), "Vicky", "Alpha");
        memberB = registerMember(cooperativeId, "ffb_" + UUID.randomUUID().toString().substring(0, 8), "Eric", "Beta");
    }

    @Test
    void multiMonth_oneMemberAppearsOnce_withCorrectTotals() {
        // Jan–Jul: 7 months for Vicky — mixed paid/partial/unpaid
        save(memberA, 2026, 1, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 2, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 3, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 4, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 5, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 6, "10000", "5000", "5000", ContributionStatus.PARTIALLY_PAID);
        save(memberA, 2026, 7, "10000", "0", "10000", ContributionStatus.PENDING);
        // Missing August intentionally — Jan–Jul range only

        ContributionObligationPeriod.Range range = ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                .reportType(ReportType.FULL_FINANCIAL)
                .fromDate(LocalDate.of(2026, 1, 1))
                .toDate(LocalDate.of(2026, 7, 31))
                .build());

        List<MemberContributionAggregate> rows =
                reportService.loadFullFinancialContributionAggregates(cooperativeId, range);

        assertThat(rows).hasSize(1);
        MemberContributionAggregate vicky = rows.get(0);
        assertThat(vicky.getMemberName()).contains("Vicky");
        assertThat(vicky.getPeriodsCounted()).isEqualTo(7);
        assertThat(vicky.getExpectedAmount()).isEqualByComparingTo("70000.00");
        assertThat(vicky.getPaidAmount()).isEqualByComparingTo("55000.00");
        assertThat(vicky.getRemainingAmount()).isEqualByComparingTo("15000.00");
        assertThat(vicky.getOverpaidAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void multiMonth_missingMonthDoesNotFabricateExpected_periodsCountedReflectsPersisted() {
        save(memberA, 2026, 1, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 3, "10000", "10000", "0", ContributionStatus.PAID);
        // Feb missing — Jan–Mar selected span is 3, periods counted = 2

        List<MemberContributionAggregate> rows = reportService.loadFullFinancialContributionAggregates(
                cooperativeId,
                ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(LocalDate.of(2026, 1, 1))
                        .toDate(LocalDate.of(2026, 3, 31))
                        .build()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPeriodsCounted()).isEqualTo(2);
        assertThat(rows.get(0).getExpectedAmount()).isEqualByComparingTo("20000.00");
    }

    @Test
    void multiMonth_overpaymentAndWaivedAndCancelledRules() {
        save(memberA, 2026, 1, "10000", "15000", "0", ContributionStatus.PAID); // overpay 5k
        save(memberA, 2026, 2, "10000", "0", "0", ContributionStatus.WAIVED); // expected 0
        save(memberA, 2026, 3, "10000", "0", "10000", ContributionStatus.CANCELLED); // excluded

        List<MemberContributionAggregate> rows = reportService.loadFullFinancialContributionAggregates(
                cooperativeId,
                ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(LocalDate.of(2026, 1, 1))
                        .toDate(LocalDate.of(2026, 3, 31))
                        .build()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPeriodsCounted()).isEqualTo(2); // paid + waived
        assertThat(rows.get(0).getExpectedAmount()).isEqualByComparingTo("10000.00");
        assertThat(rows.get(0).getPaidAmount()).isEqualByComparingTo("15000.00");
        assertThat(rows.get(0).getRemainingAmount()).isEqualByComparingTo("0.00");
        assertThat(rows.get(0).getOverpaidAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void multiMonth_twoMembersIndependent_andYearBoundary() {
        save(memberA, 2025, 12, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 1, "10000", "4000", "6000", ContributionStatus.PARTIALLY_PAID);
        save(memberB, 2025, 12, "8000", "8000", "0", ContributionStatus.PAID);
        save(memberB, 2026, 1, "8000", "0", "8000", ContributionStatus.PENDING);

        List<MemberContributionAggregate> rows = reportService.loadFullFinancialContributionAggregates(
                cooperativeId,
                ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(LocalDate.of(2025, 12, 1))
                        .toDate(LocalDate.of(2026, 1, 31))
                        .build()));

        assertThat(rows).hasSize(2);
        MemberContributionAggregate a = rows.stream()
                .filter(r -> r.getMemberUserId().equals(memberA))
                .findFirst()
                .orElseThrow();
        MemberContributionAggregate b = rows.stream()
                .filter(r -> r.getMemberUserId().equals(memberB))
                .findFirst()
                .orElseThrow();
        assertThat(a.getExpectedAmount()).isEqualByComparingTo("20000.00");
        assertThat(a.getPaidAmount()).isEqualByComparingTo("14000.00");
        assertThat(a.getRemainingAmount()).isEqualByComparingTo("6000.00");
        assertThat(b.getExpectedAmount()).isEqualByComparingTo("16000.00");
        assertThat(b.getPaidAmount()).isEqualByComparingTo("8000.00");
        assertThat(b.getRemainingAmount()).isEqualByComparingTo("8000.00");
    }

    @Test
    void singleMonth_usesDetailModeWithStatus() {
        save(memberA, 2026, 5, "10000", "3000", "7000", ContributionStatus.PARTIALLY_PAID);
        save(memberB, 2026, 5, "8000", "8000", "0", ContributionStatus.PAID);

        List<MemberContributionAggregate> rows = reportService.loadFullFinancialContributionAggregates(
                cooperativeId,
                ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(LocalDate.of(2026, 5, 1))
                        .toDate(LocalDate.of(2026, 5, 31))
                        .build()));

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getStatus() != null && !r.getStatus().isBlank());
        assertThat(rows.stream().map(MemberContributionAggregate::getStatus))
                .containsExactlyInAnyOrder("PARTIALLY_PAID", "PAID");
    }

    @Test
    void inactiveMemberHistoricalObligationsStillIncluded() throws Exception {
        save(memberA, 2026, 2, "10000", "10000", "0", ContributionStatus.PAID);
        // Mark membership inactive via SQL-free path: leave contribution persisted
        membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberA)
                .ifPresent(m -> {
                    m.setMembershipStatus("INACTIVE");
                    membershipRepository.saveAndFlush(m);
                });

        List<MemberContributionAggregate> rows = reportService.loadFullFinancialContributionAggregates(
                cooperativeId,
                ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(LocalDate.of(2026, 1, 1))
                        .toDate(LocalDate.of(2026, 3, 31))
                        .build()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMemberUserId()).isEqualTo(memberA);
        assertThat(rows.get(0).getExpectedAmount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void summaryTotalsReconcileWithMemberAggregates() {
        save(memberA, 2026, 1, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 2, "10000", "2500", "7500", ContributionStatus.PARTIALLY_PAID);
        save(memberB, 2026, 1, "8000", "0", "8000", ContributionStatus.PENDING);
        save(memberB, 2026, 2, "8000", "8000", "0", ContributionStatus.PAID);

        List<MemberContributionAggregate> rows = reportService.loadFullFinancialContributionAggregates(
                cooperativeId,
                ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(LocalDate.of(2026, 1, 1))
                        .toDate(LocalDate.of(2026, 2, 28))
                        .build()));

        BigDecimal expected = rows.stream()
                .map(MemberContributionAggregate::getExpectedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = rows.stream()
                .map(MemberContributionAggregate::getPaidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = rows.stream()
                .map(MemberContributionAggregate::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(rows).hasSize(2);
        assertThat(expected).isEqualByComparingTo("36000.00");
        assertThat(paid).isEqualByComparingTo("20500.00");
        assertThat(remaining).isEqualByComparingTo("15500.00");
    }

    @Test
    void importedHistoricalContributionIncludedLikeNormalRows() {
        Contribution imported = Contribution.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberA)
                .year(2025)
                .month(11)
                .shareCount(1)
                .expectedAmount(money("12000"))
                .paidAmount(money("12000"))
                .outstandingAmount(money("0"))
                .status(ContributionStatus.PAID)
                .paymentDate(LocalDate.of(2025, 11, 5))
                .notes("Imported historical obligation")
                .ledgerRevision(0)
                .build();
        contributionRepository.saveAndFlush(imported);

        List<MemberContributionAggregate> rows = reportService.loadFullFinancialContributionAggregates(
                cooperativeId,
                ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(LocalDate.of(2025, 11, 1))
                        .toDate(LocalDate.of(2025, 11, 30))
                        .build()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getExpectedAmount()).isEqualByComparingTo("12000.00");
        assertThat(rows.get(0).getPaidAmount()).isEqualByComparingTo("12000.00");
    }

    @Test
    void fullFinancialPdf_cumulativeModeContainsMemberOnce() throws Exception {
        for (int month = 1; month <= 4; month++) {
            save(memberA, 2026, month, "10000", "10000", "0", ContributionStatus.PAID);
        }

        List<MemberContributionAggregate> aggregates = reportService.loadFullFinancialContributionAggregates(
                cooperativeId,
                ContributionObligationPeriod.resolve(ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(LocalDate.of(2026, 1, 1))
                        .toDate(LocalDate.of(2026, 4, 30))
                        .build()));
        assertThat(aggregates).hasSize(1);
        assertThat(aggregates.get(0).getPeriodsCounted()).isEqualTo(4);
        assertThat(aggregates.get(0).getMemberName()).contains("Vicky");

        MvcResult export = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "reportType":"FULL_FINANCIAL",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-04-30"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] pdf = export.getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        // Candara Identity-H embeds glyphs; assert branding font + PDF success rather than WinAnsi title text.
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("Candara");
    }

    @Test
    void standaloneContributionsReportStillUsesDetailRows() throws Exception {
        save(memberA, 2026, 1, "10000", "10000", "0", ContributionStatus.PAID);
        save(memberA, 2026, 2, "10000", "10000", "0", ContributionStatus.PAID);

        // Standalone CONTRIBUTIONS still lists each obligation row (year/month detail), not cumulative.
        var detail = contributionRepository.search(
                cooperativeId, null, null, null, null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 28),
                org.springframework.data.domain.Pageable.unpaged());
        assertThat(detail.getContent()).hasSize(2);
        assertThat(detail.getContent().stream().map(Contribution::getMonth).toList())
                .containsExactlyInAnyOrder(1, 2);

        MvcResult export = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/reports/export")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "reportType":"CONTRIBUTIONS",
                                  "fromDate":"2026-01-01",
                                  "toDate":"2026-02-28"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] pdf = export.getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    private void save(
            UUID memberId,
            int year,
            int month,
            String expected,
            String paid,
            String outstanding,
            ContributionStatus status) {
        contributionRepository.saveAndFlush(Contribution.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .year(year)
                .month(month)
                .shareCount(1)
                .expectedAmount(money(expected))
                .paidAmount(money(paid))
                .outstandingAmount(money(outstanding))
                .status(status)
                .paymentDate(status == ContributionStatus.PENDING || status == ContributionStatus.CANCELLED
                        ? null
                        : LocalDate.of(year, month, 10))
                .ledgerRevision(0)
                .build());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(4);
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

    private UUID registerMember(UUID coopId, String username, String first, String last) throws Exception {
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
                                  "roleInCooperative":"MEMBER",
                                  "shareCount": 1
                                }
                                """
                                        .formatted(first, last, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        UUID userId = UUID.fromString(data.path("userId").asText());
        OpeningShareBalances.set(membershipRepository, coopId, userId, 1);
        return userId;
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
}
