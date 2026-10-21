package rw.terimbere.csams.modules.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "loans")
public class Loan extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false)
    private UUID memberUserId;

    @Column(name = "requested_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedAmount;

    @Column(name = "approved_amount", precision = 19, scale = 4)
    private BigDecimal approvedAmount;

    @Column(name = "principal_amount", precision = 19, scale = 4)
    private BigDecimal principalAmount;

    /** Snapshot of settings rate at loan creation — never mutated when settings change. */
    @Column(name = "interest_rate_percent", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRatePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_type", nullable = false, length = 32)
    private InterestType interestType;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "interest_amount", precision = 19, scale = 4)
    private BigDecimal interestAmount;

    @Builder.Default
    @Column(name = "prorata_enabled", nullable = false)
    private boolean prorataEnabled = false;

    @Column(name = "first_period_days")
    private Integer firstPeriodDays;

    @Column(name = "equal_installment_amount", precision = 19, scale = 4)
    private BigDecimal equalInstallmentAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_date_model", length = 32)
    private LoanRepaymentDateModel repaymentDateModel;

    @Builder.Default
    @Column(name = "loan_penalty_enabled", nullable = false)
    private boolean loanPenaltyEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", length = 64)
    private LoanPenaltyType penaltyType;

    @Column(name = "penalty_rate_or_amount", precision = 19, scale = 4)
    private BigDecimal penaltyRateOrAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_frequency", length = 32)
    private LoanPenaltyFrequency penaltyFrequency;

    @Column(name = "grace_period_days")
    private Integer gracePeriodDays;

    @Column(name = "allocation_order", length = 64)
    private String allocationOrder;

    @Builder.Default
    @Column(name = "outstanding_principal", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingPrincipal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "outstanding_interest", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingInterest = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_repaid_principal", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalRepaidPrincipal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_repaid_interest", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalRepaidInterest = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "outstanding_penalty", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingPenalty = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_repaid_penalty", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalRepaidPenalty = BigDecimal.ZERO;

    @Column(name = "request_date", nullable = false)
    private LocalDate requestDate;

    @Column(name = "approval_date")
    private LocalDate approvalDate;

    @Column(name = "disbursement_date")
    private LocalDate disbursementDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LoanStatus status;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "guarantee_mode", nullable = false, length = 32)
    private LoanGuaranteeMode guaranteeMode = LoanGuaranteeMode.SELF;

    @Column(name = "share_count")
    private Integer shareCount;

    @Column(name = "share_percent", precision = 9, scale = 4)
    private BigDecimal sharePercent;

    @Column(name = "max_loan_by_shares", precision = 19, scale = 4)
    private BigDecimal maxLoanByShares;

    @Column(name = "purpose", length = 2000)
    private String purpose;

    /**
     * Set only for loans registered through the opening-balance migration: an opening receivable with no
     * historical disbursement/repayment ledger rows. Its approval/disbursement actors stay empty because
     * the original manual loan was never approved in this system.
     */
    @Column(name = "opening_import_id")
    private UUID openingImportId;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "disbursed_by")
    private UUID disbursedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "application_snapshot", columnDefinition = "jsonb")
    private String applicationSnapshot;

    @Column(name = "first_approved_by")
    private UUID firstApprovedBy;

    @Column(name = "first_approved_at")
    private Instant firstApprovedAt;

    @Column(name = "first_approver_role", length = 64)
    private String firstApproverRole;
}
