package rw.terimbere.csams.modules.incomeexpense.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseApprovalStatus;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.entity.LedgerEffect;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeExpenseResponse {

    private UUID id;
    private UUID cooperativeId;
    private IncomeExpenseCategory category;
    private BigDecimal amount;
    private LedgerEffect ledgerEffect;
    private LocalDate transactionDate;
    private String reference;
    private String description;
    private String notes;
    private String supportingFileKey;
    private IncomeExpenseApprovalStatus approvalStatus;
    private UUID recordedBy;
    private UUID approvedBy;
    private Instant approvedAt;
    private String rejectionReason;
    private Instant createdAt;
    private Instant updatedAt;
}
