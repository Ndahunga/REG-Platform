package rw.terimbere.csams.modules.loan.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanScheduleResponse {

    private BigDecimal principal;
    private BigDecimal monthlyInterestRatePercent;
    private int numberOfInstallments;
    private LoanRepaymentDateModel repaymentDateModel;
    private boolean prorataEnabled;
    private Integer firstPeriodDays;
    private Integer daysInFirstMonth;
    private BigDecimal regularMonthlyInterest;
    private BigDecimal firstPeriodInterest;
    private BigDecimal totalInterest;
    private BigDecimal totalRepayment;
    private BigDecimal equalInstallmentAmount;
    private boolean scheduleFinalized;
    private List<LoanInstallmentResponse> installments;
}
