package rw.terimbere.csams.modules.fine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
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
@Table(name = "fines")
public class Fine extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false)
    private UUID memberUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fine_type", nullable = false, length = 32)
    private FineType fineType;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_mode", nullable = false, length = 32)
    private FineCalculationMode calculationMode;

    @Column(name = "source_contribution_id")
    private UUID sourceContributionId;

    @Column(name = "source_loan_id")
    private UUID sourceLoanId;

    @Column(name = "source_loan_installment_id")
    private UUID sourceLoanInstallmentId;

    @Column(name = "automatic_source_key", length = 80, unique = true)
    private String automaticSourceKey;

    @Column(name = "base_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal baseAmount;

    @Builder.Default
    @Column(name = "daily_increment_snapshot", nullable = false, precision = 19, scale = 4)
    private BigDecimal dailyIncrementSnapshot = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "overdue_days", nullable = false)
    private int overdueDays = 0;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Builder.Default
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingAmount;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "issued_date", nullable = false)
    private LocalDate issuedDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FineStatus status;

    @Column(name = "issued_by")
    private UUID issuedBy;
}
