package rw.terimbere.csams.modules.incomeexpense.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.entity.LedgerEffect;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeExpenseCreateRequest {

    @NotNull
    private IncomeExpenseCategory category;

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    /** Required when category is ADJUSTMENT. */
    private LedgerEffect ledgerEffect;

    @NotNull
    @PastOrPresent(message = "Transaction date cannot be in the future")
    private LocalDate transactionDate;

    @Size(max = 128)
    private String reference;

    @Size(max = 2000)
    private String description;

    @Size(max = 2000)
    private String notes;

    @Size(max = 512)
    private String supportingFileKey;
}
