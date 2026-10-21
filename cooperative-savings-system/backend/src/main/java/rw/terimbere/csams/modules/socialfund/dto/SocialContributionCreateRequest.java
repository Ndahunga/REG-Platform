package rw.terimbere.csams.modules.socialfund.dto;

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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialContributionCreateRequest {

    /** When null, defaults to the current user (self-submit). */
    private UUID memberUserId;

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    @PastOrPresent(message = "Contribution date cannot be in the future")
    private LocalDate contributionDate;

    @Size(max = 128)
    private String paymentReference;

    @Size(max = 2000)
    private String notes;

    @Size(max = 512)
    private String evidenceFileKey;
}
