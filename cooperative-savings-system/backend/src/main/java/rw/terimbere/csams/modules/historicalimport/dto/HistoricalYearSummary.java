package rw.terimbere.csams.modules.historicalimport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalYearSummary {

    private int year;
    @Builder.Default
    private int members = 0;
    @Builder.Default
    private int contributions = 0;
    @Builder.Default
    private int specialContributions = 0;
    @Builder.Default
    private int socialContributions = 0;
    @Builder.Default
    private int socialDisbursements = 0;
    @Builder.Default
    private int loans = 0;
    @Builder.Default
    private int repayments = 0;
    @Builder.Default
    private int fines = 0;
    @Builder.Default
    private int finePayments = 0;
    @Builder.Default
    private int investments = 0;
    @Builder.Default
    private int investmentReturns = 0;
    @Builder.Default
    private int income = 0;
    @Builder.Default
    private int expenses = 0;
    @Builder.Default
    private int payouts = 0;
}
