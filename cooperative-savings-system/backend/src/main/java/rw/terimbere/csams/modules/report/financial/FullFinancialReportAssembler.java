package rw.terimbere.csams.modules.report.financial;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.report.dto.MemberContributionAggregate;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.modules.report.service.ReportTimelineValidator;
import rw.terimbere.csams.modules.report.support.ContributionObligationPeriod;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.entity.SharePurchase;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;
import rw.terimbere.csams.modules.share.service.ShareValuationService;
import rw.terimbere.csams.modules.socialfund.service.SocialFundBalanceService;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContribution;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class FullFinancialReportAssembler {

    private static final DateTimeFormatter LONG_DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final ZoneId ZONE = ReportTimelineValidator.ZONE;
    /** Zero convention: omit zero activity lines; always show section totals. */
    private static final boolean OMIT_ZERO_LINES = true;

    private final LedgerPeriodAggregationService ledgerPeriodAggregationService;
    private final ShareValuationService shareValuationService;
    private final rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository openingBalanceRepository;
    private final SocialFundBalanceService socialFundBalanceService;
    private final MemberContributionScheduleService memberContributionScheduleService;
    private final SharePurchaseRepository sharePurchaseRepository;
    private final SpecialContributionRepository specialContributionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public FullFinancialReportModel assemble(
            Cooperative cooperative, ReportExportRequest request, String generatedBy, Instant generatedAt) {
        LocalDate from = request.getFromDate();
        LocalDate to = request.getToDate();
        Instant generated = generatedAt == null ? Instant.now() : generatedAt;
        LocalDate snapshotAsOf = LocalDate.ofInstant(generated, ZONE);

        LedgerPeriodTotals ledger = ledgerPeriodAggregationService.aggregateMainFund(cooperative.getId(), from, to);
        ShareValuationResponse valuation = shareValuationService.calculate(cooperative);
        BigDecimal socialBalance = socialFundBalanceService.calculateBalance(cooperative.getId());

        ContributionObligationPeriod.Range obligationRange = ContributionObligationPeriod.resolve(request);
        List<MemberContributionAggregate> contributionRows =
                memberContributionScheduleService.loadAggregates(cooperative.getId(), obligationRange);
        FullFinancialReportModel.MemberContributionSection contributions =
                memberContributionScheduleService.toSection(obligationRange, contributionRows);

        FullFinancialReportModel.OperatingStatement operating = buildOperating(from, to, ledger);
        FullFinancialReportModel.CashFlowStatement cashFlow = buildCashFlow(cooperative.getId(), from, to, ledger);
        FullFinancialReportModel.FinancialPosition position =
                buildPosition(valuation, socialBalance, generated, snapshotAsOf);

        List<FullFinancialReportModel.SummaryMetric> summary = buildExecutiveSummary(
                contributions, operating, cashFlow, valuation);

        return FullFinancialReportModel.builder()
                .header(FullFinancialReportModel.Header.builder()
                        .brandName("OuWealth Community")
                        .cooperativeName(cooperative.getName())
                        .reportTitle(ReportType.FULL_FINANCIAL.getLabel())
                        .periodFrom(from)
                        .periodTo(to)
                        .periodLabel(formatLongPeriod(from, to))
                        .generatedAt(generated)
                        .snapshotAsOf(snapshotAsOf)
                        .currency(cooperative.getCurrency() == null ? "RWF" : cooperative.getCurrency())
                        .generatedBy(generatedBy)
                        .build())
                .executiveSummary(summary)
                .currentPosition(position)
                .operatingStatement(operating)
                .cashFlow(cashFlow)
                .memberContributions(contributions)
                .supportingSchedules(buildSupportingSchedules(cooperative.getId(), request))
                .notes(List.of(
                        "Financial Position is a current snapshot as of "
                                + LONG_DATE.format(snapshotAsOf)
                                + ", not a historical reconstruction as of the reporting period end date.",
                        "Member contributions and share purchases are Saving Scheme funding (member capital), not operating income.",
                        "Social Fund is maintained separately from the main Saving Scheme fund.",
                        "Formal liabilities are not currently modeled as a complete payable ledger in OuWealth.",
                        "Opening and Closing Ledger Balance = approved main-fund ledger credits minus debits (excludes Social Fund). This differs from Available Funds, which adjusts for outstanding loans and lending capacity.",
                        "A reversed receipt stays in the period it was received; its reversal appears as an offsetting line in the period the reversal was made.",
                        "Zero-activity statement lines are omitted; section totals are always shown."))
                .build();
    }

    private FullFinancialReportModel.OperatingStatement buildOperating(
            LocalDate from, LocalDate to, LedgerPeriodTotals ledger) {
        List<FullFinancialReportModel.StatementLine> income = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        for (LedgerTransactionType type : FullFinancialLedgerClassifications.OPERATING_INCOME_TYPES) {
            BigDecimal amount = ledger.credit(type);
            totalIncome = MoneyUtils.add(totalIncome, amount);
            addLineIfShown(income, FullFinancialLedgerClassifications.label(type), amount);
        }

        List<FullFinancialReportModel.StatementLine> expenses = new ArrayList<>();
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (LedgerTransactionType type : FullFinancialLedgerClassifications.OPERATING_EXPENSE_TYPES) {
            BigDecimal amount = ledger.debit(type);
            totalExpense = MoneyUtils.add(totalExpense, amount);
            addLineIfShown(expenses, FullFinancialLedgerClassifications.label(type), amount);
        }

        BigDecimal net = MoneyUtils.subtract(totalIncome, totalExpense);
        String netLabel = net.compareTo(BigDecimal.ZERO) >= 0 ? "Net Operating Surplus" : "Net Operating Deficit";

        return FullFinancialReportModel.OperatingStatement.builder()
                .periodFrom(from)
                .periodTo(to)
                .incomeLines(income)
                .totalOperatingIncome(MoneyUtils.scale(totalIncome))
                .expenseLines(expenses)
                .totalOperatingExpenses(MoneyUtils.scale(totalExpense))
                .netOperatingResult(MoneyUtils.scale(net))
                .netResultLabel(netLabel)
                .build();
    }

    private FullFinancialReportModel.CashFlowStatement buildCashFlow(
            UUID cooperativeId, LocalDate from, LocalDate to, LedgerPeriodTotals ledger) {
        List<FullFinancialReportModel.StatementLine> inflows = new ArrayList<>();
        BigDecimal totalIn = BigDecimal.ZERO;
        for (LedgerTransactionType type : FullFinancialLedgerClassifications.CASH_INFLOW_TYPES) {
            BigDecimal amount = ledger.credit(type);
            totalIn = MoneyUtils.add(totalIn, amount);
            addLineIfShown(inflows, FullFinancialLedgerClassifications.label(type), amount);
        }

        // Adjustments that credit cash
        BigDecimal adjCredit = ledger.credit(LedgerTransactionType.ADJUSTMENT);
        if (adjCredit.compareTo(BigDecimal.ZERO) > 0) {
            totalIn = MoneyUtils.add(totalIn, adjCredit);
            addLineIfShown(inflows, "Adjustments (credits)", adjCredit);
        }
        // Reversals appear on their own line, in the period they were made: never as an expense or an adjustment.
        BigDecimal reversalCredit = ledger.credit(LedgerTransactionType.REVERSAL);
        if (reversalCredit.compareTo(BigDecimal.ZERO) > 0) {
            totalIn = MoneyUtils.add(totalIn, reversalCredit);
            addLineIfShown(inflows, "Reversals (credits)", reversalCredit);
        }

        List<FullFinancialReportModel.StatementLine> outflows = new ArrayList<>();
        BigDecimal totalOut = BigDecimal.ZERO;
        for (LedgerTransactionType type : FullFinancialLedgerClassifications.CASH_OUTFLOW_TYPES) {
            BigDecimal amount = ledger.debit(type);
            totalOut = MoneyUtils.add(totalOut, amount);
            addLineIfShown(outflows, FullFinancialLedgerClassifications.label(type), amount);
        }
        BigDecimal adjDebit = ledger.debit(LedgerTransactionType.ADJUSTMENT);
        if (adjDebit.compareTo(BigDecimal.ZERO) > 0) {
            totalOut = MoneyUtils.add(totalOut, adjDebit);
            addLineIfShown(outflows, "Adjustments (debits)", adjDebit);
        }
        BigDecimal reversalDebit = ledger.debit(LedgerTransactionType.REVERSAL);
        if (reversalDebit.compareTo(BigDecimal.ZERO) > 0) {
            totalOut = MoneyUtils.add(totalOut, reversalDebit);
            addLineIfShown(outflows, "Reversals of receipts (debits)", reversalDebit);
        }

        BigDecimal opening = ledger.getOpeningLedgerBalance();
        BigDecimal closing = ledger.getClosingLedgerBalance();
        BigDecimal netMovement = ledger.netCashMovement();
        BigDecimal expectedClosing = MoneyUtils.add(opening, netMovement);
        boolean holds = expectedClosing.compareTo(closing) == 0;

        // Social fund period net (separate footnote; not mixed into main cash flow totals)
        BigDecimal socialIn = ledgerPeriodAggregationService.sumApprovedCreditsInPeriod(
                cooperativeId,
                from,
                to,
                EnumSet.of(LedgerTransactionType.SOCIAL_CONTRIBUTION));
        BigDecimal socialOut = ledgerPeriodAggregationService.sumApprovedDebitsInPeriod(
                cooperativeId,
                from,
                to,
                EnumSet.of(LedgerTransactionType.SOCIAL_DISBURSEMENT));
        BigDecimal socialNet = MoneyUtils.subtract(socialIn, socialOut);

        return FullFinancialReportModel.CashFlowStatement.builder()
                .periodFrom(from)
                .periodTo(to)
                .inflowLines(inflows)
                .totalInflows(MoneyUtils.scale(totalIn))
                .outflowLines(outflows)
                .totalOutflows(MoneyUtils.scale(totalOut))
                .openingLedgerBalance(opening)
                .netCashMovement(MoneyUtils.scale(netMovement))
                .closingLedgerBalance(closing)
                .reconciliationHolds(holds)
                .socialFundPeriodNet(socialNet)
                .socialFundNote(
                        "Social Fund period net (contributions − disbursements) is separate from main-fund cash flow.")
                .build();
    }

    private List<String> reconciliationNotes(ShareValuationResponse valuation) {
        List<String> notes = new ArrayList<>(List.of(
                "Do not interpret Available Funds alone as total assets.",
                "Social Fund Balance is reported separately and is not included in Current Ikimina Value."));
        BigDecimal openingContributions = openingBalanceRepository.sumOpeningContributions(valuation.getCooperativeId());
        BigDecimal openingCapital = openingBalanceRepository.sumOpeningShareCapital(valuation.getCooperativeId());
        BigDecimal opening = MoneyUtils.add(
                MoneyUtils.scale(openingContributions == null ? BigDecimal.ZERO : openingContributions),
                MoneyUtils.scale(openingCapital == null ? BigDecimal.ZERO : openingCapital));
        if (opening.signum() > 0) {
            notes.add("Available Funds start from the explicit cash position entered at the migration cutover, then"
                    + " add later ledger activity. Opening member contributions and share capital ("
                    + opening
                    + ") are lifetime member balances: they are reported separately and are neither cash nor"
                    + " period transactions.");
        }
        return notes;
    }

    private FullFinancialReportModel.FinancialPosition buildPosition(
            ShareValuationResponse valuation,
            BigDecimal socialBalance,
            Instant generated,
            LocalDate snapshotAsOf) {
        List<FullFinancialReportModel.StatementLine> assets = new ArrayList<>();
        assets.add(line(
                "Available Funds (lending-capacity measure)",
                valuation.getAvailableFunds(),
                "LINE"));
        assets.add(line("Outstanding Loan Principal", valuation.getOutstandingLoans(), "LINE"));
        assets.add(line("Outstanding Loan Interest Receivable", valuation.getUnpaidInterest(), "LINE"));
        assets.add(line("Fine Receivables", valuation.getUnpaidPenalties(), "LINE"));
        assets.add(line("Investment Capital Deployed", valuation.getOtherAssets(), "LINE"));
        assets.add(line("Current Ikimina Value (Net Position)", valuation.getTotalIkiminaValue(), "TOTAL"));

        return FullFinancialReportModel.FinancialPosition.builder()
                .snapshotAt(generated)
                .snapshotAsOfDate(snapshotAsOf)
                .assetLines(assets)
                .currentIkiminaValue(valuation.getTotalIkiminaValue())
                .ikiminaValueNote(
                        "Current Ikimina Value = Available Funds + Outstanding Loan Principal + Interest Receivable + Fine Receivables + Investment Capital. Available Funds already deducts outstanding loan principal for lending capacity; adding principal back reconstructs total scheme value.")
                .socialFundBalance(socialBalance)
                .liabilitiesNote(
                        "Formal liabilities are not currently modeled as a complete payable ledger in OuWealth. No liability total is shown.")
                .reconciliationNotes(reconciliationNotes(valuation))
                .build();
    }

    private List<FullFinancialReportModel.SummaryMetric> buildExecutiveSummary(
            FullFinancialReportModel.MemberContributionSection contributions,
            FullFinancialReportModel.OperatingStatement operating,
            FullFinancialReportModel.CashFlowStatement cashFlow,
            ShareValuationResponse valuation) {
        List<FullFinancialReportModel.SummaryMetric> metrics = new ArrayList<>();
        metrics.add(metric("Member Contributions Paid", contributions.getTotalPaid(), "PERIOD"));
        metrics.add(metric("Operating Income", operating.getTotalOperatingIncome(), "PERIOD"));
        metrics.add(metric("Operating Expenses", operating.getTotalOperatingExpenses(), "PERIOD"));
        metrics.add(metric(operating.getNetResultLabel(), operating.getNetOperatingResult(), "PERIOD"));
        metrics.add(metric("Net Cash Movement", cashFlow.getNetCashMovement(), "PERIOD"));
        metrics.add(metric("Outstanding Loan Principal", valuation.getOutstandingLoans(), "SNAPSHOT"));
        metrics.add(metric("Investment Capital Deployed", valuation.getOtherAssets(), "SNAPSHOT"));
        metrics.add(metric("Fine Receivables", valuation.getUnpaidPenalties(), "SNAPSHOT"));
        metrics.add(metric("Current Ikimina Value", valuation.getTotalIkiminaValue(), "SNAPSHOT"));
        return metrics;
    }

    private List<ReportSheetData> buildSupportingSchedules(UUID cooperativeId, ReportExportRequest request) {
        List<ReportSheetData> sheets = new ArrayList<>();
        sheets.add(sharePurchasesAppendix(cooperativeId, request));
        sheets.add(specialContributionsAppendix(cooperativeId, request));
        sheets.add(ledgerAppendix(cooperativeId, request));
        return sheets;
    }

    private ReportSheetData sharePurchasesAppendix(UUID cooperativeId, ReportExportRequest request) {
        Instant periodFrom = request.getFromDate().atStartOfDay(ZONE).toInstant();
        Instant periodTo = request.getToDate().plusDays(1).atStartOfDay(ZONE).toInstant().minusNanos(1);
        List<SharePurchase> purchases = sharePurchaseRepository.findByCooperativeIdAndStatusAndReviewedAtBetween(
                cooperativeId, SharePurchaseStatus.APPROVED, periodFrom, periodTo);
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal amountTotal = BigDecimal.ZERO;
        for (SharePurchase p : purchases) {
            amountTotal = MoneyUtils.add(amountTotal, nvl(p.getTotalAmount()));
            rows.add(List.of(
                    memberName(p.getMemberUserId()),
                    p.getNumberOfShares() == null ? 0 : p.getNumberOfShares(),
                    MoneyUtils.scale(nvl(p.getTotalAmount())),
                    p.getReviewedAt() == null ? "" : p.getReviewedAt().toString()));
        }
        return ReportSheetData.builder()
                .sheetName("Appendix — Additional Shares")
                .headers(List.of("Member", "Shares", "Amount", "Approved At"))
                .rows(rows)
                .totalsRow(List.of("TOTAL", "", MoneyUtils.scale(amountTotal), ""))
                .build();
    }

    private ReportSheetData specialContributionsAppendix(UUID cooperativeId, ReportExportRequest request) {
        List<SpecialContribution> list = specialContributionRepository.findFiltered(
                cooperativeId, null, null, request.getFromDate(), request.getToDate());
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SpecialContribution s : list) {
            total = MoneyUtils.add(total, nvl(s.getAmount()));
            rows.add(List.of(
                    memberName(s.getMemberUserId()),
                    MoneyUtils.scale(nvl(s.getAmount())),
                    s.getStatus() == null ? "" : s.getStatus().name(),
                    s.getContributionDate() == null ? "" : s.getContributionDate().toString()));
        }
        return ReportSheetData.builder()
                .sheetName("Appendix — Special Contributions")
                .headers(List.of("Member", "Amount", "Status", "Contribution Date"))
                .rows(rows)
                .totalsRow(List.of("TOTAL", MoneyUtils.scale(total), "", ""))
                .build();
    }

    private ReportSheetData ledgerAppendix(UUID cooperativeId, ReportExportRequest request) {
        List<LedgerEntry> entries = ledgerEntryRepository
                .findFiltered(
                        cooperativeId,
                        null,
                        request.getFromDate(),
                        request.getToDate(),
                        null,
                        null,
                        Pageable.unpaged())
                .getContent();
        List<List<Object>> rows = new ArrayList<>();
        int limit = Math.min(entries.size(), 400);
        for (int i = 0; i < limit; i++) {
            LedgerEntry e = entries.get(i);
            rows.add(List.of(
                    e.getTransactionDate(),
                    e.getTransactionType() == null ? "" : e.getTransactionType().name(),
                    MoneyUtils.scale(nvl(e.getDebitAmount())),
                    MoneyUtils.scale(nvl(e.getCreditAmount())),
                    e.getStatus() == null ? "" : e.getStatus().name(),
                    e.getDescription() == null ? "" : e.getDescription()));
        }
        String name = entries.size() > limit
                ? "Appendix — Financial Ledger (first " + limit + " of " + entries.size() + ")"
                : "Appendix — Financial Ledger";
        return ReportSheetData.builder()
                .sheetName(name)
                .headers(List.of("Date", "Type", "Debit", "Credit", "Status", "Description"))
                .rows(rows)
                .build();
    }

    private String memberName(UUID userId) {
        if (userId == null) {
            return "";
        }
        return userRepository.findById(userId).map(User::getFullName).orElse("");
    }

    private static void addLineIfShown(
            List<FullFinancialReportModel.StatementLine> lines, String label, BigDecimal amount) {
        if (OMIT_ZERO_LINES && MoneyUtils.isZero(amount)) {
            return;
        }
        lines.add(line(label, amount, "LINE"));
    }

    private static FullFinancialReportModel.StatementLine line(String label, BigDecimal amount, String kind) {
        return FullFinancialReportModel.StatementLine.builder()
                .label(label)
                .amount(MoneyUtils.scale(amount == null ? BigDecimal.ZERO : amount))
                .kind(kind)
                .build();
    }

    private static FullFinancialReportModel.SummaryMetric metric(String label, BigDecimal amount, String scope) {
        return FullFinancialReportModel.SummaryMetric.builder()
                .label(label)
                .amount(MoneyUtils.scale(amount == null ? BigDecimal.ZERO : amount))
                .scope(scope)
                .build();
    }

    private static String formatLongPeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return "";
        }
        return LONG_DATE.format(from) + " – " + LONG_DATE.format(to);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
