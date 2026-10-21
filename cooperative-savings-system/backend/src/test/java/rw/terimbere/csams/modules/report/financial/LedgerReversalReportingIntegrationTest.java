package rw.terimbere.csams.modules.report.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.OpeningShareBalances;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Period-accurate reversal reporting. A reversed original stays visible in the period of its own transaction date,
 * the linked REVERSAL appears as the offsetting movement in the period of the reversal's transaction date, and
 * the pair nets to zero cumulatively. Operational balances are untouched by this reporting rule.
 *
 * <p>Reversals here are real linked rows: the original flips to REVERSED and an APPROVED REVERSAL row referencing
 * it is appended through {@link LedgerService#appendApproved}, which is exactly what
 * {@code reverseApprovedCredit} writes (apart from the reversal date, which that method fixes to "today").
 * One test uses {@code reverseApprovedCredit} itself.
 *
 * <p>Runs on H2 in PostgreSQL mode; PostgreSQL-specific behavior is not verified here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerReversalReportingIntegrationTest {

    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LedgerPeriodAggregationService aggregationService;
    @Autowired private FullFinancialReportAssembler assembler;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private LedgerFinancialCalculationService fundService;
    @Autowired private CooperativeRepository cooperativeRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private CooperativeMembershipRepository membershipRepository;

    private String superAdminToken;
    private UUID cooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        superAdminToken = login();
        cooperativeId = createCooperative("Rev Report " + UUID.randomUUID().toString().substring(0, 8));
    }

    // ---------------------------------------------------------------- tests 1-4

    @Test
    void sameMonthReversalShowsBothMovementsAndNetsToZero() {
        LedgerEntry original = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", LocalDate.of(2026, 9, 3));
        reverse(original, LocalDate.of(2026, 9, 20));

        LedgerPeriodTotals september = totals(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(september.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("30000");
        assertThat(september.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("30000");
        assertThat(september.netCashMovement()).isEqualByComparingTo("0");
        assertThat(september.getOpeningLedgerBalance()).isEqualByComparingTo("0");
        assertThat(september.getClosingLedgerBalance()).isEqualByComparingTo("0");
    }

    @Test
    void crossMonthReversalKeepsSeptemberAndOffsetsInOctober() {
        LedgerEntry original = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", SEP_10);
        reverse(original, OCT_5);

        LedgerPeriodTotals september = totals(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(september.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("30000");
        assertThat(september.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("0");
        assertThat(september.netCashMovement()).isEqualByComparingTo("30000");
        assertThat(september.getClosingLedgerBalance()).isEqualByComparingTo("30000");

        LedgerPeriodTotals october = totals(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(october.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("0");
        assertThat(october.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("30000");
        assertThat(october.netCashMovement()).isEqualByComparingTo("-30000");
        assertThat(october.getOpeningLedgerBalance()).isEqualByComparingTo("30000");
        assertThat(october.getClosingLedgerBalance()).isEqualByComparingTo("0");

        LedgerPeriodTotals cumulative = totals(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 31));
        assertThat(cumulative.netCashMovement()).isEqualByComparingTo("0");
        assertThat(cumulative.getClosingLedgerBalance()).isEqualByComparingTo("0");
    }

    @Test
    void crossYearReversalAcrossTheYearBoundary() {
        LedgerEntry original = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", LocalDate.of(2025, 12, 12));
        reverse(original, LocalDate.of(2026, 1, 8));

        LedgerPeriodTotals december = totals(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31));
        assertThat(december.netCashMovement()).isEqualByComparingTo("30000");
        assertThat(december.getClosingLedgerBalance()).isEqualByComparingTo("30000");

        LedgerPeriodTotals january = totals(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertThat(january.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("30000");
        assertThat(january.netCashMovement()).isEqualByComparingTo("-30000");
        assertThat(january.getOpeningLedgerBalance()).isEqualByComparingTo("30000");
        assertThat(january.getClosingLedgerBalance()).isEqualByComparingTo("0");

        assertThat(totals(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 31)).getClosingLedgerBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void openingPlusMovementEqualsClosingBeforeAndAfterTheReversalDate() {
        LedgerEntry original = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", SEP_10);
        credit(LedgerTransactionType.OTHER_INCOME, "1000", LocalDate.of(2026, 8, 15));
        reverse(original, OCT_5);

        List<LocalDate[]> windows = List.of(
                new LocalDate[] {LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)},
                new LocalDate[] {LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)},
                new LocalDate[] {LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4)},
                new LocalDate[] {LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)},
                new LocalDate[] {LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)},
                new LocalDate[] {LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)});
        BigDecimal[] expectedClosing = {
            new BigDecimal("1000"),
            new BigDecimal("31000"),
            new BigDecimal("31000"),
            new BigDecimal("1000"),
            new BigDecimal("1000"),
            new BigDecimal("1000")
        };
        for (int i = 0; i < windows.size(); i++) {
            LedgerPeriodTotals t = totals(windows.get(i)[0], windows.get(i)[1]);
            assertThat(MoneyUtils.add(t.getOpeningLedgerBalance(), t.netCashMovement()))
                    .as("window %d reconciles", i)
                    .isEqualByComparingTo(t.getClosingLedgerBalance());
            assertThat(t.getClosingLedgerBalance()).as("window %d closing", i).isEqualByComparingTo(expectedClosing[i]);
        }
    }

    // ---------------------------------------------------------------- test 5

    @Test
    void oneReversedAndOneApprovedContributionLeaveOnlyTheApprovedOneCumulatively() {
        LedgerEntry reversed = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", LocalDate.of(2026, 9, 5));
        credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "20000", LocalDate.of(2026, 9, 12));
        reverse(reversed, OCT_5);

        LedgerPeriodTotals september = totals(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(september.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("50000");
        assertThat(september.getClosingLedgerBalance()).isEqualByComparingTo("50000");

        LedgerPeriodTotals october = totals(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(october.netCashMovement()).isEqualByComparingTo("-30000");
        assertThat(october.getClosingLedgerBalance()).isEqualByComparingTo("20000");
        assertThat(totals(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 31)).getClosingLedgerBalance())
                .isEqualByComparingTo("20000");
    }

    // ---------------------------------------------------------------- test 6

    @Test
    void aReversedOriginalWithoutALinkedReversalIsNotReportedAndNothingIsInvented() {
        LedgerEntry legacy = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", SEP_10);
        legacy.setStatus(LedgerEntryStatus.REVERSED);
        ledgerEntryRepository.saveAndFlush(legacy);

        LedgerPeriodTotals t = totals(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        // Defined behavior: without a valid reversal row the original is excluded (as the operational rules do) and
        // no reversal movement is fabricated. The anomaly needs data repair, not a reporting workaround.
        assertThat(t.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("0");
        assertThat(t.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("0");
        assertThat(t.getClosingLedgerBalance()).isEqualByComparingTo("0");
    }

    @Test
    void anOrphanedReversalIsNotReported() throws Exception {
        // A reversal pointing at an entry that does not exist cannot even be stored (foreign key fk_ledger_reverses).
        assertThatThrownBy(() -> reversalRow(UUID.randomUUID(), "30000", OCT_5))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A reversal in this cooperative pointing at another cooperative's REVERSED original is not a valid pair,
        // and must not leak that other tenant's amount into this cooperative's report.
        UUID other = createCooperative("Rev Other " + UUID.randomUUID().toString().substring(0, 8));
        LedgerEntry foreign = ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(other)
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .creditAmount(new BigDecimal("8000"))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(SEP_10)
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("rev-foreign-" + UUID.randomUUID())
                .description("other tenant")
                .build());
        foreign.setStatus(LedgerEntryStatus.REVERSED);
        ledgerEntryRepository.saveAndFlush(foreign);
        reversalRow(foreign.getId(), "8000", OCT_5);
        // Reversal row pointing at an original that is still APPROVED (never flagged REVERSED).
        LedgerEntry stillApproved = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "10000", SEP_10);
        reversalRow(stillApproved.getId(), "10000", OCT_5);
        // Reversal row whose amount does not match the original.
        LedgerEntry mismatch = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "5000", SEP_10);
        mismatch.setStatus(LedgerEntryStatus.REVERSED);
        ledgerEntryRepository.saveAndFlush(mismatch);
        reversalRow(mismatch.getId(), "4000", OCT_5);

        LedgerPeriodTotals t = totals(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        // Only the still-approved original counts; none of the unexplained reversal rows does.
        assertThat(t.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("10000");
        assertThat(t.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("0");
        assertThat(t.getClosingLedgerBalance()).isEqualByComparingTo("10000");
    }

    @Test
    void aSecondReversalOfTheSameOriginalIsNotCountedAgain() {
        LedgerEntry original = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", SEP_10);
        reverse(original, OCT_5);
        // A duplicate (anomalous) reversal row created later for the same original.
        reversalRow(original.getId(), "30000", LocalDate.of(2026, 10, 20));

        LedgerPeriodTotals t = totals(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(t.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("30000");
        assertThat(t.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("30000");
        assertThat(t.getClosingLedgerBalance()).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- test 7

    @Test
    void theRuleIsNotSpecificToContributions() {
        LedgerEntry fine = credit(LedgerTransactionType.FINE_PAYMENT, "2500", SEP_10);
        LedgerEntry share = credit(LedgerTransactionType.SHARE_PURCHASE, "7000", SEP_10);
        LedgerEntry interest = credit(LedgerTransactionType.LOAN_INTEREST_PAYMENT, "1500", SEP_10);
        reverse(fine, OCT_5);
        reverse(share, OCT_5);
        reverse(interest, OCT_5);

        LedgerPeriodTotals september = totals(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(september.credit(LedgerTransactionType.FINE_PAYMENT)).isEqualByComparingTo("2500");
        assertThat(september.credit(LedgerTransactionType.SHARE_PURCHASE)).isEqualByComparingTo("7000");
        assertThat(september.credit(LedgerTransactionType.LOAN_INTEREST_PAYMENT)).isEqualByComparingTo("1500");
        LedgerPeriodTotals october = totals(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(october.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("11000");
        assertThat(totals(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 31)).getClosingLedgerBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void theRealReversalServiceProducesALinkedPairThatReportsCorrectly() {
        LocalDate originalDate = LocalDate.now().minusDays(45);
        UUID sourceId = UUID.randomUUID();
        LedgerEntry original = ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(LedgerTransactionType.REGULAR_CONTRIBUTION)
                .creditAmount(new BigDecimal("30000"))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(originalDate)
                .sourceEntityType("TEST")
                .sourceEntityId(sourceId)
                .idempotencyKey("rev-real-" + UUID.randomUUID())
                .description("real service")
                .build());

        ledgerService.reverseApprovedCredit(
                "TEST",
                sourceId,
                LedgerTransactionType.REGULAR_CONTRIBUTION,
                "rev-real-r-" + UUID.randomUUID(),
                null,
                "reverse");

        LedgerEntry reversed = ledgerEntryRepository.findById(original.getId()).orElseThrow();
        assertThat(reversed.getStatus()).isEqualTo(LedgerEntryStatus.REVERSED);
        List<LedgerEntry> reversals = ledgerEntryRepository.findAll().stream()
                .filter(e -> original.getId().equals(e.getReversesEntryId()))
                .toList();
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).getTransactionType()).isEqualTo(LedgerTransactionType.REVERSAL);
        assertThat(reversals.get(0).getStatus()).isEqualTo(LedgerEntryStatus.APPROVED);

        LocalDate from = originalDate.withDayOfMonth(1);
        LocalDate to = LocalDate.now();
        LedgerPeriodTotals t = totals(from, to);
        assertThat(t.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("30000");
        assertThat(t.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("30000");
        assertThat(t.getClosingLedgerBalance()).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- test 8

    @Test
    void operationalBalancesAreUntouchedAndStillExcludeTheReversedOriginal() throws Exception {
        UUID memberId = registerMember();
        Contribution b = contributionRepository.saveAndFlush(contribution(memberId, 9, "20000"));
        credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "20000", SEP_10);
        LedgerEntry reversed = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", SEP_10);
        reverse(reversed, OCT_5);

        int shares = membershipRepository.findByCooperativeIdAndUserId(cooperativeId, memberId).orElseThrow().getShareCount();
        BigDecimal fundBefore = fundService.calculateAvailableGroupFund(cooperativeId);
        long ledgerRows = ledgerEntryRepository.count();

        // Run the reports (this is the code that changed).
        totals(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assemble(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));

        // Operational rule is unchanged: the reversed original and the REVERSAL row are not part of the fund.
        assertThat(fundBefore).isEqualByComparingTo("20000");
        assertThat(fundService.calculateAvailableGroupFund(cooperativeId)).isEqualByComparingTo("20000");
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerRows);
        assertThat(ledgerEntryRepository.findById(reversed.getId()).orElseThrow().getStatus())
                .isEqualTo(LedgerEntryStatus.REVERSED);
        Contribution after = contributionRepository.findById(b.getId()).orElseThrow();
        assertThat(after.getPaidAmount()).isEqualByComparingTo("20000");
        assertThat(after.getStatus()).isEqualTo(ContributionStatus.PAID);
        assertThat(membershipRepository.findByCooperativeIdAndUserId(cooperativeId, memberId).orElseThrow().getShareCount())
                .isEqualTo(shares);
    }

    // ---------------------------------------------------------------- test 9

    @Test
    void theFullFinancialReportShowsTheOriginalAndTheReversalInTheirOwnPeriods() {
        LedgerEntry original = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", SEP_10);
        reverse(original, OCT_5);

        FullFinancialReportModel september = assemble(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        FullFinancialReportModel.CashFlowStatement sepFlow = september.getCashFlow();
        assertThat(line(sepFlow.getInflowLines(), "Regular Contributions")).isEqualByComparingTo("30000");
        assertThat(sepFlow.getOutflowLines()).noneMatch(l -> l.getLabel().toLowerCase().contains("revers"));
        assertThat(sepFlow.getTotalInflows()).isEqualByComparingTo("30000");
        assertThat(sepFlow.getClosingLedgerBalance()).isEqualByComparingTo("30000");
        assertThat(sepFlow.isReconciliationHolds()).isTrue();

        FullFinancialReportModel october = assemble(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        FullFinancialReportModel.CashFlowStatement octFlow = october.getCashFlow();
        assertThat(octFlow.getInflowLines()).noneMatch(l -> l.getLabel().equals("Regular Contributions"));
        assertThat(line(octFlow.getOutflowLines(), "Reversals of receipts (debits)")).isEqualByComparingTo("30000");
        assertThat(octFlow.getTotalOutflows()).isEqualByComparingTo("30000");
        assertThat(octFlow.getNetCashMovement()).isEqualByComparingTo("-30000");
        assertThat(octFlow.getOpeningLedgerBalance()).isEqualByComparingTo("30000");
        assertThat(octFlow.getClosingLedgerBalance()).isEqualByComparingTo("0");
        assertThat(octFlow.isReconciliationHolds()).isTrue();

        // A reversal is never an ordinary business expense and never touches the operating result.
        FullFinancialReportModel.OperatingStatement operating = october.getOperatingStatement();
        assertThat(operating.getTotalOperatingExpenses()).isEqualByComparingTo("0");
        assertThat(operating.getExpenseLines()).isEmpty();
        assertThat(operating.getNetOperatingResult()).isEqualByComparingTo("0");

        // Both periods together reconcile to zero.
        FullFinancialReportModel both = assemble(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));
        assertThat(both.getCashFlow().getNetCashMovement()).isEqualByComparingTo("0");
        assertThat(both.getCashFlow().getClosingLedgerBalance()).isEqualByComparingTo("0");
        assertThat(both.getNotes()).anyMatch(n -> n.contains("reversed receipt stays in the period"));
    }

    // --------------------------------------------------------------- test 10

    @Test
    void anOriginalAndItsLinkedReversalEachCountExactlyOnce() {
        LedgerEntry original = credit(LedgerTransactionType.REGULAR_CONTRIBUTION, "30000", SEP_10);
        reverse(original, OCT_5);

        // One window containing both: each side appears once, never twice.
        LedgerPeriodTotals t = totals(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));
        assertThat(t.getPeriodCredits()).isEqualByComparingTo("30000");
        assertThat(t.getPeriodDebits()).isEqualByComparingTo("30000");
        assertThat(t.credit(LedgerTransactionType.REGULAR_CONTRIBUTION)).isEqualByComparingTo("30000");
        assertThat(t.debit(LedgerTransactionType.REVERSAL)).isEqualByComparingTo("30000");
        // And no other type picked up either entry.
        for (LedgerTransactionType type : LedgerTransactionType.values()) {
            if (type != LedgerTransactionType.REGULAR_CONTRIBUTION && type != LedgerTransactionType.REVERSAL) {
                assertThat(t.credit(type)).as(type + " credit").isEqualByComparingTo("0");
                assertThat(t.debit(type)).as(type + " debit").isEqualByComparingTo("0");
            }
        }
        // Splitting the window cannot change the total.
        LedgerPeriodTotals sep = totals(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        LedgerPeriodTotals oct = totals(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(MoneyUtils.add(sep.getPeriodCredits(), oct.getPeriodCredits())).isEqualByComparingTo("30000");
        assertThat(MoneyUtils.add(sep.getPeriodDebits(), oct.getPeriodDebits())).isEqualByComparingTo("30000");
    }

    // ---------------------------------------------------------------- helpers

    private LedgerPeriodTotals totals(LocalDate from, LocalDate to) {
        return aggregationService.aggregateMainFund(cooperativeId, from, to);
    }

    private FullFinancialReportModel assemble(LocalDate from, LocalDate to) {
        return assembler.assemble(
                cooperativeRepository.findById(cooperativeId).orElseThrow(),
                ReportExportRequest.builder()
                        .reportType(ReportType.FULL_FINANCIAL)
                        .fromDate(from)
                        .toDate(to)
                        .build(),
                "superadmin",
                Instant.now());
    }

    private static BigDecimal line(List<FullFinancialReportModel.StatementLine> lines, String label) {
        return lines.stream()
                .filter(l -> label.equals(l.getLabel()))
                .map(FullFinancialReportModel.StatementLine::getAmount)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /** Flips the original to REVERSED and appends the linked APPROVED REVERSAL debit, as the service does. */
    private void reverse(LedgerEntry original, LocalDate reversalDate) {
        original.setStatus(LedgerEntryStatus.REVERSED);
        ledgerEntryRepository.saveAndFlush(original);
        reversalRow(original.getId(), original.getCreditAmount().toPlainString(), reversalDate);
    }

    private LedgerEntry reversalRow(UUID reversesEntryId, String amount, LocalDate date) {
        return ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(LedgerTransactionType.REVERSAL)
                .debitAmount(new BigDecimal(amount))
                .creditAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(date)
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .reversesEntryId(reversesEntryId)
                .idempotencyKey("rev-row-" + UUID.randomUUID())
                .description("reversal")
                .build());
    }

    private LedgerEntry credit(LedgerTransactionType type, String amount, LocalDate date) {
        return ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(type)
                .creditAmount(new BigDecimal(amount))
                .debitAmount(BigDecimal.ZERO)
                .currency("RWF")
                .transactionDate(date)
                .sourceEntityType("TEST")
                .sourceEntityId(UUID.randomUUID())
                .idempotencyKey("rev-c-" + UUID.randomUUID())
                .description("credit")
                .build());
    }

    private Contribution contribution(UUID memberId, int month, String paid) {
        return Contribution.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .year(2026)
                .month(month)
                .shareCount(1)
                .expectedAmount(new BigDecimal(paid).setScale(4))
                .paidAmount(new BigDecimal(paid).setScale(4))
                .outstandingAmount(BigDecimal.ZERO.setScale(4))
                .status(ContributionStatus.PAID)
                .paymentDate(LocalDate.of(2026, month, 10))
                .ledgerRevision(0)
                .build();
    }

    private UUID registerMember() throws Exception {
        String username = "rvm_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Rev","lastName":"Member","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"MEMBER","shareCount":1}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper
                .readTree(register.getResponse().getContentAsString())
                .path("data")
                .path("userId")
                .asText());
        OpeningShareBalances.set(membershipRepository, cooperativeId, userId, 1);
        return userId;
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

    private String login() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"superadmin\",\"password\":\"ChangeMe@123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }
}
