package rw.terimbere.csams.modules.socialfund.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialDisbursementCreateRequest {

    @NotNull
    private UUID beneficiaryMemberUserId;

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    @PastOrPresent(message = "Disbursement date cannot be in the future")
    private LocalDate disbursementDate;

    @NotBlank
    @Size(max = 2000)
    private String reason;

    @Size(max = 2000)
    private String notes;

    @Size(max = 512)
    private String evidenceFileKey;
}
