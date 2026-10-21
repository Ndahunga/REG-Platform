package rw.terimbere.csams.modules.loan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.audit.dto.ApprovalEventResponse;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.LoanGuaranteeMode;
import rw.terimbere.csams.modules.loan.entity.LoanPenaltyFrequency;
import rw.terimbere.csams.modules.loan.entity.LoanPenaltyType;
import rw.terimbere.csams.modules.loan.entity.LoanRepaymentDateModel;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanResponse {

    private UUID id;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberName;
    private BigDecimal requestedAmount;
    private BigDecimal approvedAmount;
    private BigDecimal principalAmount;
    private BigDecimal interestRatePercent;
    private InterestType interestType;
    private int termMonths;
    private BigDecimal interestAmount;
    private boolean prorataEnabled;
    private Integer firstPeriodDays;
    private BigDecimal regularMonthlyInterest;
    private BigDecimal firstPeriodInterest;
    private BigDecimal totalRepayment;
    private BigDecimal equalInstallmentAmount;
    private LoanRepaymentDateModel repaymentDateModel;
    private boolean loanPenaltyEnabled;
    private LoanPenaltyType penaltyType;
    private BigDecimal penaltyRateOrAmount;
    private LoanPenaltyFrequency penaltyFrequency;
    private Integer gracePeriodDays;
    private String allocationOrder;
    private boolean scheduleFinalized;
    private List<LoanInstallmentResponse> repaymentSchedule;
    private BigDecimal outstandingPrincipal;
    private BigDecimal outstandingInterest;
    private BigDecimal outstandingPenalty;
    private BigDecimal totalRepaidPrincipal;
    private BigDecimal totalRepaidInterest;
    private BigDecimal totalRepaidPenalty;
    private LocalDate requestDate;
    private LocalDate approvalDate;
    private LocalDate disbursementDate;
    private LocalDate dueDate;
    private LoanStatus status;
    private LoanGuaranteeMode guaranteeMode;
    private Integer shareCount;
    private BigDecimal sharePercent;
    private BigDecimal maxLoanByShares;
    private String purpose;
    private String rejectionReason;
    private UUID requestedBy;
    private UUID approvedBy;
    private UUID disbursedBy;
    private UUID firstApprovedBy;
    private Instant firstApprovedAt;
    private String firstApproverRole;
    private LoanApplicationFormResponse applicationForm;
    private LoanEligibilityResponse eligibility;
    private LoanGuarantorResponse guarantor;
    private List<ApprovalEventResponse> approvalHistory;
    private Instant createdAt;
    private Instant updatedAt;
}
