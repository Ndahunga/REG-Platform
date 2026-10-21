package rw.terimbere.csams.modules.incomeexpense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeExpenseRejectRequest {

    @NotBlank
    @Size(max = 2000)
    private String rejectionReason;
}
