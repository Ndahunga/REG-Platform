package rw.terimbere.csams.modules.report.financial;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import rw.terimbere.csams.modules.report.dto.MemberContributionAggregate;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;

/**
 * Structured Full Financial Report model (PDF now; Excel-ready later).
 *
 * <p>Period activity vs current snapshot are kept as separate sections.
 */
@Value
@Builder
public class FullFinancialReportModel {

    Header header;
    List<SummaryMetric> executiveSummary;
    FinancialPosition currentPosition;
    OperatingStatement operatingStatement;
    CashFlowStatement cashFlow;
    MemberContributionSection memberContributions;
    List<ReportSheetData> supportingSchedules;
    List<String> notes;

    @Value
    @Builder
    public static class Header {
        String brandName;
        String cooperativeName;
        String reportTitle;
        LocalDate periodFrom;
        LocalDate periodTo;
        String periodLabel;
        Instant generatedAt;
        LocalDate snapshotAsOf;
        String currency;
        String generatedBy;
    }

    @Value
    @Builder
    public static class SummaryMetric {
        String label;
        BigDecimal amount;
        /** {@code PERIOD} or {@code SNAPSHOT}. */
        String scope;
    }

    @Value
    @Builder
    public static class StatementLine {
        String label;
        BigDecimal amount;
        /** LINE, SUBTOTAL, TOTAL, HEADING, NOTE. */
        String kind;
    }

    @Value
    @Builder
    public static class FinancialPosition {
        Instant snapshotAt;
        LocalDate snapshotAsOfDate;
        List<StatementLine> assetLines;
        BigDecimal currentIkiminaValue;
        String ikiminaValueNote;
        BigDecimal socialFundBalance;
        String liabilitiesNote;
        List<String> reconciliationNotes;
    }

    @Value
    @Builder
    public static class OperatingStatement {
        LocalDate periodFrom;
        LocalDate periodTo;
        List<StatementLine> incomeLines;
        BigDecimal totalOperatingIncome;
        List<StatementLine> expenseLines;
        BigDecimal totalOperatingExpenses;
        BigDecimal netOperatingResult;
        String netResultLabel;
    }

    @Value
    @Builder
    public static class CashFlowStatement {
        LocalDate periodFrom;
        LocalDate periodTo;
        List<StatementLine> inflowLines;
        BigDecimal totalInflows;
        List<StatementLine> outflowLines;
        BigDecimal totalOutflows;
        BigDecimal openingLedgerBalance;
        BigDecimal netCashMovement;
        BigDecimal closingLedgerBalance;
        boolean reconciliationHolds;
        BigDecimal socialFundPeriodNet;
        String socialFundNote;
    }

    @Value
    @Builder
    public static class MemberContributionSection {
        boolean singleMonth;
        String sheetTitle;
        List<String> headers;
        List<MemberContributionAggregate> rows;
        BigDecimal totalExpected;
        BigDecimal totalPaid;
        BigDecimal totalRemaining;
        BigDecimal totalOverpaid;
        long totalPeriodsCounted;
    }
}
