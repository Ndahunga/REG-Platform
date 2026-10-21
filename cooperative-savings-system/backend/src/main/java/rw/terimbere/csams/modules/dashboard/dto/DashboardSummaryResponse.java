package rw.terimbere.csams.modules.dashboard.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryResponse {

    private long totalMembers;
    private long activeMembers;
    private BigDecimal regularContributionsTotal;
    private BigDecimal specialContributionsTotal;
    private BigDecimal actualContributionsTotal;
    private BigDecimal availableGroupFunds;
    private long pendingSpecialApprovals;
    private BigDecimal totalLoanPrincipal;
    private BigDecimal outstandingLoanPrincipal;
    private BigDecimal loanInterestEarned;
    private long overdueLoansCount;
    private long totalFines;
    private long unpaidFines;
    private long paidFines;
    private long membersWithFines;
    private BigDecimal approvedFineIncome;
    private long pendingFinePayments;
    private long approvedFinePayments;
    private long rejectedFinePayments;
    private BigDecimal socialFundBalance;
    private BigDecimal socialContributionsTotal;
    private BigDecimal socialDisbursementsTotal;
    private long pendingSocialApprovals;
    private long activeInvestmentsCount;
    private BigDecimal investmentCapital;
    private BigDecimal investmentProfits;
    private BigDecimal otherIncomeTotal;
    private BigDecimal generalExpensesTotal;
    private BigDecimal interestExpensesTotal;
    private BigDecimal availableInterest;
    private long pendingPayoutsCount;
    private BigDecimal totalConfirmedPayouts;
    private String currency;
}
