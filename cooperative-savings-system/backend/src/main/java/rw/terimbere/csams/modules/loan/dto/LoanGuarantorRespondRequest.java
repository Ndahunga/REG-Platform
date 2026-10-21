package rw.terimbere.csams.modules.loan.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanGuarantorRespondRequest {

    @NotNull
    private Boolean accepted;

    @Size(max = 2000)
    private String comment;
}
