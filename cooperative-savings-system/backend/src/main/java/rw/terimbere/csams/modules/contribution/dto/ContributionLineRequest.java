package rw.terimbere.csams.modules.contribution.dto;

import jakarta.validation.constraints.DecimalMin;
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
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContributionLineRequest {

    @NotNull
    private UUID memberUserId;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal paidAmount;

    @PastOrPresent(message = "Payment date cannot be in the future")
    private LocalDate paymentDate;

    @Size(max = 128, message = "Payment reference must be at most 128 characters")
    private String paymentReference;

    @Size(max = 2000, message = "Notes must be at most 2000 characters")
    private String notes;

    private ContributionStatus status;

    private BigDecimal expectedAmount;
}
