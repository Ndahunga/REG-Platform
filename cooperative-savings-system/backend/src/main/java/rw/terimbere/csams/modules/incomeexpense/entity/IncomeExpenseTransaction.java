package rw.terimbere.csams.modules.incomeexpense.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
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
@Table(name = "income_expense_transactions")
public class IncomeExpenseTransaction extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private IncomeExpenseCategory category;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Required when category is ADJUSTMENT; must be null otherwise. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_effect", length = 16)
    private LedgerEffect ledgerEffect;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "reference", length = 128)
    private String reference;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "supporting_file_key", length = 512)
    private String supportingFileKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32)
    private IncomeExpenseApprovalStatus approvalStatus;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;
}
